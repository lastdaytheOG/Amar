package com.amar.vault.agent.brain

/**
 * Static catalog of models the agent can use.
 *
 * Why a static catalog instead of a remote manifest:
 *   - v1 keeps the set small (2 models).
 *   - Adding a network dependency to "what models exist" makes cold-start
 *     flaky — user opens the app offline, we can't even show a download picker.
 *   - Upgrading the catalog is a normal app update, which is fine for v1 cadence.
 *   - Phase 2 can layer a remote manifest on top, with this as the fallback.
 *
 * URLs below are PLACEHOLDERS. Before shipping, you need to:
 *   1. Download the actual .litertlm files from the LiteRT Community HF repo.
 *   2. Either host them on your own CDN or point directly at stable HF URLs.
 *   3. Compute real SHA-256 checksums and update the `sha256` fields.
 *   4. Verify the `sizeBytes` fields match reality.
 *
 * The checksums are a CORRECTNESS requirement — mismatches abort the load and
 * re-download. Without them, a truncated download or CDN corruption yields a
 * silently-broken model that generates gibberish.
 */
object ModelManifest {

    val GEMMA_3_1B: ModelSpec = ModelSpec(
        id = "gemma-3-1b-it-int4",
        displayName = "Gemma 3 1B Instruct (int4)",
        family = ModelFamily.GEMMA_3,
        parameters = 1_000_000_000L,
        quantization = "int4",
        fileName = "gemma-3-1b-it-int4.litertlm",

        // TODO(ship): replace with real URL + checksum before release
        downloadUrl = "https://huggingface.co/litert-community/gemma-3-1b-it/resolve/main/gemma-3-1b-it-int4.litertlm",
        sha256 = "E9D9AE551140313653F40DFC6B89AFB6B1B7826AB36FC0C324CC8D4D6AC443EE",

        sizeBytes = 584417280,  // ~550MB, verify before ship

        minRamMb = 2000,
        recommendedRamMb = 3000,
        supportsGpu = true,
        expectedTokensPerSecondCpu = 8,   // SD695-class floor
        expectedTokensPerSecondGpu = 25,

        maxContextTokens = 2048,
        // Gemma 3 uses its own chat template — <start_of_turn>user ... <end_of_turn>
        // The LiteRT-LM engine handles this internally via ConversationConfig;
        // we don't manually template here.
        chatTemplate = ChatTemplate.GEMMA_3
    )

    val GEMMA_4_E2B: ModelSpec = ModelSpec(
        id = "gemma-4-E2B-it-int4",
        displayName = "Gemma 4 E2B Instruct (int4)",
        family = ModelFamily.GEMMA_4,
        parameters = 2_000_000_000L,
        quantization = "int4",
        fileName = "gemma-4-E2B-it-int4.litertlm",

        // TODO(ship): replace
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it/resolve/main/gemma-4-E2B-it-int4.litertlm",
        sha256 = "PLACEHOLDER_REPLACE_BEFORE_SHIP",

        sizeBytes = 1_200L * 1024 * 1024,  // ~1.2GB

        minRamMb = 4000,
        recommendedRamMb = 6000,
        supportsGpu = true,
        expectedTokensPerSecondCpu = 4,
        expectedTokensPerSecondGpu = 15,

        maxContextTokens = 4096,
        chatTemplate = ChatTemplate.GEMMA_3  // Gemma 4 uses same template as 3
    )

    /** All models the app knows about, in preference order within each tier. */
    val ALL: List<ModelSpec> = listOf(GEMMA_3_1B, GEMMA_4_E2B)

    /** The guaranteed-available model. Must run on every SD695+ device. */
    val DEFAULT: ModelSpec = GEMMA_3_1B

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
}

/**
 * Specification of a single model.
 *
 * Immutable value class — serialized into persistence when recording which
 * model produced a plan.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val parameters: Long,
    val quantization: String,

    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,

    val minRamMb: Int,
    val recommendedRamMb: Int,
    val supportsGpu: Boolean,

    /** Rough TTFT estimates — used for TTFT budget checks before accepting a plan request. */
    val expectedTokensPerSecondCpu: Int,
    val expectedTokensPerSecondGpu: Int,

    val maxContextTokens: Int,
    val chatTemplate: ChatTemplate
)

enum class ModelFamily { GEMMA_3, GEMMA_4, QWEN_2_5, OTHER }

enum class ChatTemplate {
    /** Gemma 3 / Gemma 4 use <start_of_turn>/<end_of_turn> markers. */
    GEMMA_3,

    /** Qwen 2.5 uses ChatML-style <|im_start|>/<|im_end|>. */
    QWEN_CHATML,

    /** Raw — no template applied (rare, for base models). */
    RAW
}