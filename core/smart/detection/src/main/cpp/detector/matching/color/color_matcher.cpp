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
#include <array>
#include <cmath>

#include "color_matcher.hpp"
#include "../../../logs/log.h"
#include "../../../utils/roi.h"


using namespace smartautoclicker;


void ColorMatcher::reset() {
    currentMatchingResult.reset();
}

ColorMatchingResult *ColorMatcher::getMatchingResults() {
    return &currentMatchingResult;
}

bool ColorMatcher::isRoiValidForMatching(const cv::Rect& screenRoi, const cv::Rect& roi) {
    if (roi.width <= 0 || roi.height <= 0 || !isRoiContainsOrEquals(screenRoi, roi)) {
        LOGD("Detector", "Can't detect color, detection area (x=%d, y=%d, w=%d, h=%d) is not contained in screen (w=%d, h=%d)",
             roi.x, roi.y, roi.width, roi.height,
             screenRoi.width, screenRoi.height);
        return false;
    }

    return true;
}

void ColorMatcher::matchColor(
        const ScreenImage& screenImage,
        const cv::Scalar& conditionColor,
        const cv::Rect& detectionArea,
        int threshold
) {

    // Crop the color screen image to get only the detection area
    cv::Mat screenCroppedColorMat = screenImage.cropColor(detectionArea);
    if (screenCroppedColorMat.empty()) {
        LOGE("ColorMatcher", "screenCroppedColorMat is empty after cropping.");
        return;
    }

    // Compare real pixels instead of averaging RGB first: red/blue must not become a fictitious
    // purple match. The 75th percentile tolerates a minority of moving highlights/edge pixels,
    // while requiring the selected area to be predominantly the requested colour. For a 1px
    // condition this is exactly the original RGB difference. Histogram memory is constant.
    std::array<int, 766> differences{};
    for (int y = 0; y < screenCroppedColorMat.rows; ++y) {
        const auto* row = screenCroppedColorMat.ptr<cv::Vec4b>(y);
        for (int x = 0; x < screenCroppedColorMat.cols; ++x) {
            int difference = 0;
            for (int channel = 0; channel < 3; ++channel) {
                difference += std::abs(static_cast<int>(row[x][channel]) -
                                       static_cast<int>(conditionColor[channel]));
            }
            ++differences[difference];
        }
    }
    const int requiredPixels = static_cast<int>(std::ceil(screenCroppedColorMat.total() * 0.75));
    int counted = 0;
    int quantileDifference = 0;
    for (; quantileDifference < 765; ++quantileDifference) {
        counted += differences[quantileDifference];
        if (counted >= requiredPixels) break;
    }
    const double diff = quantileDifference / 765.0;

    currentMatchingResult.updateResults(detectionArea, diff);

    // If the colors are OK, the result is valid
    if ((diff * 100) <= threshold) currentMatchingResult.markResultAsDetected();
}
