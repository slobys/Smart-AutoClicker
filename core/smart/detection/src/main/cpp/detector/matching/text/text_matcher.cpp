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
    constexpr int MIN_DIRECT_TEXT_SIDE = 12;
    constexpr int MAX_DIRECT_TEXT_SHORT_SIDE = 192;
    constexpr int MAX_DIRECT_TEXT_LONG_SIDE = 960;
    constexpr float MAX_DIRECT_TEXT_ASPECT_RATIO = 12.0f;
}

bool TextMatcher::init(const std::string& detectionModelPath, const std::map<std::string, std::string>& recognitionModels) {
    if (!recognitionModels.empty()) {
        defaultRecognitionModelId = recognitionModels.begin()->first;
    }
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

    // Keep the original image as the primary path. Most clear text is recognized here without
    // paying for extra inference or exposing it to preprocessing artifacts.
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
    updateTextMatchingResult(
            recognizerResults,
            conditionText,
            detectionArea,
            threshold,
            MIN_ENHANCED_TEXT_CONFIDENCE,
            "enhanced");

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
            threshold);

    float bestRankingScore = 0.0f;

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

        float rankingScore = score;
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
        // recognizer confidence itself unchanged for the user's threshold check.
        if (rankingScore < bestRankingScore) continue;
        bestRankingScore = rankingScore;

        currentMatchingResult.updateResults(
                detectionArea,
                recognizerResult.boundingBox,
                score,
                recognizedNumber);

        if ((int) score >= threshold) {
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

bool TextMatcher::updateTextMatchingResult(
        const std::vector<TextRecognizerResult>& recognizerResults,
        const std::string& conditionText,
        const cv::Rect& detectionArea,
        int threshold,
        float minimumRecognizerConfidence,
        const char* passName
) {
    const float minimumSimilarity = std::clamp(
            static_cast<float>(threshold) / 100.0f,
            0.0f,
            1.0f);

    for (const auto& recognizerResult: recognizerResults) {
        if (recognizerResult.confidence < minimumRecognizerConfidence) continue;

        const float score = bestSubstringSimilarity(
                recognizerResult.text,
                conditionText,
                minimumSimilarity) * 100.0f;
        LOGD(
                "TextMatcher",
                "Pass=%s; score=%f; ocrConfidence=%f; recognized=%s",
                passName,
                score,
                recognizerResult.confidence,
                recognizerResult.text.c_str());

        if (score <= currentMatchingResult.getResultConfidence()) continue;

        currentMatchingResult.updateResults(detectionArea, recognizerResult.boundingBox, score);
        if (score >= static_cast<float>(threshold)) {
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
        int threshold
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

            return result.confidence * 100.0f >= static_cast<float>(threshold);
        });
    };

    auto results = recognizeTextInImage(
            rgbScreenCrop,
            recognitionModelId,
            MIN_NUMBER_DETECTION_SIDE);
    if (hasUsableNumericCandidate(results)) return results;

    // Game counters are often tiny outlined glyphs rendered over colorful icons. A contrast-
    // enhanced grayscale pass suppresses most hue changes while preserving those glyph edges.
    cv::Mat gray;
    cv::cvtColor(rgbScreenCrop, gray, cv::COLOR_RGB2GRAY);
    cv::Mat enhancedGray;
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(3.0, cv::Size(8, 8));
    clahe->apply(gray, enhancedGray);

    cv::Mat enhancedRgb;
    cv::cvtColor(enhancedGray, enhancedRgb, cv::COLOR_GRAY2RGB);
    results = recognizeTextInImage(
            enhancedRgb,
            recognitionModelId,
            MIN_NUMBER_DETECTION_SIDE);
    if (hasUsableNumericCandidate(results)) return results;

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
        results = recognizeTextInImage(
                binaryRgb,
                recognitionModelId,
                MIN_NUMBER_DETECTION_SIDE);
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

    results = recognizeCorner(rgbScreenCrop);
    if (hasUsableNumericCandidate(results)) return results;

    return recognizeCorner(enhancedRgb);
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

float TextMatcher::bestSubstringSimilarity(const std::string& recognized, const std::string& target, float minSimilarity) {
    if (recognized.empty() || target.empty()) return 0.f;

    // Fast exact substring match
    if (recognized.find(target) != std::string::npos) return 1.f;

    const auto recognizedCodePoints = decodeUtf8(recognized);
    const auto targetCodePoints = decodeUtf8(target);
    const int targetLen = static_cast<int>(targetCodePoints.size());
    const int recognizedLen = static_cast<int>(recognizedCodePoints.size());

    // Fast path
    if (recognizedLen <= targetLen + 2) {
        return similarityCodePoints(recognizedCodePoints, targetCodePoints, minSimilarity);
    }

    // Allow small OCR insertions/deletions. Window boundaries are Unicode code points, never the
    // continuation bytes inside a Chinese, Japanese, Korean, or other multibyte character.
    float bestScore = 0.f;
    const int minWindow = std::max(1, targetLen - 2);
    const int maxWindow = std::min(recognizedLen, targetLen + 4);

    std::vector<std::uint32_t> window;
    for (int windowSize = minWindow; windowSize <= maxWindow; ++windowSize) {
        for (int start = 0; start <= recognizedLen - windowSize; ++start) {
            window.assign(
                    recognizedCodePoints.begin() + start,
                    recognizedCodePoints.begin() + start + windowSize);

            float score = similarityCodePoints(window, targetCodePoints, minSimilarity);
            if (score > bestScore) {
                bestScore = score;

                // Early success exit
                if (bestScore >= 0.95f) return bestScore;
            }
        }
    }

    return bestScore;
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
