# VisionClick 图标收紧

2026-10-13，使用内置 `imagegen` 编辑原图。原图保留在 `visionclick-original.png`，新图替换应用的 `visionclick_artwork.png`。用户的红圈图仅用作保留范围参考，没有将红圈作为图案内容。

只保留完整圆角边框及其中的手机、触控圆环、手指和内部深色背景，移除边框外多余背景；透明区域使用 PNG Alpha。编辑工具保存的实际文件为 1254 × 1254。Android 的多密度旧版图标从该图通过原生 Drawable / Canvas 渲染生成，尺寸分别为 48、72、96、144、192 像素。

## 完整提示词

```text
Use case: background-extraction. Asset type: VisionClick Android launcher icon. Image 1 is the clean EDIT TARGET; image 2 is only the user's red-outline CROP GUIDE, not artwork to reproduce. Keep the COMPLETE existing rounded-square cyan frame and EVERYTHING inside it: the exact glowing cyan smartphone, concentric touch rings, robotic hand/finger, navy/teal inner gradient, existing geometry, colors, proportions, highlights and detail. Remove ONLY the excess navy background OUTSIDE the rounded-square frame. Tightly reframe the existing rounded-square logo as a square icon with its outer frame occupying approximately 96% of the output width and height, leaving at most 2% margin each side. The areas outside the rounded-square outline, especially corners, should have genuine transparent alpha. Inside the rounded square remains opaque and unchanged. This is a faithful crop and cutout, NOT a redesign or new illustration. Do not remove the rounded-square frame. Do not crop the phone or hand. Do not add any red markings, white border, text, or new decorations. Return a 1024x1024 PNG icon suitable for use as an app asset.
```

提示词的尺寸为请求值，工具实际输出尺寸以 1254 × 1254 的 PNG 文件为准；未另行裁切或绘制工具输出。
