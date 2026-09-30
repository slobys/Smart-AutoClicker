/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include <algorithm>
#include <cmath>

#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/imgproc/imgproc_c.h>

#include "template_matcher.hpp"
#include "../../../logs/log.h"
#include "../../../utils/roi.h"


using namespace smartautoclicker;

namespace {
    constexpr int MAX_CANDIDATE_COUNT = 5;
    constexpr double MIN_TEMPLATE_STDDEV = 5.0;
    constexpr double MIN_COLOR_DIFFERENCE = 5.0;
    constexpr double MAX_COLOR_DIFFERENCE = 30.0;
    constexpr double SHAPE_SCORE_WEIGHT = 0.55;
    constexpr double EDGE_SCORE_WEIGHT = 0.30;
    constexpr double COLOR_SCORE_WEIGHT = 0.15;
    constexpr int MIN_MOTION_TEMPLATE_SIDE = 12;
    constexpr double MOTION_FALLBACK_SCORE_MARGIN = 0.30;

    struct ScoredCandidate {
        bool valid = false;
        cv::Rect area;
        double shapeConfidence = 0.0;
        double compositeScore = 0.0;
    };
}


void TemplateMatcher::reset() {
    currentMatchingResult.reset();
}

TemplateMatchingResult *TemplateMatcher::getMatchingResults() {
    return &currentMatchingResult;
}

bool TemplateMatcher::isRoiValidForMatching(const cv::Rect& screenRoi, const cv::Rect& conditionRoi, const cv::Rect& roi) {
    if (!isRoiBiggerOrEquals(screenRoi, conditionRoi)) {
        LOGD("Detector", "Can't detectCondition, condition (w=%d, h=%d) is bigger than screen (w=%d, h=%d)",
             conditionRoi.width, conditionRoi.height, screenRoi.width, screenRoi.height);
        return false;
    }

    if (roi.width <= 0 || roi.height <= 0 || !isRoiContainsOrEquals(screenRoi, roi)) {
        LOGD("Detector", "Can't detectCondition, detection area (x=%d, y=%d, w=%d, h=%d) is not contained in screen (w=%d, h=%d)",
             roi.x, roi.y, roi.width, roi.height,
             screenRoi.width, screenRoi.height);
        return false;
    }

    if (!isRoiBiggerOrEquals(roi, conditionRoi)) {
        LOGD("Detector", "Can't detectCondition, condition (w=%d, h=%d) is bigger than detection area (x=%d, y=%d, w=%d, h=%d)",
             conditionRoi.width, conditionRoi.height, roi.x, roi.y, roi.width, roi.height);
        return false;
    }

    return true;
}

