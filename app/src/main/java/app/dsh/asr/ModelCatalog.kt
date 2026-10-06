package app.dsh.asr

/**
 * 模型文件：文件名 + hf-mirror 上的**精确字节数**（实测 Content-Length）。
 * 下载完成后按此校验，防止半截/被篡改的文件进入推理。
 */
data class ModelFile(val name: String, val bytes: Long)

enum class ModelKind {
    /** SenseVoice：非流式、多语种、带标点 */
    SENSE_VOICE,

    /** Paraformer：非流式、中文、带时间戳 */
    PARAFORMER,

    /** 流式 zipformer transducer：边说边出字 */
    STREAMING_TRANSDUCER,
}

data class AsrModel(
    val id: String,
    val name: String,
    val langs: String,
    val note: String,
    val repo: String,
    val kind: ModelKind,
    val files: List<ModelFile>,
    val recommended: Boolean = false,
) {
    val streaming: Boolean get() = kind == ModelKind.STREAMING_TRANSDUCER
    val totalBytes: Long get() = files.fold(0L) { acc, f -> acc + f.bytes }
}

object ModelCatalog {

    /** 国内可直连的镜像（hf-mirror.com）。下载 URL 形如 <base>/<repo>/resolve/main/<file> */
    const val MIRROR = "https://hf-mirror.com"

    fun url(model: AsrModel, file: ModelFile): String =
        "$MIRROR/${model.repo}/resolve/main/${file.name}"

    val MODELS: List<AsrModel> = listOf(
        AsrModel(
            id = "sensevoice-small-int8",
            name = "SenseVoice Small",
            langs = "中 · 英 · 日 · 韩 · 粤",
            note = "官方推荐：识别质量最好，自动加标点，支持中英混说",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            kind = ModelKind.SENSE_VOICE,
            files = listOf(
                ModelFile("model.int8.onnx", 239_233_841L),
                ModelFile("tokens.txt", 315_894L),
            ),
            recommended = true,
        ),
        AsrModel(
            id = "paraformer-zh-int8",
            name = "Paraformer 中文（大）",
            langs = "中",
            note = "纯中文，长句稳定；体积大",
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09",
            kind = ModelKind.PARAFORMER,
            files = listOf(
                ModelFile("model.int8.onnx", 227_330_205L),
                ModelFile("tokens.txt", 75_354L),
            ),
        ),
        AsrModel(
            id = "paraformer-zh-small-int8",
            name = "Paraformer 中文（小）",
            langs = "中",
            note = "轻量中文（82MB），下载快、占用低",
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
            kind = ModelKind.PARAFORMER,
            files = listOf(
                ModelFile("model.int8.onnx", 81_828_675L),
                ModelFile("tokens.txt", 75_352L),
            ),
        ),
        AsrModel(
            id = "zipformer-bilingual-zh-en-int8",
            name = "流式 Zipformer 中英双语",
            langs = "中 · 英",
            note = "边说边出字（流式），中英混说",
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            kind = ModelKind.STREAMING_TRANSDUCER,
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 181_895_032L),
                ModelFile("decoder-epoch-99-avg-1.onnx", 13_876_452L),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 3_228_404L),
                ModelFile("tokens.txt", 56_317L),
            ),
        ),
        AsrModel(
            id = "zipformer-zh-14m-int8",
            name = "流式 Zipformer 中文（轻量）",
            langs = "中",
            note = "25MB 流式中文，秒下；适合先装了验证链路",
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            kind = ModelKind.STREAMING_TRANSDUCER,
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 21_621_684L),
                ModelFile("decoder-epoch-99-avg-1.int8.onnx", 1_888_682L),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 1_795_562L),
                ModelFile("tokens.txt", 48_697L),
            ),
        ),
        AsrModel(
            id = "zipformer-en-20m-int8",
            name = "流式 Zipformer 英文（轻量）",
            langs = "英",
            note = "44MB 流式英文，纯英文场景更准",
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            kind = ModelKind.STREAMING_TRANSDUCER,
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 42_845_182L),
                ModelFile("decoder-epoch-99-avg-1.int8.onnx", 539_499L),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 259_572L),
                ModelFile("tokens.txt", 5_048L),
            ),
        ),
    )

    fun byId(id: String?): AsrModel? = MODELS.firstOrNull { it.id == id }

    /** 体积可读化：239.5 MB / 25.4 MB */
    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
