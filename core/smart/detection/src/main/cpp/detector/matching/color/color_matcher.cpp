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
        int threshold, bool findInArea
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
    cv::Mat mask;
    if (findInArea) mask = cv::Mat::zeros(screenCroppedColorMat.size(), CV_8UC1);
    for (int y = 0; y < screenCroppedColorMat.rows; ++y) {
        const auto* row = screenCroppedColorMat.ptr<cv::Vec4b>(y);
        for (int x = 0; x < screenCroppedColorMat.cols; ++x) {
            int difference = 0;
            for (int channel = 0; channel < 3; ++channel) {
                difference += std::abs(static_cast<int>(row[x][channel]) -
                                       static_cast<int>(conditionColor[channel]));
            }
            if (!findInArea) ++differences[difference];
            if (findInArea && difference * 100.0 <= threshold * 765.0) mask.at<uchar>(y, x) = 255;
        }
    }
    if (findInArea) {
        // Opt-in area search, never change legacy 75% coverage into "any pixel" implicitly.
        // A connected patch must have at least 9 pixels; isolated noise cannot trigger it.
        cv::Mat labels, stats, centroids;
        const int count = cv::connectedComponentsWithStats(mask, labels, stats, centroids, 8);
        int best = 0;
        for (int label = 1; label < count; ++label) {
            if (stats.at<int>(label, cv::CC_STAT_AREA) >= 9 &&
                (!best || stats.at<int>(label, cv::CC_STAT_AREA) > stats.at<int>(best, cv::CC_STAT_AREA))) best = label;
        }
        if (!best) {
            currentMatchingResult.updateResults(detectionArea, 1.0);
            return;
        }
        // A ring/L-shape's geometric centre may be background. Return a real interior pixel.
        const cv::Rect bounds(stats.at<int>(best, cv::CC_STAT_LEFT), stats.at<int>(best, cv::CC_STAT_TOP),
                              stats.at<int>(best, cv::CC_STAT_WIDTH), stats.at<int>(best, cv::CC_STAT_HEIGHT));
        cv::Mat component = labels(bounds) == best;
        // Distance data is needed only around this component, not the entire capture. Release
        // the full-size label buffers first to avoid overlapping their peak memory on large ROIs.
        labels.release(); mask.release(); stats.release(); centroids.release();
        cv::Mat distance;
        cv::distanceTransform(component, distance, cv::DIST_L2, 3);
        cv::Point point;
        cv::minMaxLoc(distance, nullptr, nullptr, nullptr, &point);
        point += bounds.tl();
        const auto pixel = screenCroppedColorMat.at<cv::Vec4b>(point);
        double difference = 0;
        for (int channel = 0; channel < 3; ++channel) difference += std::abs(pixel[channel] - conditionColor[channel]);
        currentMatchingResult.updateResults(cv::Rect(detectionArea.tl() + point, cv::Size(1, 1)), difference / 765.0);
        currentMatchingResult.markResultAsDetected();
        return;
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
