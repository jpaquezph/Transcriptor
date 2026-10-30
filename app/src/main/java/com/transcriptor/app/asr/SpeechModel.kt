package com.transcriptor.app.asr

import androidx.annotation.StringRes
import com.transcriptor.app.R

class ModelFile(val name: String, val size: Long, val sha256: String)

/** Las URLs apuntan a un commit fijo para que los hashes sigan siendo válidos. */
enum class SpeechModel(
    val id: String,
    private val repo: String,
    private val revision: String,
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    val encoder: ModelFile,
    val decoder: ModelFile,
    val tokens: ModelFile,
    val joiner: ModelFile? = null,
    /** Modelo de streaming para grabar en vivo; no transcribe archivos. */
    val live: Boolean = false,
) {
    BASE(
        id = "base",
        repo = "sherpa-onnx-whisper-base",
        revision = "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
        label = R.string.model_base,
        description = R.string.model_base_desc,
        encoder = ModelFile(
            "base-encoder.int8.onnx", 29_120_534,
            "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
        ),
        decoder = ModelFile(
            "base-decoder.int8.onnx", 130_672_026,
            "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
        ),
        tokens = ModelFile("base-tokens.txt", 816_730, WHISPER_TOKENS_SHA256),
    ),
    SMALL(
        id = "small",
        repo = "sherpa-onnx-whisper-small",
        revision = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
        label = R.string.model_small,
        description = R.string.model_small_desc,
        encoder = ModelFile(
            "small-encoder.int8.onnx", 112_442_483,
            "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
        ),
        decoder = ModelFile(
            "small-decoder.int8.onnx", 262_226_114,
            "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
        ),
        tokens = ModelFile("small-tokens.txt", 816_730, WHISPER_TOKENS_SHA256),
    ),
    LIVE(
        id = "kroko-es",
        repo = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
        revision = "20cf7a4921613397841d31168796cade5b866585",
        label = R.string.model_live,
        description = R.string.model_live_desc,
        encoder = ModelFile(
            "encoder.onnx", 154_878_102,
            "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b",
        ),
        decoder = ModelFile(
            "decoder.onnx", 617_488,
            "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee",
        ),
        joiner = ModelFile(
            "joiner.onnx", 336_817,
            "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5",
        ),
        tokens = ModelFile(
            "tokens.txt", 6_385,
            "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f",
        ),
        live = true,
    );

    val files get() = listOfNotNull(encoder, decoder, joiner, tokens)
    val totalBytes get() = files.sumOf { it.size }
    val sizeMb get() = (totalBytes / 1_000_000).toInt()

    fun url(file: ModelFile) = "https://huggingface.co/csukuangfj/$repo/resolve/$revision/${file.name}"
}

private const val WHISPER_TOKENS_SHA256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
