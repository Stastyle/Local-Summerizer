package com.stastyle.localsummarizer.data.models

enum class ModelKind { WHISPER, LLM }

/**
 * A model the app can fetch directly. Sizes are approximate and only drive the
 * free-space check and the UI label; the real size comes from the server.
 */
data class CatalogModel(
    val id: String,
    val kind: ModelKind,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    /** Short Hebrew note shown under the dropdown. */
    val note: String,
)

private const val MB = 1024L * 1024L

object ModelCatalog {

    val whisper: List<CatalogModel> = listOf(
        CatalogModel(
            id = "whisper-large-v3-turbo",
            kind = ModelKind.WHISPER,
            displayName = "Whisper large-v3-turbo",
            fileName = "ggml-large-v3-turbo.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin",
            approxBytes = 1624 * MB,
            note = "האיכות הטובה ביותר מבין המודלים הרשמיים, כולל בעברית",
        ),
        CatalogModel(
            id = "whisper-small",
            kind = ModelKind.WHISPER,
            displayName = "Whisper small",
            fileName = "ggml-small.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            approxBytes = 488 * MB,
            note = "פשרה בין מהירות לאיכות",
        ),
        CatalogModel(
            id = "whisper-base",
            kind = ModelKind.WHISPER,
            displayName = "Whisper base",
            fileName = "ggml-base.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            approxBytes = 148 * MB,
            note = "קטן ומהיר — טוב לבדיקה ראשונה שהכול עובד, לא לשימוש אמיתי",
        ),
    )

    val llm: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen2.5-3b-instruct-q4km",
            kind = ModelKind.LLM,
            displayName = "Qwen2.5 3B Instruct (Q4_K_M)",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/" +
                "qwen2.5-3b-instruct-q4_k_m.gguf",
            approxBytes = 2000 * MB,
            note = "מהיר, ועברית טובה לסיכומים — מומלץ להתחיל כאן",
        ),
        CatalogModel(
            id = "qwen2.5-7b-instruct-q4km",
            kind = ModelKind.LLM,
            displayName = "Qwen2.5 7B Instruct (Q4_K_M)",
            fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/" +
                "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            approxBytes = 4680 * MB,
            note = "האיכות הטובה ביותר, איטי יותר. דורש 12GB RAM",
        ),
    )

    fun forKind(kind: ModelKind): List<CatalogModel> =
        if (kind == ModelKind.WHISPER) whisper else llm

    fun byId(id: String): CatalogModel? =
        (whisper + llm).firstOrNull { it.id == id }
}
