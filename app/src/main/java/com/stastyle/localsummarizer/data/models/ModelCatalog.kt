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

    private const val QUANTIZED =
        "https://github.com/Stastyle/Local-Summerizer/releases/download/models"

    /**
     * Ordered by what to try first on a phone. The quantized entries are built
     * by .github/workflows/quantize-models.yml from the ivrit.ai f16 originals
     * and hosted here, because no prebuilt quantization of them exists.
     *
     * Timings are measured on one 77-second Hebrew briefing, four x86 cores,
     * beam 5 — useful for the ratios between models, not as phone figures.
     */
    val whisper: List<CatalogModel> = listOf(
        CatalogModel(
            id = "ivrit-turbo-q5",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai turbo — מכווץ (מומלץ)",
            fileName = "ivrit-whisper-large-v3-turbo-q5_0.bin",
            url = "$QUANTIZED/ivrit-whisper-large-v3-turbo-q5_0.bin",
            approxBytes = 548 * MB,
            note = "מכוונן לעברית, מכווץ ל-q5_0. פי 3 מהר מ-large-v3 ובאיכות קרובה מאוד — " +
                "ההמלצה. שפת התמלול חייבת להיות עברית, לא \"זיהוי אוטומטי\"",
        ),
        CatalogModel(
            id = "ivrit-large-v3-q5",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai large-v3 — מכווץ",
            fileName = "ivrit-whisper-large-v3-q5_0.bin",
            url = "$QUANTIZED/ivrit-whisper-large-v3-q5_0.bin",
            approxBytes = 1031 * MB,
            note = "המדויק ביותר שנמדד, אבל פי ~3 איטי מה-turbo. שווה כשהדיוק קריטי",
        ),
        CatalogModel(
            id = "ivrit-large-v3-q8",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai large-v3 — כיווץ עדין (q8_0)",
            fileName = "ivrit-whisper-large-v3-q8_0.bin",
            url = "$QUANTIZED/ivrit-whisper-large-v3-q8_0.bin",
            approxBytes = 1580 * MB,
            note = "כיווץ כמעט ללא אובדן, אך גדול ואיטי יותר מ-q5_0. רק אם q5_0 מפספס משהו",
        ),
        CatalogModel(
            id = "ivrit-large-v3-turbo",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai turbo — מקורי f16",
            fileName = "ivrit-whisper-large-v3-turbo.bin",
            url = "https://huggingface.co/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/main/" +
                "ggml-model.bin",
            approxBytes = 1620 * MB,
            note = "אותו מודל כמו הראשון ברשימה בלי כיווץ — פי 3 בנפח, ואיטי יותר",
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
            id = "ivrit-large-v3",
            kind = ModelKind.WHISPER,
            displayName = "ivrit.ai large-v3 — מקורי f16",
            fileName = "ivrit-whisper-large-v3.bin",
            url = "https://huggingface.co/ivrit-ai/whisper-large-v3-ggml/resolve/main/ggml-model.bin",
            approxBytes = 3095 * MB,
            note = "הכי איטי בהפרש גדול, בלי יתרון דיוק על הגרסה המכווצת. עדיף q5_0 למעלה",
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
