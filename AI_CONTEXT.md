# dsh-asr-service · 交接文档

> 独立 Android 应用：为系统提供**离线语音识别服务**（实现 `android.speech.RecognitionService`）。
> 其它 App（含 DSH Mobile）用标准 `android.speech.SpeechRecognizer` 即可调用，无需改动。
> 引擎：sherpa-onnx 1.13.8（本地推理，音频不出本机）。模型来自 hf-mirror.com（国内镜像）。

- 项目路径：`E:\Deepeek harness\dsh-asr-service`
- 包名 / applicationId：`app.dsh.asr`
- 版本：0.1.0（versionCode 1）
- 构建：compileSdk 36 / minSdk 26 / **targetSdk 28**（与 dsh-android 一致的 sideload 策略）
- 工具链：AGP 8.13.0 + Kotlin 2.1.0 + Gradle 9.1.0 + `JAVA_HOME=D:/新建文件夹/jbr`
  ```
  JAVA_HOME="D:/新建文件夹/jbr" "C:/Users/Qt/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle.bat" \
      -p "E:/Deepeek harness/dsh-asr-service" assembleDebug -Pabi=x86_64   # 或 -Pabi=arm64-v8a
  ```

## 当前进度（2026-10-06）

全部功能已实现并在模拟器（emulator-5554，Android 16 / x86_64）实测：

| 项 | 状态 | 证据 |
|---|---|---|
| 服务注册为系统识别服务 | ✅ 实测 | `dumpsys package app.dsh.asr \| grep -iA3 recognition` → `android.speech.RecognitionService: app.dsh.asr/.AsrRecognitionService filter`；`cmd package query-services -a android.speech.RecognitionService` → Service #2 |
| 设为系统默认识别服务 | ✅ 实测 | `settings put secure voice_recognition_service app.dsh.asr/app.dsh.asr.AsrRecognitionService` |
| SpeechRecognizer 全链路（绑定→录音→解码→回调） | ✅ 实测 | app 内「服务自检」：`onStartListening → AudioRecord → onReadyForSpeech →（真实音频）onStopListening → onResults: 嗯` |
| 无语音超时路径 | ✅ 实测 | 静音 8s → `error(6) ERROR_SPEECH_TIMEOUT` 送达调用方 |
| 流式模型推理（OnlineRecognizer） | ✅ 实测 | 25MB zh-14M zipformer + test_wavs/0.wav → `对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢`（770ms / 5.6s 音频，RTF 0.14） |
| 离线模型推理（OfflineRecognizer） | ✅ 实测 | 78MB paraformer-small + 同一 wav → `对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯`（81–111ms，RTF 0.01–0.02） |
| 模型下载（hf-mirror 国内源 + 字节校验） | ✅ 实测 | 模拟器内实测下载 24.2MB 与 78.1MB 两个模型，落盘字节数与目录登记值完全一致 |
| 断点续传 / 重试 / 取消 | ✅ 代码实现（**未做断网实测**） | `.part` + HTTP Range，最多 5 次指数退避 |
| 暗色 UI（首页/模型管理/设置） | ✅ 实测截图 | `E:/tmp/shot_main_selftest.png`、`shot_models.png`、`shot_settings2.png` |
| APK 权限仅 RECORD_AUDIO + INTERNET | ✅ 核对 | `aapt2 dump permissions` 输出仅两条 |
| 系统设置 UI 里选识别服务 | ⚠️ 该模拟器镜像无此页（Pixel 镜像把 `VOICE_INPUT_SETTINGS` 重定向到数字助理；AOSP 的 `Settings$VoiceInputSettingsActivity` 不存在）——真机待验 |
| 真实麦克风识别准确率 | ⚠️ 未验证（模拟器无有效麦克风输入；已用 WAV 文件替代验证推理链路） |

## 关键设计决策 / 踩过的坑

1. **sherpa 必须传 `null` AssetManager**（`OfflineRecognizer(null, cfg)` / `OnlineRecognizer(null, cfg)`）。
   模型放在外部私有目录（`/storage/emulated/0/Android/data/app.dsh.asr/files/models/...`）是"SD 卡路径"，
   若同时传了 AssetManager，sherpa 原生层 `file-utils.cc ReadFile` 直接 `FATAL` **abort 整个进程**（已实测踩到一次）。