void TemplateMatcher::matchTemplate(
        const ScreenImage& screenImage,
        const ConditionImage& condition,
        const cv::Rect& detectionArea,
        int threshold
) {

    // Crop the gray screen image to get only the detection area
    cv::Mat screenCroppedGrayMat = screenImage.cropGray(detectionArea);
    if (screenCroppedGrayMat.empty()) {
        LOGE("TemplateMatcher", "screenCroppedGrayMat is empty after cropping.");
        return;
    }

    if (!isTemplateInformative(condition.getGrayMat())) {
        LOGW("TemplateMatcher", "Image condition does not contain enough visual information.");
        return;
    }

    if (runMatchingPass(
            screenImage,
            screenCroppedGrayMat,
            condition.getGrayMat(),
            condition.getColorMat(),
            detectionArea,
            threshold)) {
        return;
    }

    // Transparent HUDs keep their foreground but not the scenery underneath. A local high-pass
    // fallback removes broad background/lighting changes. It still checks spatial foreground RGB
    // and never reuses a position from an older frame.
    if (matchForeground(screenImage, condition, detectionArea, threshold)) return;

    // During a swipe, Android can capture an intermediate frame where the target is directionally
    // blurred. Keep the exact pass authoritative, then try small horizontal and vertical motion-
    // blurred condition variants only as a fallback. This preserves static-image precision and
    // avoids globally loosening the user's tolerated-difference threshold.
    if (std::min(condition.getGrayMat().cols, condition.getGrayMat().rows) < MIN_MOTION_TEMPLATE_SIDE) {
        return;
    }

    // A completely unrelated area normally produces a low normalized correlation. Avoid two
    // additional full-screen searches unless the sharp pass was at least reasonably close to the
    // requested score, which is the typical signature of directional motion blur.
    const double minimumRequiredConfidence = (100.0 - threshold) / 100.0;
    if (currentMatchingResult.getResultConfidence() <
        std::max(0.0, minimumRequiredConfidence - MOTION_FALLBACK_SCORE_MARGIN)) {
        return;
    }

    int blurLength = std::clamp(
            static_cast<int>(std::round(
                    static_cast<double>(std::min(condition.getGrayMat().cols, condition.getGrayMat().rows)) * 0.08)),
            5,
            9);
    if (blurLength % 2 == 0) ++blurLength;

    for (const cv::Size& blurKernel : {cv::Size(blurLength, 1), cv::Size(1, blurLength)}) {
        cv::Mat blurredConditionGray;
        cv::Mat blurredConditionColor;
        cv::blur(condition.getGrayMat(), blurredConditionGray, blurKernel);
        cv::blur(condition.getColorMat(), blurredConditionColor, blurKernel);

        currentMatchingResult.reset();
        if (runMatchingPass(
                screenImage,
                screenCroppedGrayMat,
                blurredConditionGray,
                blurredConditionColor,
                detectionArea,
                threshold)) {
            LOGD(
                    "TemplateMatcher",
                    "Motion-blur fallback matched with kernel %dx%d",
                    blurKernel.width,
                    blurKernel.height);
            return;
        }
    }
}

bool TemplateMatcher::matchForeground(
        const ScreenImage& screenImage, const ConditionImage& condition,
        const cv::Rect& detectionArea, int threshold
) {
    const cv::Mat& templateGray = condition.getGrayMat();
    // Bound additional CPU/memory on missing-target frames. Prefer a tight detection region.
    if (templateGray.total() > 256 * 256 || detectionArea.area() > 1024 * 1024 ||
        std::min(templateGray.cols, templateGray.rows) < 16) return false;

    auto highPass = [](const cv::Mat& source) {
        cv::Mat floating, background, detail;
        source.convertTo(floating, CV_32F);
        cv::GaussianBlur(floating, background, cv::Size(0, 0), 3.0);
        cv::subtract(floating, background, detail);
        return detail;
    };
    const cv::Mat detail = highPass(templateGray);
    double peak = 0;
    cv::minMaxLoc(detail, nullptr, &peak);
    if (peak < 20.0) return false;
    const cv::Mat foreground = detail > std::max(12.0, peak * 0.35);
    const int support = cv::countNonZero(foreground);
    if (support < 24 || support < templateGray.total() * 0.03 ||
        support > templateGray.total() * 0.65) return false;

    cv::Mat scores;
    cv::matchTemplate(highPass(screenImage.cropGray(detectionArea)), detail, scores, cv::TM_CCOEFF_NORMED);
    // Extra fallback must not accept a weak edge resemblance even with a permissive user setting.
    const double minimumScore = std::max(0.90, (100.0 - threshold) / 100.0);
    for (int i = 0; i < MAX_CANDIDATE_COUNT; ++i) {
        double score;
        cv::Point location;
        cv::minMaxLoc(scores, nullptr, &score, nullptr, &location);
        if (!std::isfinite(score) || score < minimumScore) return false;
        const cv::Rect area(detectionArea.x + location.x, detectionArea.y + location.y,
                            templateGray.cols, templateGray.rows);
        cv::Mat difference;
        cv::absdiff(screenImage.cropColor(area), condition.getColorMat(), difference);
        const cv::Scalar channels = cv::mean(difference, foreground);
        const double colorDifference = (channels[0] + channels[1] + channels[2]) * 100.0 / 765.0;
        // Also reject gross changes outside the edge mask (e.g. a similarly outlined coloured
        // icon). Background suppression must not discard the entire spatial colour signature.
        const double fullColorDifference = getPixelColorDiff(screenImage.cropColor(area), condition.getColorMat());
        if (colorDifference <= getMaxColorDifference(threshold) &&
            fullColorDifference <= std::min(30.0, 3.0 * getMaxColorDifference(threshold))) {
            currentMatchingResult.setDetectedResult(area, score);
            return true;
        }
        // Suppress the rejected candidate symmetrically, retaining separate nearby candidates.
        const int radius = std::max(1, std::min(templateGray.cols, templateGray.rows) / 4);
        const cv::Rect excluded = cv::Rect(location.x - radius, location.y - radius,
                                           2 * radius + 1, 2 * radius + 1) &
                                  cv::Rect(0, 0, scores.cols, scores.rows);
        scores(excluded).setTo(-1);
    }
    return false;
}

