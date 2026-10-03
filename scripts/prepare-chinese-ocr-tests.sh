#!/usr/bin/env bash
# Only isolated instrumentation data. Runtime APKs do not include this extra model.
set -euo pipefail
package=com.buzbuz.smartautoclicker.core.detection.test
adb shell run-as "$package" id
cache="${RUNNER_TEMP:-/tmp}/klickr-chinese-ocr-fixtures-1.0.0"
mkdir -p "$cache/model"
archive="$cache/chinese_simplified.zip"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 --connect-timeout 30 --max-time 300 \
    https://github.com/Nain57/Smart-AutoClicker/releases/download/recognition-models-1.0.0/chinese_simplified.zip \
    --output "$archive"
fi
printf '%s  %s\n' f926054e3cf3f7cd937ffd8a87058d9b257ee2feb4af9db67568e949cac5f205 "$archive" | sha256sum --check -
unzip -o -q "$archive" -d "$cache/model"
adb shell run-as "$package" mkdir -p files/ocr-fixtures/chinese_simplified
for name in dict.txt rec.ncnn.param rec.ncnn.bin; do
  adb push "$cache/model/$name" "/data/local/tmp/klickr-ocr-$name"
  adb shell run-as "$package" cp "/data/local/tmp/klickr-ocr-$name" "files/ocr-fixtures/chinese_simplified/$name"
done