2. 模型落盘位置 = `getExternalFilesDir(null)/models/<modelId>/`（免存储权限、用户可见可清理）。
3. 前台服务通知**不申请 FOREGROUND_SERVICE 权限**：先试 `startForeground`，失败自动退化为普通通知
   （实测 targetSdk 28 下 startForeground 会因缺权限被拒 → 已走退化分支，无异常）。
4. `settingsActivity` 用相对名 `.SettingsActivity`（AOSP `ComponentName.createRelative` 语义）。
5. 离线模型的中间结果由独立线程解码（只保留最新快照），与抓音线程解耦；最终解码用一把锁串行化
   （sherpa Recognizer 非线程安全）。
6. 端点判定：能量 VAD（自适应噪声底）+ 静音 1.2s（可调），支持 `RecognizerIntent.EXTRA_SPEECH_INPUT_*` 覆盖。

## 关键文件路径

```
app/src/main/java/app/dsh/asr/
├── AsrRecognitionService.kt   RecognitionService 实现（onStart/onStop/onCancel/onCheckRecognitionSupport）+ 通知
├── AsrEngine.kt               引擎缓存、CaptureSession（录音+VAD+流式/离线解码）、WAV 自检、音频工具
├── ModelCatalog.kt            6 个模型（名称/仓库/精确字节数/类型）
├── ModelStore.kt              落盘、状态、下载（Range 续传+字节校验+重试）、删除
├── DownloadBus.kt             应用级下载线程 + 进度监听
├── Prefs.kt                   默认模型/语言/流式偏好/端点参数
├── MainActivity.kt            状态自检页（服务注册、默认服务、模型、两项自检）
├── ModelsActivity.kt          模型管理页（下载/取消/删除/设为默认 + 进度条）
├── SettingsActivity.kt        识别设置页（同时是系统侧的 settingsActivity 入口）
└── Ui.kt                      设计令牌与小组件工具
app/src/main/res/xml/recognition_service.xml   <recognition-service android:settingsActivity=".SettingsActivity"/>
```

模型目录（名称 / 类型 / 体积 / hf-mirror 仓库）：
1. SenseVoice Small · 离线多语种（中英日韩粤+标点）· 228.5 MB · `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`
2. Paraformer 中文（大）· 离线中文 · 216.9 MB · `csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09`
3. Paraformer 中文（小）· 离线中文 · 78.1 MB · `csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09` ✅已实测
4. 流式 Zipformer 中英双语 · 流式 · 189.8 MB · `csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`
5. 流式 Zipformer 中文（轻量）· 流式 · 24.2 MB · `csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` ✅已实测
6. 流式 Zipformer 英文（轻量）· 流式 · 41.6 MB · `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`

产物 APK：
- `dsh-asr-service-0.1.0-arm64-debug.apk`（真机 arm64，33.0 MB）
- `dsh-asr-service-0.1.0-x86_64-debug.apk`（模拟器 x86_64，37.2 MB）
（`app/build/outputs/apk/debug/app-debug.apk` 是最近一次 `-Pabi` 的结果）

## 当前阻塞 / 未验证

1. **真实麦克风识别质量**：模拟器无有效麦克风输入，只验证到"录音→VAD→解码→回调"链路（真实音频由 WAV 提供）。
2. **系统设置 UI 选择识别服务**：测试用的 Pixel 镜像没有 AOSP 的语音输入设置页，只能在真机上验证
   （替代验证：用 adb 把 `voice_recognition_service` 指向本应用，SpeechRecognizer 全链路实测可用）。
3. **大模型（SenseVoice 228MB / Paraformer 216MB / 双语流式 190MB）**：只核对了 hf-mirror 的 HTTP
   Content-Length 与目录登记值一致，未在设备上下载与推理（按同一代码路径，小模型已验证）。
4. **DSH Mobile 端到端**：未在 DSH Mobile 里实测（本机 DSH Mobile 版本 1.2.84 的 `AsrManager` 走
   `SpeechRecognizer.createSpeechRecognizer` + `EXTRA_PREFER_OFFLINE`，与本服务兼容；未做真机联调）。

## 下一步首要任务

1. 真机（arm64）安装 `dsh-asr-service-0.1.0-arm64-debug.apk` → 授权麦克风 → 下载模型 →
   在系统「语音输入」里把识别服务切到「DSH 离线识别」→ 跑首页两项自检 + 真实说话识别。
2. 在 DSH Mobile 里点语音按钮，确认识别文本回填（零改动即可用）。
