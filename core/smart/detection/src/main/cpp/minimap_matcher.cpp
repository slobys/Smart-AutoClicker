/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <jni.h>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <vector>

namespace {
constexpr int SIZE = 192;
struct Vote { cv::Point2d delta; cv::Point origin; double score; };
bool masked(int x, int y, int patch, int radius) {
    // Exclude a square enclosing the rotating player icon, in both images.
    return x < SIZE / 2 + radius && x + patch > SIZE / 2 - radius &&
           y < SIZE / 2 + radius && y + patch > SIZE / 2 - radius;
}
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_buzbuz_smartautoclicker_core_detection_MinimapMatcher_matchNative(
        JNIEnv* env, jobject, jbyteArray reference, jbyteArray current, jint radius) {
    auto empty = [&]() { return env->NewDoubleArray(0); };
    if (!reference || !current || env->GetArrayLength(reference) != SIZE * SIZE ||
        env->GetArrayLength(current) != SIZE * SIZE || radius < 8 || radius > 40) return empty();
    try {
        cv::Mat a(SIZE, SIZE, CV_8UC1), b(SIZE, SIZE, CV_8UC1);
        env->GetByteArrayRegion(reference, 0, SIZE * SIZE, reinterpret_cast<jbyte*>(a.data));
        env->GetByteArrayRegion(current, 0, SIZE * SIZE, reinterpret_cast<jbyte*>(b.data));
        if (env->ExceptionCheck()) return nullptr;
        constexpr int patch = 24;
        std::vector<Vote> votes;
        // Stay inside the circular map border. Sixteen independent patches, not one
        // large template: an animated quest icon cannot vote for a whole translation.
        for (int y = 24; y <= 144; y += 40) for (int x = 24; x <= 144; x += 40) {
            if (masked(x, y, patch, radius)) continue;
            cv::Mat tile = a(cv::Rect(x, y, patch, patch));
            cv::Scalar mean, deviation;
            cv::meanStdDev(tile, mean, deviation);
            if (deviation[0] < 12) continue;
            cv::Mat scores;
            cv::matchTemplate(b, tile, scores, cv::TM_CCOEFF_NORMED);
            for (int by = 0; by < scores.rows; ++by) for (int bx = 0; bx < scores.cols; ++bx)
                if (masked(bx, by, patch, radius)) scores.at<float>(by, bx) = -1;
            double best;
            cv::Point location;
            cv::minMaxLoc(scores, nullptr, &best, nullptr, &location);
            if (best < .80) continue;
            // Repeating roads/tiles must have a unique peak, not just a high score.
            cv::rectangle(scores, cv::Rect(std::max(0, location.x - 5), std::max(0, location.y - 5),
                std::min(scores.cols, location.x + 6) - std::max(0, location.x - 5),
                std::min(scores.rows, location.y + 6) - std::max(0, location.y - 5)), -1, cv::FILLED);
            double runnerUp;
            cv::minMaxLoc(scores, nullptr, &runnerUp);
            if (best - runnerUp < .08) continue;
            votes.push_back({cv::Point2d(location.x - x, location.y - y), {x, y}, best});
        }
        std::vector<Vote> consensus;
        for (const auto& seed : votes) {
            std::vector<Vote> group;
            for (const auto& vote : votes)
                if (cv::norm(seed.delta - vote.delta) <= 2.0) group.push_back(vote);
            if (group.size() > consensus.size()) consensus = group;
        }
        if (consensus.size() < 5 || consensus.size() * 3 < votes.size() * 2) return empty();
        int minX = SIZE, minY = SIZE, maxX = 0, maxY = 0;
        cv::Point2d delta;
        double confidence = 0;
        for (const auto& vote : consensus) {
            delta += vote.delta; confidence += vote.score;
            minX = std::min(minX, vote.origin.x); maxX = std::max(maxX, vote.origin.x);
            minY = std::min(minY, vote.origin.y); maxY = std::max(maxY, vote.origin.y);
        }
        if (maxX - minX < 70 || maxY - minY < 70) return empty();
        delta *= 1.0 / consensus.size(); confidence /= consensus.size();
        // Large jumps mean non-overlapping frames or a teleport, not a valid next step.
        if (cv::norm(delta) > 65) return empty();
        jdouble values[] = {delta.x, delta.y, confidence, static_cast<double>(consensus.size())};
        auto result = env->NewDoubleArray(4);
        if (result) env->SetDoubleArrayRegion(result, 0, 4, values);
        return result;
    } catch (...) {
        // CV allocation or malformed input failures are localization failures, never a native crash.
        return empty();
    }
}