bool TemplateMatcher::runMatchingPass(
        const ScreenImage& screenImage,
        const cv::Mat& screenCroppedGray,
        const cv::Mat& conditionGray,
        const cv::Mat& conditionColor,
        const cv::Rect& detectionArea,
        int threshold
) {
    cv::Mat newResultsMat = cv::Mat(
            std::max(screenCroppedGray.rows - conditionGray.rows + 1, 0),
            std::max(screenCroppedGray.cols - conditionGray.cols + 1, 0),
            CV_32F);

    try {
        // Run OpenCv template matching
        cv::matchTemplate(
                screenCroppedGray,
                conditionGray,
                newResultsMat,
                cv::TM_CCOEFF_NORMED);
    } catch (const cv::Exception& e) {
        LOGE("TemplateMatcher", "OpenCV Exception caught: %s", e.what());
        throw;
    } catch (const std::exception& e) {
        LOGE("TemplateMatcher", "Standard Exception caught: %s", e.what());
        throw; // Rethrow
    } catch (...) {
        LOGE("TemplateMatcher", "Unknown exception caught!");
        throw std::runtime_error("Unknown exception in TemplateMatcher");
    } // Rethrow the Exceptions to be caught by the JNI wrapper

    // Parse result Mat to check for matching
    parseMatchingResult(
            screenImage,
            conditionGray,
            conditionColor,
            detectionArea,
            threshold,
            newResultsMat);
    return currentMatchingResult.isDetected();
}

void TemplateMatcher::parseMatchingResult(
        const ScreenImage& screenImage,
        const cv::Mat& conditionGray,
        const cv::Mat& conditionColor,
        const cv::Rect& detectionArea,
        int threshold,
        cv::Mat& matchingResult
) {

    ScoredCandidate bestCandidate;
    const double maxColorDifference = getMaxColorDifference(threshold);
    const cv::Mat normalizedConditionEdges = getNormalizedEdgeMagnitude(conditionGray);

    for (int candidateIndex = 0; candidateIndex < MAX_CANDIDATE_COUNT; ++candidateIndex) {

        // Mark previous results as invalid, if any
        if (!currentMatchingResult.getResultArea().empty()) {
            currentMatchingResult.invalidateCurrentResult(
                    conditionGray,
                    matchingResult);
        }

        // Look for new best match
        currentMatchingResult.updateResults(
                detectionArea,
                conditionGray,
                matchingResult);

        // Check if the highest result is above threshold. If not, we will never find.
        const double shapeConfidence = currentMatchingResult.getResultConfidence();
        if (!isShapeConfidenceValid(shapeConfidence, threshold)) break;

        // Check if result area is valid. If not, check next possible match
        if (!isRoiBiggerOrEquals(screenImage.getRoi(), currentMatchingResult.getResultArea())) continue;

        // Validate the spatial color layout, not only the average color. Two different icons can
        // have the same grayscale structure and mean HSV values while their colored pixels differ.
        cv::Mat colorCrop = screenImage.cropColor(currentMatchingResult.getResultArea());
        const double colorDifference = getPixelColorDiff(colorCrop, conditionColor);
        if (colorDifference > maxColorDifference) continue;

        const cv::Mat grayCrop = screenImage.cropGray(currentMatchingResult.getResultArea());
        const double edgeSimilarity = getEdgeSimilarity(grayCrop, normalizedConditionEdges);
        const double colorSimilarity = std::max(0.0, 1.0 - colorDifference / 100.0);
        const double compositeScore =
                shapeConfidence * SHAPE_SCORE_WEIGHT +
                edgeSimilarity * EDGE_SCORE_WEIGHT +
                colorSimilarity * COLOR_SCORE_WEIGHT;

        LOGD("TemplateMatcher", "Candidate %d: shape=%f edge=%f colorDiff=%f score=%f",
             candidateIndex, shapeConfidence, edgeSimilarity, colorDifference, compositeScore);

        if (!bestCandidate.valid || compositeScore > bestCandidate.compositeScore) {
            bestCandidate.valid = true;
            bestCandidate.area = currentMatchingResult.getResultArea();
            bestCandidate.shapeConfidence = shapeConfidence;
            bestCandidate.compositeScore = compositeScore;
        }
    }

    if (bestCandidate.valid) {
        currentMatchingResult.setDetectedResult(bestCandidate.area, bestCandidate.shapeConfidence);
    }
}

