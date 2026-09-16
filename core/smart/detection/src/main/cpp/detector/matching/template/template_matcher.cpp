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

    // During a swipe, Android can capture an intermediate frame where the target is directionally
    // blurred. Keep the exact pass authoritative, then try small horizontal and vertical motion-
    // blurred condition variants only as a fallback. This preserves static-image precision and
    // avoids globally loosening the user's tolerated-difference threshold.
    if (std::min(condition.getGrayMat().cols, condition.getGrayMat().rows) < MIN_MOTION_TEMPLATE_SIDE) {
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
        const double edgeSimilarity = getEdgeSimilarity(grayCrop, conditionGray);
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

double TemplateMatcher::getEdgeSimilarity(const cv::Mat& image, const cv::Mat& condition) {
    if (image.empty() || condition.empty() || image.size() != condition.size()) return 0.0;

    cv::Mat imageGradientX;
    cv::Mat imageGradientY;
    cv::Mat conditionGradientX;
    cv::Mat conditionGradientY;
    cv::Sobel(image, imageGradientX, CV_32F, 1, 0);
    cv::Sobel(image, imageGradientY, CV_32F, 0, 1);
    cv::Sobel(condition, conditionGradientX, CV_32F, 1, 0);
    cv::Sobel(condition, conditionGradientY, CV_32F, 0, 1);

    cv::Mat imageMagnitude;
    cv::Mat conditionMagnitude;
    cv::magnitude(imageGradientX, imageGradientY, imageMagnitude);
    cv::magnitude(conditionGradientX, conditionGradientY, conditionMagnitude);

    cv::Mat normalizedImage;
    cv::Mat normalizedCondition;
    cv::normalize(imageMagnitude, normalizedImage, 0.0, 1.0, cv::NORM_MINMAX);
    cv::normalize(conditionMagnitude, normalizedCondition, 0.0, 1.0, cv::NORM_MINMAX);

    cv::Mat difference;
    cv::absdiff(normalizedImage, normalizedCondition, difference);
    return std::max(0.0, 1.0 - cv::mean(difference).val[0]);
}

bool TemplateMatcher::isTemplateInformative(const cv::Mat& condition) {
    if (condition.empty()) return false;

    cv::Scalar mean;
    cv::Scalar standardDeviation;
    cv::meanStdDev(condition, mean, standardDeviation);
    return standardDeviation.val[0] >= MIN_TEMPLATE_STDDEV;
}
