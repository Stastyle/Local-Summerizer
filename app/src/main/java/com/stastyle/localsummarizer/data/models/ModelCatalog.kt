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
            id = "ivrit-large-v3-turbo",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai large-v3-turbo (עברית)",
            fileName = "ivrit-whisper-large-v3-turbo.bin",
            url = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/main/" +
                "ggml-model.bin",
            approxBytes = 1620 * MB,
            note = "מכוונן לעברית והמהיר מבין השניים — התחילו כאן. שפת התמלול חייבת " +
                "להיות עברית, לא \"זיהוי אוטומטי\"",
        ),
        CatalogModel(
            id = "ivrit-large-v3",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai large-v3 (עברית, מדויק ואיטי)",
            fileName = "ivrit-whisper-large-v3.bin",
            url = "https://huggingface.co/ivrit-ai/whisper-large-v3-ggml/resolve/main/" +
                "ggml-model.bin",
            approxBytes = 3095 * MB,
            note = "לא מומלץ לטלפון: 32 שכבות מפענח מול 4 ב-turbo, כלומר איטי פי ~8. שעת הקלטה עלולה לקחת יום. בחרו בזה רק אם turbo לא מספיק מדויק ואתם מוכנים לחכות",
        ),
        CatalogModel(
            id = "whisper-large-v3-turbo",
            kind = ModelKind.WHISPER,
            displayName = "Whisper large-v3-turbo (רב-לשוני)",
            fileName = "ggml-large-v3-turbo.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin",
            approxBytes = 1624 * MB,
            note = "המודל הרשמי. עדיף על ivrit רק בישיבות שמערבבות עברית ואנגלית",
        ),
        CatalogModel(
            id = "whisper-small",
            kind = ModelKind.WHISPER,
            displayName = "Whisper small (רב-לשוני)",
            fileName = "ggml-small.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            approxBytes = 488 * MB,
            note = "מהיר, אבל בעברית הוא טועה הרבה. לא מומלץ לישיבות אמיתיות",
        ),
        CatalogModel(
            id = "whisper-base",
            kind = ModelKind.WHISPER,
            displayName = "Whisper base (רב-לשוני)",
            fileName = "ggml-base.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            approxBytes = 148 * MB,
            note = "לבדיקה שהצינור עובד בלבד — התמליל שיפיק בעברית לא שמיש",
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
            note = "מהיר, אבל בעברית הוא נוטה לגלוש לאנגלית ולסינית באמצע משפט",
        ),
        CatalogModel(
            id = "qwen2.5-7b-instruct-q4km",
            kind = ModelKind.LLM,
            displayName = "Qwen2.5 7B Instruct (Q4_K_M)",
            fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/" +
                "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            approxBytes = 4680 * MB,
            note = "עברית טובה בהרבה מ-3B, איטי יותר. דורש 12GB RAM",
        ),
    )

    fun forKind(kind: ModelKind): List<CatalogModel> =
        if (kind == ModelKind.WHISPER) whisper else llm

    fun byId(id: String): CatalogModel? =
        (whisper + llm).firstOrNull { it.id == id }
}
