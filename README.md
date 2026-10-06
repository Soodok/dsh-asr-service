# DSH ASR Service

**An Android app that provides system-level offline speech recognition.**

Install it once, and any app that uses the standard `android.speech.SpeechRecognizer`
API gets offline recognition — no cloud, no API key, audio never leaves the device.

[中文说明见下方](#中文说明)

---

## Why

Android's speech recognition depends on a recognizer being installed on the device.
Many phones — especially Chinese ROMs — ship without one, so voice input simply fails.
This app fills that gap: it registers itself as a system `RecognitionService`, so
other apps can use it through the standard API with **zero code changes**.

## Features

- **System-level `RecognitionService`** — selectable as the device's recognizer
- **Multiple models**, all downloadable from a China-friendly mirror (`hf-mirror.com`)
- **Streaming and offline** decoding paths
- **Bilingual** (Chinese + English), plus multilingual models
- **Built-in self-check** — verifies service registration, model loading and the full
  recognition chain, and tells you exactly what to fix
- **One-tap "set as system default"** — via Shizuku (adb identity) or root; falls back
  to clear instructions when neither is available
- **Minimal permissions** — microphone and network only
- **Dark UI**, no ads, no tracking, no telemetry

## Models

All models come from [`hf-mirror.com`](https://hf-mirror.com) (a mirror of Hugging Face,
reachable from mainland China). Sizes are the actual file sizes.

| Model | Languages | Size | Streaming | Notes |
|---|---|---|---|---|
| `zipformer-zh-14m-int8` | Chinese | ~25 MB | ✅ | Smallest, good starting point |
| `zipformer-en-20m-int8` | English | ~30 MB | ✅ | Smallest English option |
| `paraformer-zh-small-int8` | Chinese | ~78 MB | ❌ | Balanced accuracy/size |
| `paraformer-zh-int8` | Chinese | ~230 MB | ❌ | Higher accuracy |
| `sensevoice-small-int8` | zh / en / ja / ko / yue | ~230 MB | ❌ | Punctuation included |
| `zipformer-bilingual-zh-en-int8` | Chinese + English | ~190 MB | ✅ | Best for mixed speech |

Models are provided by [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0).

## Requirements

- Android 8.0 (API 26) or newer
- arm64-v8a device
- ~50 MB for the app, plus disk space for whichever models you download

## Install

1. Install the APK.
2. Open the app → **Models** → download a model. Start with `zipformer-zh-14m-int8`
   (small and fast); switch to a larger one later if accuracy matters more.
3. Make the system use it, **any one** of these:
   - Tap **Set as system default** in the app (needs Shizuku or root), or
   - System Settings → Languages & input → Voice input → select this app, or
   - Let the calling app specify this service directly (see below).
4. Tap **Run check** — it verifies everything and reports what to fix.

## For app developers

The service is a standard `RecognitionService`. Two ways to use it:

```kotlin
// ① System default (nothing to do — just use the normal API)
val r = SpeechRecognizer.createSpeechRecognizer(context)

// ② Bind this service explicitly (works even when the ROM hides the
//    "voice input" settings page, which is common on Chinese ROMs)
val component = ComponentName("app.dsh.asr", "app.dsh.asr.AsrRecognitionService")
val r = SpeechRecognizer.createSpeechRecognizer(context, component)
```

For option ②, declare package visibility in your manifest (Android 11+):

```xml
<queries>
    <intent><action android:name="android.speech.RecognitionService" /></intent>
    <package android:name="app.dsh.asr" />
</queries>
```

The `<intent>` entry lets you **enumerate** recognizers; the `<package>` entry is
required to **bind a specific component** — without it the bind silently fails.

## Build

```bash
# Requires JDK 17 and Android SDK (compileSdk 36)
./gradlew assembleDebug -Pabi=arm64-v8a     # or x86_64 for emulators
```

The sherpa-onnx AAR is expected at `app/libs/sherpa-onnx-1.13.8.aar`
(download from the [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)).

## Privacy

- Audio is processed **entirely on device**. Nothing is uploaded.
- The only network access is **downloading models** from the mirror.
- No analytics, no crash reporting, no advertising, no account required.
- Permissions: `RECORD_AUDIO` (obviously) and `INTERNET` (model downloads).

## License

[Apache License 2.0](LICENSE).

Bundled/used components:
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Apache-2.0
- [Shizuku](https://github.com/RikkaApps/Shizuku-API) — Apache-2.0
- Recognition models — see each model's repository for its license

---

# 中文说明

**一个为 Android 提供系统级离线语音识别的应用。**

装一次，任何使用标准 `android.speech.SpeechRecognizer` 接口的应用就获得了离线识别能力
—— 不联网、不要 API Key、录音不出设备。

## 为什么需要

Android 的语音识别依赖设备上已安装的识别服务。很多手机（尤其国内 ROM）出厂不带，
语音输入会直接失败。本应用补上这一环：把自己注册成系统 `RecognitionService`，
其它应用**零改动**即可通过标准接口使用。

## 特性

- **系统级识别服务** —— 可被选为设备的识别引擎
- **多个模型**，全部从国内可直连的 `hf-mirror.com` 下载
- **流式与离线**两条解码路径
- **中英双语**，另有多语言模型
- **内置自检** —— 检查服务注册、模型加载与完整识别链路，并明确告诉你该怎么修
- **一键设为系统默认** —— 通过 Shizuku（adb 身份）或 root；两者都没有时给出清晰指引
- **权限极简** —— 只要麦克风与网络
- **暗色界面**，无广告、无追踪、无统计

## 模型

全部来自 [`hf-mirror.com`](https://hf-mirror.com)（Hugging Face 的国内镜像）。体积为实际文件大小。

| 模型 | 语言 | 体积 | 流式 | 说明 |
|---|---|---|---|---|
| `zipformer-zh-14m-int8` | 中文 | 约 25 MB | ✅ | 最小，建议先试这个 |
| `zipformer-en-20m-int8` | 英文 | 约 30 MB | ✅ | 英文最小选项 |
| `paraformer-zh-small-int8` | 中文 | 约 78 MB | ❌ | 精度/体积均衡 |
| `paraformer-zh-int8` | 中文 | 约 230 MB | ❌ | 精度更高 |
| `sensevoice-small-int8` | 中英日韩粤 | 约 230 MB | ❌ | 自带标点 |
| `zipformer-bilingual-zh-en-int8` | 中英 | 约 190 MB | ✅ | 中英混说最佳 |

模型由 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 提供（Apache-2.0）。

## 环境要求

- Android 8.0（API 26）及以上
- arm64-v8a 设备
- 应用约 50 MB，另需空间下载所选模型

## 安装使用

1. 安装 APK。
2. 打开应用 → **识别模型** → 下载一个模型。建议先下 `zipformer-zh-14m-int8`
   （小且快），之后若更看重准确率再换大的。
3. 让系统使用它，**以下任一**即可：
   - 在应用里点 **设为系统默认**（需 Shizuku 或 root），或
   - 系统设置 → 语言和输入法 → 语音输入 → 选中本应用，或
   - 让调用方应用直接指定本服务（见下）。
4. 点 **开始检测** —— 它会验证全部环节并告诉你差什么。

## 给开发者

本服务是标准的 `RecognitionService`，两种用法：

```kotlin
// ① 系统默认（无需任何处理，正常调用即可）
val r = SpeechRecognizer.createSpeechRecognizer(context)

// ② 显式绑定本服务（国产 ROM 常把"语音输入"设置页藏起来时依然可用）
val component = ComponentName("app.dsh.asr", "app.dsh.asr.AsrRecognitionService")
val r = SpeechRecognizer.createSpeechRecognizer(context, component)
```

用第 ② 种方式时，需要在你的 manifest 里声明包可见性（Android 11+）：

```xml
<queries>
    <intent><action android:name="android.speech.RecognitionService" /></intent>
    <package android:name="app.dsh.asr" />
</queries>
```

`<intent>` 让你能**枚举**识别服务；`<package>` 才是**绑定指定组件**所必需的
—— 缺了它会静默绑定失败。

## 构建

```bash
# 需要 JDK 17 与 Android SDK（compileSdk 36）
./gradlew assembleDebug -Pabi=arm64-v8a     # 模拟器用 x86_64
```

sherpa-onnx 的 AAR 需放在 `app/libs/sherpa-onnx-1.13.8.aar`
（从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载）。

## 隐私

- 音频**全程在本机处理**，不上传任何数据。
- 唯一的网络访问是**下载模型**。
- 无统计、无崩溃上报、无广告、无需账号。
- 权限只有 `RECORD_AUDIO`（麦克风）与 `INTERNET`（下模型）。

## 许可

[Apache License 2.0](LICENSE)。

使用/打包的组件：
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) —— Apache-2.0
- [Shizuku](https://github.com/RikkaApps/Shizuku-API) —— Apache-2.0
- 识别模型 —— 各自仓库的许可为准