bool TemplateMatcher::isShapeConfidenceValid(double confidence, int threshold) {
    return confidence >= ((100.0 - threshold) / 100.0);
}

double TemplateMatcher::getMaxColorDifference(int threshold) {
    return std::min(
            MAX_COLOR_DIFFERENCE,
            std::max(MIN_COLOR_DIFFERENCE, static_cast<double>(threshold)));
}

double TemplateMatcher::getPixelColorDiff(const cv::Mat& image, const cv::Mat& condition) {
    if (image.empty() || condition.empty() || image.size() != condition.size() || image.type() != condition.type()) {
        return 100.0;
    }

    cv::Mat difference;
    cv::absdiff(image, condition, difference);
    const cv::Scalar meanDifference = cv::mean(difference);
    const int comparedChannels = std::min(3, image.channels());
    if (comparedChannels <= 0) return 100.0;

    double totalDifference = 0.0;
    for (int channel = 0; channel < comparedChannels; ++channel) {
        totalDifference += meanDifference.val[channel];
    }

    return totalDifference * (100.0 / (255.0 * comparedChannels));
}

cv::Mat TemplateMatcher::getNormalizedEdgeMagnitude(const cv::Mat& image) {
    if (image.empty()) return {};

    cv::Mat imageGradientX;
    cv::Mat imageGradientY;
    cv::Sobel(image, imageGradientX, CV_32F, 1, 0);
    cv::Sobel(image, imageGradientY, CV_32F, 0, 1);

    cv::Mat imageMagnitude;
    cv::magnitude(imageGradientX, imageGradientY, imageMagnitude);

    cv::Mat normalizedMagnitude;
    cv::normalize(imageMagnitude, normalizedMagnitude, 0.0, 1.0, cv::NORM_MINMAX);
    return normalizedMagnitude;
}

double TemplateMatcher::getEdgeSimilarity(
        const cv::Mat& image,
        const cv::Mat& normalizedConditionEdges
) {
    if (image.empty() || normalizedConditionEdges.empty() || image.size() != normalizedConditionEdges.size()) {
        return 0.0;
    }

    const cv::Mat normalizedImageEdges = getNormalizedEdgeMagnitude(image);

    cv::Mat difference;
    cv::absdiff(normalizedImageEdges, normalizedConditionEdges, difference);
    return std::max(0.0, 1.0 - cv::mean(difference).val[0]);
}

bool TemplateMatcher::isTemplateInformative(const cv::Mat& condition) {
    if (condition.empty()) return false;

    cv::Scalar mean;
    cv::Scalar standardDeviation;
    cv::meanStdDev(condition, mean, standardDeviation);
    return standardDeviation.val[0] >= MIN_TEMPLATE_STDDEV;
}
