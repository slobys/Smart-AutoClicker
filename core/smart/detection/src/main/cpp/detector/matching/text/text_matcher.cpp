/*
 * Copyright (C) 2026 Kevin Buzeau
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

#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/imgproc/imgproc_c.h>
#include <cctype>
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

#include "text_matcher.hpp"
#include "../../../logs/log.h"
#include "../../../utils/roi.h"

using namespace smartautoclicker;

namespace {
    constexpr int MIN_NUMBER_DETECTION_SIDE = 384;
    constexpr int MIN_ENHANCED_TEXT_DETECTION_SIDE = 320;
    constexpr float MIN_MAPPED_NUMBER_CONFIDENCE = 0.25f;
    constexpr float MIN_CORNER_FALLBACK_CONFIDENCE = 0.25f;
    constexpr float MIN_ENHANCED_TEXT_CONFIDENCE = 0.25f;
    constexpr float SMALL_ROI_CORNER_RANKING_BONUS = 20.0f;
    constexpr float NUMBER_EXTRA_DIGIT_RANKING_BONUS = 1.5f;
    constexpr float NUMBER_CONSENSUS_RANKING_BONUS = 3.0f;
    constexpr float NUMBER_RANKING_SCORE_EPSILON = 0.01f;
    constexpr int MIN_DIRECT_TEXT_SIDE = 12;
    constexpr int MAX_DIRECT_TEXT_SHORT_SIDE = 192;
    constexpr int MAX_DIRECT_TEXT_LONG_SIDE = 960;
    constexpr float MAX_DIRECT_TEXT_ASPECT_RATIO = 12.0f;
    constexpr int MAX_DIRECT_NUMBER_SIDE = 384;
    constexpr float MAX_DIRECT_NUMBER_ASPECT_RATIO = 8.0f;
    constexpr float MIN_FOREGROUND_TEXT_CONFIDENCE = 0.60f;
    constexpr int MAX_FOREGROUND_OCR_PIXELS = 512 * 512;
    constexpr int MAX_CHROMATIC_PASSES = 3;
    constexpr int MAX_CHROMATIC_LINES = 12;
    constexpr int MIN_CHROMATIC_SATURATION = 96;
    constexpr int MIN_CHROMATIC_BRIGHTNESS = 192;
    constexpr float MIN_CHROMATIC_TEXT_CONFIDENCE = 0.80f;

    std::vector<TextDetectorResult> chromaticLines(const cv::Mat& ink) {
        std::vector<TextDetectorResult> lines;
        int top = -1;
        int lastInk = -1;
        auto addLine = [&]() {
            if (top < 0 || lastInk - top + 1 < 8 || lastInk - top + 1 > 96) return;
            cv::Mat band = ink.rowRange(top, lastInk + 1);
            std::vector<cv::Point> pixels;
            cv::findNonZero(band < 128, pixels);
            if (pixels.empty()) return;
            cv::Rect box = cv::boundingRect(pixels);
            box.y += top;
            const double occupancy = static_cast<double>(pixels.size()) / box.area();
            if (box.width < box.height || box.width > box.height * 12 ||
                occupancy < 0.04 || occupancy > 0.70) return;
            // Tight line crops keep short CJK glyphs large at the recognizer's fixed 48px height.
            // The detector's dilation/margins can otherwise shrink them into a large blank band.
            cv::Mat padded, rgb;
            cv::copyMakeBorder(ink(box), padded, 3, 3, 3, 3, cv::BORDER_CONSTANT, cv::Scalar(255));
            cv::cvtColor(padded, rgb, cv::COLOR_GRAY2RGB);
            lines.emplace_back(box, rgb);
        };
        for (int y = 0; y < ink.rows && lines.size() < MAX_CHROMATIC_LINES; ++y) {
            if (cv::countNonZero(ink.row(y) < 128) >= 2) {
                if (top < 0) top = y;
                lastInk = y;
            } else if (top >= 0 && y - lastInk > 2) {
                addLine();
                top = -1;
            }
        }
        if (top >= 0 && lines.size() < MAX_CHROMATIC_LINES) addLine();
        return lines;
    }
}

bool TextMatcher::init(const std::string& detectionModelPath, const std::map<std::string, std::string>& recognitionModels) {
    // Number conditions require the Latin digit model, independently of any text conditions.
    // std::map order previously selected CHINESE_* (or ARABIC) before LATIN in mixed scripts.
    // Keep the lowercase identifier used by existing native clients/tests; never pick another
    // alphabet silently, and clear the old identifier when models are reloaded.
    defaultRecognitionModelId = recognitionModels.count("LATIN") ? "LATIN" :
            recognitionModels.count("latin") ? "latin" : "";
    return textLocator->init(detectionModelPath) && textRecognizer->init(recognitionModels);
}

bool TextMatcher::isInitialized() const {
    return textLocator->isInitialized && textRecognizer->isInitialized;
}

void TextMatcher::clearResults() {
    currentMatchingResult.reset();
}

TextMatchingResult* TextMatcher::matchText(
        const ScreenImage& screenImage,
        const std::string& conditionText,
        const std::string& recognitionModelId,
        const cv::Rect& detectionArea,
        int threshold)
{
    clearResults();

    if (!isInitialized() || !isRoiValidForMatching(screenImage.getRoi(), detectionArea)) {
        LOGE("TextMatcher", "Can't match text, invalid state or RoI (x=%d, y=%d, w=%d, h=%d)",
             detectionArea.x, detectionArea.y, detectionArea.width, detectionArea.height);
        return &currentMatchingResult;
    }

    // Always localize text before accepting the primary OCR result. Reading the complete crop as
    // one line can merge nearby labels or animated-background details into the expected text and
    // return a false positive with an imprecise click position.
    auto recognizerResults = recognizeText(screenImage, detectionArea, recognitionModelId);
    if (updateTextMatchingResult(
            recognizerResults,
            conditionText,
            detectionArea,
            threshold,
            0.0f,
            "original")) {
        return &currentMatchingResult;
    }

    // Only failed primary matches use the more expensive fallback. CLAHE makes outlined and
    // low-contrast glyphs more uniform while a conservative unsharp mask recovers mildly blurred
    // edges. Fallback candidates must also meet a model-confidence floor to limit false positives.
    recognizerResults = recognizeEnhancedText(screenImage, detectionArea, recognitionModelId);
    if (updateTextMatchingResult(
            recognizerResults,
            conditionText,
            detectionArea,
            threshold,
            MIN_ENHANCED_TEXT_CONFIDENCE,
            "enhanced")) return &currentMatchingResult;

    // Hue changes can hide glyphs in grayscale even when one RGB channel still separates them.
    // Only failed compact regions pay for these two bounded background-removal passes.
    if (detectionArea.area() <= MAX_FOREGROUND_OCR_PIXELS) {
        cv::Mat rgbCrop;
        cv::cvtColor(screenImage.cropColor(detectionArea), rgbCrop, cv::COLOR_RGBA2RGB);
        std::vector<cv::Rect> confidentNonTargetAreas;
        if (matchChromaticText(rgbCrop, recognitionModelId, conditionText, detectionArea, threshold,
                               confidentNonTargetAreas))
            return &currentMatchingResult;
        for (bool darkText : {false, true}) {
            auto foregroundResults = recognizeForegroundText(rgbCrop, recognitionModelId, darkText);
            // Don't let an uncertain residual-image reading overwrite a confident, different word
            // from the same location (e.g. 帅门 -> 师门). Other locations/strong readings remain usable.
            foregroundResults.erase(std::remove_if(foregroundResults.begin(), foregroundResults.end(),
                    [&](const TextRecognizerResult& result) {
                        if (result.confidence >= MIN_CHROMATIC_TEXT_CONFIDENCE) return false;
                        return std::any_of(confidentNonTargetAreas.begin(), confidentNonTargetAreas.end(),
                                [&](const cv::Rect& box) {
                                    const int smaller = std::min(box.area(), result.boundingBox.area());
                                    const int larger = std::max(box.area(), result.boundingBox.area());
                                    return smaller > 0 && larger <= smaller * 4 &&
                                        (box & result.boundingBox).area() >= smaller * 0.75;
                                });
                    }), foregroundResults.end());
            if (updateTextMatchingResult(
                    foregroundResults,
                    conditionText, detectionArea, threshold, MIN_FOREGROUND_TEXT_CONFIDENCE,
                    darkText ? "dark-foreground" : "light-foreground")) break;
        }
    }

    return &currentMatchingResult;
}

TextMatchingResult* TextMatcher::matchNumber(
        const ScreenImage& screenImage,
        const cv::Rect& detectionArea,
        int threshold,
        NumberFormat numberFormat
) {
    clearResults();

    if (!isInitialized() || !isRoiValidForMatching(screenImage.getRoi(), detectionArea)) {
        LOGE("TextMatcher", "Can't match text, invalid state or RoI (x=%d, y=%d, w=%d, h=%d)",
             detectionArea.x, detectionArea.y, detectionArea.width, detectionArea.height);
        return &currentMatchingResult;
    }

    if (defaultRecognitionModelId.empty()) {
        LOGE("TextMatcher", "Can't match number, no recognition model available");
        return &currentMatchingResult;
    }

    // Recognize the text in the detectionArea
    auto recognizerResults = recognizeNumber(
            screenImage,
            detectionArea,
            defaultRecognitionModelId,
            threshold,
            numberFormat);

    const float minimumRequiredScore = std::clamp(
            100.0f - static_cast<float>(threshold),
            0.0f,
            100.0f);
    float bestRankingScore = 0.0f;
    int bestDigitCount = 0;
    int bestBoundingBoxArea = 0;
    bool bestIsAccepted = false;

    // Repeated readings produced from the original and background-suppressed crops are more
    // trustworthy than a one-off high-confidence reading caused by a moving effect. Keep the
    // reported confidence untouched and use agreement only to rank competing OCR candidates.
    std::vector<double> numericCandidateValues;
    numericCandidateValues.reserve(recognizerResults.size());
    for (const auto& result: recognizerResults) {
        if (result.confidence * 100.0f < minimumRequiredScore) continue;
        const std::string normalizedText = normalizeNumberText(result.text);
        if (!isNumber(normalizedText)) continue;
        const double value = stringToDouble(normalizedText, numberFormat);
        if (std::isfinite(value) && value != std::numeric_limits<double>::lowest())
            numericCandidateValues.push_back(value);
    }

    // Parse results and find matching candidate, if any
    for (const auto& recognizerResult: recognizerResults) {
        const std::string normalizedText = normalizeNumberText(recognizerResult.text);
        if (!isNumber(normalizedText)) continue;

        const bool isMappedFromLetters = normalizedText != recognizerResult.text;
        if (isMappedFromLetters && recognizerResult.confidence < MIN_MAPPED_NUMBER_CONFIDENCE) {
            continue;
        }

        const int shortestDetectionSide = std::min(detectionArea.width, detectionArea.height);
        const int smallTileSize = std::max(
                24,
                static_cast<int>(std::round(shortestDetectionSide * 0.42)));
        const int largeTileSize = std::max(
                24,
                static_cast<int>(std::round(shortestDetectionSide * 0.55)));
        const bool isCornerFallback =
                recognizerResult.boundingBox.width == recognizerResult.boundingBox.height &&
                (recognizerResult.boundingBox.width == smallTileSize ||
                 recognizerResult.boundingBox.width == largeTileSize);
        if (isCornerFallback && recognizerResult.confidence < MIN_CORNER_FALLBACK_CONFIDENCE) {
            continue;
        }

        float score = recognizerResult.confidence * 100;
        auto recognizedNumber = stringToDouble(normalizedText, numberFormat);
        if (!std::isfinite(recognizedNumber) || recognizedNumber == std::numeric_limits<double>::lowest()) continue;
        const bool isAccepted = score >= minimumRequiredScore;
        LOGD(
                "TextMatcher",
                "Score=%f; raw=%s; normalized=%s; recognized=%f; box=(%d,%d,%d,%d)",
                score,
                recognizerResult.text.c_str(),
                normalizedText.c_str(),
                recognizedNumber,
                recognizerResult.boundingBox.x,
                recognizerResult.boundingBox.y,
                recognizerResult.boundingBox.width,
                recognizerResult.boundingBox.height);

        const int digitCount = static_cast<int>(std::count_if(
                normalizedText.begin(),
                normalizedText.end(),
                [](unsigned char character) { return std::isdigit(character) != 0; }));

        // OCR confidence is averaged over decoded glyphs. A short fragment such as "22" can
        // therefore score slightly higher than the complete "124" after the thin leading 1 has
        // been dropped. Use a deliberately small completeness prior; confidence remains dominant
        // and the unmodified score is still used for the user's acceptance threshold.
        float rankingScore = score +
                static_cast<float>(std::min(std::max(digitCount - 1, 0), 4)) *
                NUMBER_EXTRA_DIGIT_RANKING_BONUS;
        const int agreeingCandidateCount = static_cast<int>(std::count_if(
                numericCandidateValues.begin(),
                numericCandidateValues.end(),
                [&](double candidateValue) {
                    return std::abs(candidateValue - recognizedNumber) <=
                            std::numeric_limits<double>::epsilon();
                }));
        rankingScore += static_cast<float>(
                std::min(std::max(agreeingCandidateCount - 1, 0), 2)) *
                NUMBER_CONSENSUS_RANKING_BONUS;
        if (std::max(detectionArea.width, detectionArea.height) <= MIN_NUMBER_DETECTION_SIDE) {
            const float centerX = static_cast<float>(
                    recognizerResult.boundingBox.x + recognizerResult.boundingBox.width / 2);
            const float centerY = static_cast<float>(
                    recognizerResult.boundingBox.y + recognizerResult.boundingBox.height / 2);
            const bool isBottomRightBadge =
                    centerX >= static_cast<float>(detectionArea.width) * 0.65f &&
                    centerY >= static_cast<float>(detectionArea.height) * 0.65f;
            if (isBottomRightBadge) rankingScore += SMALL_ROI_CORNER_RANKING_BONUS;
        }

        // Rank tiny game-counter candidates with a conservative corner prior while keeping the
        // recognizer confidence itself unchanged for the user's threshold check. OCR can return
        // both a complete number and shorter fragments with the exact same confidence. In that
        // case, keep the most complete candidate instead of letting the last fragment win.
        const int boundingBoxArea =
                recognizerResult.boundingBox.width * recognizerResult.boundingBox.height;
        const bool hasClearlyLowerScore =
                rankingScore + NUMBER_RANKING_SCORE_EPSILON < bestRankingScore;
        const bool hasEquivalentScore =
                std::abs(rankingScore - bestRankingScore) <= NUMBER_RANKING_SCORE_EPSILON;
        const bool isLessCompleteAtEquivalentScore =
                hasEquivalentScore &&
                (digitCount < bestDigitCount ||
                 (digitCount == bestDigitCount && boundingBoxArea <= bestBoundingBoxArea));
        // A below-threshold corner fragment must never evict a valid complete number merely
        // because it received a location/length bonus. Weak candidates cannot vote for consensus.
        if (bestIsAccepted && !isAccepted) continue;
        if (isAccepted == bestIsAccepted && (hasClearlyLowerScore || isLessCompleteAtEquivalentScore)) continue;

        bestIsAccepted = isAccepted;
        bestRankingScore = rankingScore;
        bestDigitCount = digitCount;
        bestBoundingBoxArea = boundingBoxArea;

        currentMatchingResult.updateResults(
                detectionArea,
                recognizerResult.boundingBox,
                score / 100.0f,
                recognizedNumber);

        if (score >= minimumRequiredScore) {
            currentMatchingResult.markResultAsDetected();
        }
    }

    return &currentMatchingResult;
}

bool TextMatcher::isRoiValidForMatching(const cv::Rect& screenRoi, const cv::Rect& roi) {
    if (roi.width <= 0 || roi.height <= 0 || !isRoiContainsOrEquals(screenRoi, roi)) {
        LOGD("TextMatcher", "Can't detect text, detection area (x=%d, y=%d, w=%d, h=%d) is not contained in screen (w=%d, h=%d)",
             roi.x, roi.y, roi.width, roi.height,
             screenRoi.width, screenRoi.height);
        return false;
    }

    return true;
}

std::vector<TextRecognizerResult> TextMatcher::recognizeText(
        const ScreenImage& screenImage,
        const cv::Rect& detectionArea,
        const std::string& recognitionModelId,
        int minimumDetectionSide
) {
    // Get the region of interest within the screen image and convert to RGB
    cv::Mat screenCrop = screenImage.cropColor(detectionArea);
    cv::Mat rgbScreenCrop;
    cv::cvtColor(screenCrop, rgbScreenCrop, cv::COLOR_RGBA2RGB);
    if (rgbScreenCrop.empty()) {
        LOGE("TextMatcher", "Can't get rgb screen crop");
        return {};
    }

    return recognizeTextInImage(rgbScreenCrop, recognitionModelId, minimumDetectionSide);
}

std::vector<TextRecognizerResult> TextMatcher::recognizeEnhancedText(
        const ScreenImage& screenImage,
        const cv::Rect& detectionArea,
        const std::string& recognitionModelId
) {
    cv::Mat screenCrop = screenImage.cropColor(detectionArea);
    if (screenCrop.empty()) {
        LOGE("TextMatcher", "Can't get screen crop for enhanced text recognition");
        return {};
    }

    cv::Mat rgbScreenCrop;
    cv::cvtColor(screenCrop, rgbScreenCrop, cv::COLOR_RGBA2RGB);

    cv::Mat gray;
    cv::cvtColor(rgbScreenCrop, gray, cv::COLOR_RGB2GRAY);

    cv::Mat contrastEnhanced;
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
    clahe->apply(gray, contrastEnhanced);

    cv::Mat blurred;
    cv::GaussianBlur(contrastEnhanced, blurred, cv::Size(0, 0), 0.9);

    cv::Mat sharpened;
    cv::addWeighted(contrastEnhanced, 1.65, blurred, -0.65, 0.0, sharpened);

    cv::Mat enhancedRgb;
    cv::cvtColor(sharpened, enhancedRgb, cv::COLOR_GRAY2RGB);

    auto results = recognizeTextInImage(
            enhancedRgb,
            recognitionModelId,
            MIN_ENHANCED_TEXT_DETECTION_SIDE);

    // A tightly selected text line may be readable by the recognizer even when the localization
    // model rejects its outline or animated background. Restrict direct recognition to compact
    // areas so a large game scene is never squeezed into one OCR line.
    const int shortestSide = std::min(enhancedRgb.cols, enhancedRgb.rows);
    const int longestSide = std::max(enhancedRgb.cols, enhancedRgb.rows);
    const float aspectRatio = static_cast<float>(longestSide) /
            static_cast<float>(std::max(1, shortestSide));
    if (shortestSide >= MIN_DIRECT_TEXT_SIDE &&
        shortestSide <= MAX_DIRECT_TEXT_SHORT_SIDE &&
        longestSide <= MAX_DIRECT_TEXT_LONG_SIDE &&
        aspectRatio <= MAX_DIRECT_TEXT_ASPECT_RATIO) {
        cv::Mat directCrop = enhancedRgb;
        if (directCrop.rows > directCrop.cols) {
            cv::rotate(directCrop, directCrop, cv::ROTATE_90_CLOCKWISE);
        }

        std::vector<TextDetectorResult> directDetectionResults;
        directDetectionResults.emplace_back(
                cv::Rect(0, 0, enhancedRgb.cols, enhancedRgb.rows),
                directCrop);
        auto directResults = textRecognizer->recognizeText(
                recognitionModelId,
                directDetectionResults);
        results.insert(results.end(), directResults.begin(), directResults.end());
    }

    return results;
}

bool TextMatcher::matchChromaticText(
        const cv::Mat& rgbCrop, const std::string& recognitionModelId,
        const std::string& conditionText, const cv::Rect& detectionArea, int threshold,
        std::vector<cv::Rect>& confidentNonTargetAreas
) {
    return recognizeChromaticText(rgbCrop, recognitionModelId, [&](const auto& results) {
        if (updateTextMatchingResult(results, conditionText, detectionArea, threshold,
                                     MIN_CHROMATIC_TEXT_CONFIDENCE, "chromatic")) return true;
        const float requiredSimilarity = std::clamp(1.f - threshold / 100.f, 0.f, 1.f);
        for (const auto& result : results) {
            if (result.confidence >= 0.90f &&
                bestSubstringMatch(result.text, conditionText, requiredSimilarity).similarity < requiredSimilarity)
                confidentNonTargetAreas.push_back(result.boundingBox);
        }
        return false;
    });
}

bool TextMatcher::recognizeChromaticText(
        const cv::Mat& rgbCrop, const std::string& recognitionModelId,
        const std::function<bool(const std::vector<TextRecognizerResult>&)>& acceptPass
) {
    if (rgbCrop.empty() || rgbCrop.total() > MAX_FOREGROUND_OCR_PIXELS ||
        std::min(rgbCrop.cols, rgbCrop.rows) < MIN_DIRECT_TEXT_SIDE) return false;

    // Luminance enhancement alone blends coloured HUD lettering into the scenery. Select actual
    // colour populations, not a game-specific colour or target word. Circular hue bins include red
    // on both sides of the HSV boundary. The small fixed pass budget bounds both memory and latency.
    cv::Mat hsv;
    cv::cvtColor(rgbCrop, hsv, cv::COLOR_RGB2HSV);
    std::array<int, 18> histogram{};
    std::array<int, 18> brightHistogram{};
    std::array<std::array<int, 256>, 18> brightness{};
    for (int y = 0; y < hsv.rows; ++y) {
        const auto* row = hsv.ptr<cv::Vec3b>(y);
        for (int x = 0; x < hsv.cols; ++x) {
            if (row[x][1] >= MIN_CHROMATIC_SATURATION && row[x][2] >= 80) {
                ++histogram[row[x][0] / 10];
                ++brightness[row[x][0] / 10][row[x][2]];
                if (row[x][2] >= MIN_CHROMATIC_BRIGHTNESS) ++brightHistogram[row[x][0] / 10];
            }
        }
    }
    for (int pass = 0; pass < MAX_CHROMATIC_PASSES; ++pass) {
        // Preserve bright-label priority: a large dim background must not consume all hue slots.
        const auto brightPeak = std::max_element(brightHistogram.begin(), brightHistogram.end());
        const bool brightPass = *brightPeak >= 16;
        const auto peak = brightPass ? brightPeak : std::max_element(histogram.begin(), histogram.end());
        if (*peak < 16) break;
        const int bin = static_cast<int>(std::distance(brightPass ? brightHistogram.begin() : histogram.begin(), peak));
        const int hue = bin * 10 + 5;
        // Use the bright end of this hue's observed population, not a fixed brightness of 192.
        // This retains dim coloured labels while still excluding same-hue dark outlines/ghosts.
        // A percentile (rather than max) prevents one spark/noise pixel from hiding the whole word.
        const int support = histogram[bin];
        int count = 0;
        int bright = 255;
        for (; bright > 80; --bright) {
            count += brightness[bin][bright];
            if (count >= std::max(8, support / 20)) break;
        }
        const int minimumBrightness = brightPass ? MIN_CHROMATIC_BRIGHTNESS :
                std::clamp(bright * 4 / 5, 80, MIN_CHROMATIC_BRIGHTNESS);
        for (int offset = -2; offset <= 2; ++offset) {
            histogram[(bin + offset + 18) % 18] = 0;
            brightHistogram[(bin + offset + 18) % 18] = 0;
        }

        cv::Mat ink(hsv.size(), CV_8UC1, cv::Scalar(255));
        for (int y = 0; y < hsv.rows; ++y) {
            const auto* source = hsv.ptr<cv::Vec3b>(y);
            auto* target = ink.ptr<uchar>(y);
            for (int x = 0; x < hsv.cols; ++x) {
                const int difference = std::abs(static_cast<int>(source[x][0]) - hue);
                if (std::min(difference, 180 - difference) <= 12 &&
                    source[x][1] >= MIN_CHROMATIC_SATURATION && source[x][2] >= minimumBrightness) {
                    target[x] = 0;
                }
            }
        }
        // Bright ink excludes darker, same-hue outlines/old lettering visible behind a translucent
        // HUD. Neutral text still has the original, contrast and residual passes.
        if (acceptPass(textRecognizer->recognizeText(recognitionModelId, chromaticLines(ink)))) return true;
    }
    return false;
}

std::vector<TextRecognizerResult> TextMatcher::recognizeForegroundText(
        const cv::Mat& rgbCrop, const std::string& recognitionModelId, bool darkText
) {
    if (rgbCrop.empty() || rgbCrop.total() > MAX_FOREGROUND_OCR_PIXELS ||
        std::min(rgbCrop.cols, rgbCrop.rows) < MIN_DIRECT_TEXT_SIDE) return {};

    // Opening/closing estimates broad scenery behind thin glyph strokes. The strongest RGB
    // residual retains coloured lettering with low luminance contrast. No earlier frames are
    // retained, so movement and disappearing text cannot create stale coordinates or ghost text.
    const int kernelSize = std::clamp(std::min(rgbCrop.cols, rgbCrop.rows) / 6, 7, 21) | 1;
    cv::Mat residual;
    cv::morphologyEx(rgbCrop, residual, darkText ? cv::MORPH_BLACKHAT : cv::MORPH_TOPHAT,
                    cv::getStructuringElement(cv::MORPH_RECT, cv::Size(kernelSize, kernelSize)));
    std::vector<cv::Mat> channels;
    cv::split(residual, channels);
    cv::Mat foreground;
    cv::max(channels[0], channels[1], foreground);
    cv::max(foreground, channels[2], foreground);
    double peak;
    cv::minMaxLoc(foreground, nullptr, &peak);
    if (peak < 16.0) return {};
    // Black glyphs on white preserve antialiased boundaries without hard binary thresholding.
    foreground.convertTo(foreground, CV_8U, -255.0 / peak, 255.0);
    cv::Mat foregroundRgb;
    cv::cvtColor(foreground, foregroundRgb, cv::COLOR_GRAY2RGB);
    auto results = recognizeTextInImage(foregroundRgb, recognitionModelId, MIN_ENHANCED_TEXT_DETECTION_SIDE);
    // Only a tight horizontal line is suitable for direct recognition, not an entire game scene.
    if (rgbCrop.rows <= MAX_DIRECT_TEXT_SHORT_SIDE && rgbCrop.cols <= MAX_DIRECT_TEXT_LONG_SIDE &&
        rgbCrop.cols >= rgbCrop.rows && rgbCrop.cols <= rgbCrop.rows * MAX_DIRECT_TEXT_ASPECT_RATIO) {
        std::vector<TextDetectorResult> direct;
        direct.emplace_back(cv::Rect(0, 0, rgbCrop.cols, rgbCrop.rows), foregroundRgb);
        auto directResults = textRecognizer->recognizeText(recognitionModelId, direct);
        results.insert(results.end(), directResults.begin(), directResults.end());
    }
    return results;
}

bool TextMatcher::updateTextMatchingResult(
        const std::vector<TextRecognizerResult>& recognizerResults,
        const std::string& conditionText,
        const cv::Rect& detectionArea,
        int threshold,
        float minimumRecognizerConfidence,
        const char* passName
) {
    // The UI stores a tolerated difference (0% means exact, 4% means at least 96% similar),
    // while the matcher works with similarity. Keep the conversion here next to both the
    // fuzzy-comparison cutoff and the final acceptance check so the two can never diverge.
    const float minimumRequiredScore = std::clamp(
            100.0f - static_cast<float>(threshold),
            0.0f,
            100.0f);
    const float minimumSimilarity = std::clamp(
            minimumRequiredScore / 100.0f,
            0.0f,
            1.0f);

    for (const auto& recognizerResult: recognizerResults) {
        const auto substringMatch = bestSubstringMatch(
                recognizerResult.text,
                conditionText,
                minimumSimilarity);
        const float score = substringMatch.similarity * 100.0f;
        // A clipped punctuation mark elsewhere in the line must not suppress a confident target;
        // conversely, unrelated high-confidence words must not hide uncertainty in the target.
        // Number detection continues to use the full reading's unchanged confidence.
        float targetConfidence = recognizerResult.confidence;
        const auto& characterConfidences = recognizerResult.characterConfidences;
        if (substringMatch.length > 0 && substringMatch.start >= 0 &&
            substringMatch.start + substringMatch.length <= static_cast<int>(characterConfidences.size())) {
            float total = 0.f;
            for (int i = substringMatch.start; i < substringMatch.start + substringMatch.length; ++i)
                total += characterConfidences[i];
            targetConfidence = total / static_cast<float>(substringMatch.length);
        }
        if (targetConfidence < minimumRecognizerConfidence) continue;
        LOGD(
                "TextMatcher",
                "Pass=%s; score=%f; ocrConfidence=%f; recognized=%s",
                passName,
                score,
                targetConfidence,
                recognizerResult.text.c_str());

        const float normalizedScore = score / 100.0f;
        if (normalizedScore <= currentMatchingResult.getResultConfidence()) continue;

        cv::Rect matchedBoundingBox = recognizerResult.boundingBox;
        const int recognizedLength = static_cast<int>(decodeUtf8(recognizerResult.text).size());
        if (recognizedLength > 0 &&
            substringMatch.length > 0 &&
            substringMatch.length < recognizedLength) {
            if (matchedBoundingBox.width >= matchedBoundingBox.height) {
                const double pixelsPerCharacter =
                        static_cast<double>(matchedBoundingBox.width) / recognizedLength;
                const int leftOffset = static_cast<int>(std::lround(
                        substringMatch.start * pixelsPerCharacter));
                const int matchedWidth = std::max(1, static_cast<int>(std::lround(
                        substringMatch.length * pixelsPerCharacter)));
                matchedBoundingBox.x += leftOffset;
                matchedBoundingBox.width = std::min(
                        matchedWidth,
                        recognizerResult.boundingBox.x + recognizerResult.boundingBox.width - matchedBoundingBox.x);
            } else {
                const double pixelsPerCharacter =
                        static_cast<double>(matchedBoundingBox.height) / recognizedLength;
                const int topOffset = static_cast<int>(std::lround(
                        substringMatch.start * pixelsPerCharacter));
                const int matchedHeight = std::max(1, static_cast<int>(std::lround(
                        substringMatch.length * pixelsPerCharacter)));
                matchedBoundingBox.y += topOffset;
                matchedBoundingBox.height = std::min(
                        matchedHeight,
                        recognizerResult.boundingBox.y + recognizerResult.boundingBox.height - matchedBoundingBox.y);
            }
        }

        currentMatchingResult.updateResults(
                detectionArea,
                matchedBoundingBox,
                normalizedScore);
        if (score >= minimumRequiredScore) {
            currentMatchingResult.markResultAsDetected();
            return true;
        }
    }

    return false;
}

std::vector<TextRecognizerResult> TextMatcher::recognizeNumber(
        const ScreenImage& screenImage,
        const cv::Rect& detectionArea,
        const std::string& recognitionModelId,
        int threshold,
        NumberFormat numberFormat
) {
    cv::Mat screenCrop = screenImage.cropColor(detectionArea);
    cv::Mat rgbScreenCrop;
    cv::cvtColor(screenCrop, rgbScreenCrop, cv::COLOR_RGBA2RGB);
    if (rgbScreenCrop.empty()) {
        LOGE("TextMatcher", "Can't get rgb screen crop for number recognition");
        return {};
    }

    auto hasUsableNumericCandidate = [&](const std::vector<TextRecognizerResult>& results) {
        return std::any_of(results.begin(), results.end(), [&](const TextRecognizerResult& result) {
            const std::string normalizedText = normalizeNumberText(result.text);
            if (!isNumber(normalizedText)) return false;

            const bool isMappedFromLetters = normalizedText != result.text;
            if (isMappedFromLetters && result.confidence < MIN_MAPPED_NUMBER_CONFIDENCE) return false;

            const int shortestDetectionSide = std::min(detectionArea.width, detectionArea.height);
            const int smallTileSize = std::max(
                    24,
                    static_cast<int>(std::round(shortestDetectionSide * 0.42)));
            const int largeTileSize = std::max(
                    24,
                    static_cast<int>(std::round(shortestDetectionSide * 0.55)));
            const bool isCornerFallback =
                    result.boundingBox.width == result.boundingBox.height &&
                    (result.boundingBox.width == smallTileSize ||
                     result.boundingBox.width == largeTileSize);
            if (isCornerFallback && result.confidence < MIN_CORNER_FALLBACK_CONFIDENCE) return false;

            const float minimumRequiredScore = std::clamp(
                    100.0f - static_cast<float>(threshold),
                    0.0f,
                    100.0f);
            return result.confidence * 100.0f >= minimumRequiredScore;
        });
    };

    auto hasConfidentNumericConsensus = [&](const std::vector<TextRecognizerResult>& results) {
        const float minimumRequiredScore = std::clamp(
                100.0f - static_cast<float>(threshold),
                0.0f,
                100.0f);
        std::vector<double> confidentValues;
        for (const auto& result: results) {
            if (result.confidence * 100.0f < minimumRequiredScore) continue;
            const std::string normalizedText = normalizeNumberText(result.text);
            if (!isNumber(normalizedText)) continue;
            const double value = stringToDouble(normalizedText, numberFormat);
            if (std::isfinite(value) && value != std::numeric_limits<double>::lowest())
                confidentValues.push_back(value);
        }

        return std::any_of(
                confidentValues.begin(),
                confidentValues.end(),
                [&](double value) {
                    return std::count_if(
                            confidentValues.begin(),
                            confidentValues.end(),
                            [&](double other) {
                                return std::abs(value - other) <=
                                        std::numeric_limits<double>::epsilon();
                            }) >= 2;
                });
    };

    cv::Mat gray;
    cv::cvtColor(rgbScreenCrop, gray, cv::COLOR_RGB2GRAY);
    cv::Mat enhancedGray;
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(3.0, cv::Size(8, 8));
    clahe->apply(gray, enhancedGray);
    cv::Mat enhancedRgb;
    cv::cvtColor(enhancedGray, enhancedRgb, cv::COLOR_GRAY2RGB);

    // A tightly selected number does not need the text locator. Read the whole crop twice: first
    // in its original form, then with colour and illumination changes suppressed. When both passes
    // agree we can return immediately, which is both more stable on animated game backgrounds and
    // cheaper than running the locator on every frame.
    const int directShortestSide = std::min(rgbScreenCrop.cols, rgbScreenCrop.rows);
    const int directLongestSide = std::max(rgbScreenCrop.cols, rgbScreenCrop.rows);
    const float directAspectRatio = static_cast<float>(directLongestSide) /
            static_cast<float>(std::max(1, directShortestSide));
    const bool isCompactDirectArea =
            directShortestSide >= MIN_DIRECT_TEXT_SIDE &&
            directLongestSide <= MAX_DIRECT_NUMBER_SIDE &&
            directAspectRatio <= MAX_DIRECT_NUMBER_ASPECT_RATIO;
    std::vector<TextRecognizerResult> results;
    if (isCompactDirectArea) {
        auto appendDirectRecognition = [&](const cv::Mat& image) {
            std::vector<TextDetectorResult> directDetectionResults;
            directDetectionResults.emplace_back(
                    cv::Rect(0, 0, image.cols, image.rows),
                    image);
            auto directResults = textRecognizer->recognizeText(
                    recognitionModelId,
                    directDetectionResults);
            results.insert(results.end(), directResults.begin(), directResults.end());
        };
        appendDirectRecognition(rgbScreenCrop);
        appendDirectRecognition(enhancedRgb);
        if (hasConfidentNumericConsensus(results)) return results;
    }

    // If the two direct reads disagree or cannot find a number, ask the locator for an independent
    // candidate. Preserve the direct results so matchNumber can prefer values confirmed by more
    // than one visual representation.
    auto locatedResults = recognizeTextInImage(
            rgbScreenCrop,
            recognitionModelId,
            MIN_NUMBER_DETECTION_SIDE);
    results.insert(results.end(), locatedResults.begin(), locatedResults.end());
    if ((!isCompactDirectArea && hasUsableNumericCandidate(results)) ||
        hasConfidentNumericConsensus(results)) return results;

    // Game counters are often tiny outlined glyphs rendered over colorful icons. A contrast-
    // enhanced grayscale pass suppresses most hue changes while preserving those glyph edges.
    auto enhancedResults = recognizeTextInImage(
            enhancedRgb,
            recognitionModelId,
            MIN_NUMBER_DETECTION_SIDE);
    results.insert(results.end(), enhancedResults.begin(), enhancedResults.end());
    if ((!isCompactDirectArea && hasUsableNumericCandidate(results)) ||
        hasConfidentNumericConsensus(results)) return results;

    // Keep coloured digits when animation makes them nearly equal to the background in grayscale.
    if (isCompactDirectArea) {
        if (recognizeChromaticText(rgbScreenCrop, recognitionModelId, [&](const auto& pass) {
            for (const auto& result : pass) {
                if (result.confidence >= MIN_CHROMATIC_TEXT_CONFIDENCE) results.push_back(result);
            }
            return hasConfidentNumericConsensus(results);
        })) return results;
        for (bool darkText : {false, true}) {
            auto foregroundResults = recognizeForegroundText(rgbScreenCrop, recognitionModelId, darkText);
            results.insert(results.end(), foregroundResults.begin(), foregroundResults.end());
            if (hasConfidentNumericConsensus(results)) return results;
        }
    }

    // Last resort for low-contrast digits: local thresholding separates the outline from a
    // non-uniform background better than a single global threshold.
    const int minDimension = std::min(gray.cols, gray.rows);
    int blockSize = std::min(31, minDimension % 2 == 0 ? minDimension - 1 : minDimension);
    if (blockSize >= 3) {
        cv::Mat binary;
        cv::adaptiveThreshold(
                enhancedGray,
                binary,
                255,
                cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                cv::THRESH_BINARY,
                blockSize,
                5.0);
        cv::Mat binaryRgb;
        cv::cvtColor(binary, binaryRgb, cv::COLOR_GRAY2RGB);
        auto binaryResults = recognizeTextInImage(
                binaryRgb,
                recognitionModelId,
                MIN_NUMBER_DETECTION_SIDE);
        results.insert(results.end(), binaryResults.begin(), binaryResults.end());
        if (hasUsableNumericCandidate(results)) return results;
    }

    // The text detector can still reject a single outlined glyph when it is surrounded by a
    // detailed icon. For small regions only, recognize two bottom-right crops where game quantity
    // badges are conventionally rendered. Restricting this fallback to the corner avoids treating
    // decorative icon edges as digits. This remains a last-resort path so normal numbers keep the
    // fast detector-based flow.
    const int shortestSide = std::min(rgbScreenCrop.cols, rgbScreenCrop.rows);
    const int longestSide = std::max(rgbScreenCrop.cols, rgbScreenCrop.rows);
    if (shortestSide < 28 || longestSide > MIN_NUMBER_DETECTION_SIDE) return results;

    auto recognizeCorner = [&](const cv::Mat& image) {
        std::vector<TextDetectorResult> tiles;
        tiles.reserve(2);
        const int edgeInset = std::max(4, static_cast<int>(std::round(shortestSide * 0.04)));
        for (double ratio : {0.42, 0.55}) {
            const int tileSize = std::min(
                    shortestSide - edgeInset,
                    std::max(24, static_cast<int>(std::round(shortestSide * ratio))));
            const cv::Rect tileArea(
                    image.cols - edgeInset - tileSize,
                    image.rows - edgeInset - tileSize,
                    tileSize,
                    tileSize);
            tiles.emplace_back(tileArea, image(tileArea));
        }
        return textRecognizer->recognizeText(recognitionModelId, tiles);
    };

    auto cornerResults = recognizeCorner(rgbScreenCrop);
    results.insert(results.end(), cornerResults.begin(), cornerResults.end());
    if (hasUsableNumericCandidate(results)) return results;

    cornerResults = recognizeCorner(enhancedRgb);
    results.insert(results.end(), cornerResults.begin(), cornerResults.end());
    return results;
}

std::vector<TextRecognizerResult> TextMatcher::recognizeTextInImage(
        const cv::Mat& rgbScreenCrop,
        const std::string& recognitionModelId,
        int minimumDetectionSide
) {
    // Find all regions containing text within the screen crop.
    auto detectorResults = textLocator->detectText(rgbScreenCrop, minimumDetectionSide);

    // Recognize the text in the regions detected
    return textRecognizer->recognizeText(recognitionModelId, detectorResults);
}

TextMatcher::SubstringMatchResult TextMatcher::bestSubstringMatch(
        const std::string& recognized,
        const std::string& target,
        float minSimilarity
) {
    if (recognized.empty() || target.empty()) return {};

    const auto recognizedCodePoints = decodeUtf8(recognized);
    const auto targetCodePoints = decodeUtf8(target);
    const int targetLen = static_cast<int>(targetCodePoints.size());
    const int recognizedLen = static_cast<int>(recognizedCodePoints.size());

    // Locate exact substrings using Unicode code points. A byte offset cannot be used for CJK text
    // because it would place the click several characters away from the matched word.
    if (recognizedLen >= targetLen) {
        for (int start = 0; start <= recognizedLen - targetLen; ++start) {
            bool exact = true;
            for (int offset = 0; offset < targetLen; ++offset) {
                if (normalizeCodePoint(recognizedCodePoints[start + offset]) !=
                    normalizeCodePoint(targetCodePoints[offset])) {
                    exact = false;
                    break;
                }
            }
            if (exact) return {1.f, start, targetLen};
        }
    }

    // Fast path
    if (recognizedLen <= targetLen + 2) {
        return {
                similarityCodePoints(recognizedCodePoints, targetCodePoints, minSimilarity),
                0,
                recognizedLen};
    }

    // Allow small OCR insertions/deletions. Window boundaries are Unicode code points, never the
    // continuation bytes inside a Chinese, Japanese, Korean, or other multibyte character.
    SubstringMatchResult bestMatch;
    const int minWindow = std::max(1, targetLen - 2);
    const int maxWindow = std::min(recognizedLen, targetLen + 4);

    std::vector<std::uint32_t> window;
    for (int windowSize = minWindow; windowSize <= maxWindow; ++windowSize) {
        for (int start = 0; start <= recognizedLen - windowSize; ++start) {
            window.assign(
                    recognizedCodePoints.begin() + start,
                    recognizedCodePoints.begin() + start + windowSize);

            float score = similarityCodePoints(window, targetCodePoints, minSimilarity);
            if (score > bestMatch.similarity) {
                bestMatch = {score, start, windowSize};

                // Early success exit
                if (bestMatch.similarity >= 0.95f) return bestMatch;
            }
        }
    }

    return bestMatch;
}

float TextMatcher::similarity(const std::string &recognized, const std::string &target, float minSimilarity) {
    if (recognized.empty() || target.empty()) return 0.f;

    return similarityCodePoints(decodeUtf8(recognized), decodeUtf8(target), minSimilarity);
}

float TextMatcher::similarityCodePoints(
        const std::vector<std::uint32_t>& recognized,
        const std::vector<std::uint32_t>& target,
        float minSimilarity
) {
    if (recognized.empty() || target.empty()) return 0.f;

    const int n = static_cast<int>(recognized.size());
    const int m = static_cast<int>(target.size());
    const int maxLen = std::max(n, m);

    // Early impossible length check
    int maxAllowedDistance = static_cast<int>((1.f - minSimilarity) * maxLen);
    if (std::abs(n - m) > maxAllowedDistance) return 0.f;

    comparisonPrevPrevRow.resize(m + 1);
    comparisonPrevRow.resize(m + 1);
    comparisonCurrRow.resize(m + 1);

    for (int j = 0; j <= m; ++j) comparisonPrevRow[j] = j;

    for (int i = 1; i <= n; ++i) {
        comparisonCurrRow[0] = i;
        int rowMin = comparisonCurrRow[0];

        const std::uint32_t ca = normalizeCodePoint(recognized[i - 1]);
        for (int j = 1; j <= m; ++j) {
            const std::uint32_t cb = normalizeCodePoint(target[j - 1]);

            int cost = (ca == cb) ? 0 : 1;

            int deletion = comparisonPrevRow[j] + 1;
            int insertion = comparisonCurrRow[j - 1] + 1;
            int substitution = comparisonPrevRow[j - 1] + cost;
            int value = std::min({ deletion, insertion, substitution });

            // Damerau transposition
            if (i > 1 && j > 1 &&
                ca == normalizeCodePoint(target[j - 2]) &&
                normalizeCodePoint(recognized[i - 2]) == cb) {
                value = std::min(value, comparisonPrevPrevRow[j - 2] + 1);
            }

            comparisonCurrRow[j] = value;
            rowMin = std::min(rowMin, value);
        }

        // Early exit
        if (rowMin > maxAllowedDistance) return 0.f;

        std::swap(comparisonPrevPrevRow, comparisonPrevRow);
        std::swap(comparisonPrevRow, comparisonCurrRow);
    }

    int distance = comparisonPrevRow[m];

    float score = 1.f - (float)distance / (float)maxLen;
    return std::max(0.f, score);
}

std::vector<std::uint32_t> TextMatcher::decodeUtf8(const std::string& text) {
    std::vector<std::uint32_t> codePoints;
    codePoints.reserve(text.size());

    for (std::size_t index = 0; index < text.size();) {
        const auto first = static_cast<unsigned char>(text[index]);
        std::uint32_t codePoint = first;
        std::size_t length = 1;

        if ((first & 0xE0u) == 0xC0u) {
            codePoint = first & 0x1Fu;
            length = 2;
        } else if ((first & 0xF0u) == 0xE0u) {
            codePoint = first & 0x0Fu;
            length = 3;
        } else if ((first & 0xF8u) == 0xF0u) {
            codePoint = first & 0x07u;
            length = 4;
        }

        bool valid = index + length <= text.size();
        for (std::size_t offset = 1; valid && offset < length; ++offset) {
            const auto continuation = static_cast<unsigned char>(text[index + offset]);
            if ((continuation & 0xC0u) != 0x80u) {
                valid = false;
                break;
            }
            codePoint = (codePoint << 6u) | (continuation & 0x3Fu);
        }

        if (!valid) {
            codePoint = first;
            length = 1;
        }

        codePoints.push_back(codePoint);
        index += length;
    }

    return codePoints;
}

std::uint32_t TextMatcher::normalizeCodePoint(std::uint32_t codePoint) {
    if (codePoint >= 'A' && codePoint <= 'Z') return codePoint + ('a' - 'A');
    return codePoint;
}

bool TextMatcher::isNumber(const std::string& text) {
    if (text.empty()) return false;

    bool hasDigit = false;
    for (char c : text) {
        if (std::isdigit(static_cast<unsigned char>(c))) {
            hasDigit = true;
            continue;
        }

        if (c == '.' || c == ',' || c == ' ' || c == '-' || c == '+') continue;

        return false;
    }

    return hasDigit;
}

std::string TextMatcher::normalizeNumberText(const std::string& text) {
    std::string normalized;
    normalized.reserve(text.size());

    for (char c : text) {
        if (std::isdigit(static_cast<unsigned char>(c)) ||
            c == '.' || c == ',' || c == ' ' || c == '-' || c == '+') {
            normalized += c;
            continue;
        }

        switch (c) {
            case 'O': case 'o': case 'Q':
                normalized += '0';
                break;
            case 'I': case 'i': case 'l': case 'L': case '|': case '!':
                normalized += '1';
                break;
            case 'Z': case 'z':
                normalized += '2';
                break;
            case 'S': case 's':
                normalized += '5';
                break;
            case 'G': case 'g':
                normalized += '6';
                break;
            case 'B': case 'b':
                normalized += '8';
                break;
            default:
                return {};
        }
    }

    return normalized;
}

double TextMatcher::stringToDouble(const std::string& text, NumberFormat format) {
    std::string s = text;
    // Remove spaces
    s.erase(std::remove(s.begin(), s.end(), ' '), s.end());

    char decimalSep;
    char thousandsSep;

    if (format == NumberFormat::DOT_DECIMAL) {
        decimalSep   = '.';
        thousandsSep = ',';
    } else if (format == NumberFormat::COMMA_DECIMAL) {
        decimalSep   = ',';
        thousandsSep = '.';
    } else {
        // AUTO: infer from structure.
        // If both separators are present, the last one is the decimal separator.
        size_t lastDot   = s.rfind('.');
        size_t lastComma = s.rfind(',');

        if (lastDot != std::string::npos && lastComma != std::string::npos) {
            decimalSep   = (lastDot > lastComma) ? '.' : ',';
            thousandsSep = (lastDot > lastComma) ? ',' : '.';
        } else if (lastComma != std::string::npos) {
            // Only a comma: thousands separator if exactly 3 digits follow it, else decimal.
            size_t digitsAfterComma = s.size() - lastComma - 1;
            if (digitsAfterComma == 3 && lastComma > 0) {
                decimalSep   = '.';
                thousandsSep = ',';
            } else {
                decimalSep   = ',';
                thousandsSep = '.';
            }
        } else {
            // Only a dot (or none): treat dot as decimal separator.
            decimalSep   = '.';
            thousandsSep = ',';
        }
    }

    // Strip thousands separators, then normalise decimal separator to '.'.
    std::string sanitized;
    sanitized.reserve(s.size());
    for (char c : s) {
        if (c == thousandsSep) continue;
        sanitized += (c == decimalSep) ? '.' : c;
    }

    try {
        return std::stod(sanitized);
    } catch (...) {
        return std::numeric_limits<double>::lowest();
    }
}
