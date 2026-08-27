package com.stastyle.localsummarizer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

const val DEFAULT_MASTER_PROMPT: String =
    "שפת הפלט: עברית בלבד.\n\n" +
        "אתה כותב פרוטוקול של ישיבה מתוך תמליל. המטרה: מי שלא נכח בישיבה יבין " +
        "מהפרוטוקול מה נאמר והוחלט, בלי לקרוא את התמליל.\n\n" +
        "כתוב עם הכותרות הבאות, בדיוק בסדר הזה:\n" +
        "## תקציר מנהלים\n" +
        "3–5 משפטים: על מה הישיבה, מה סוכם, ומה נשאר פתוח.\n" +
        "## נושאים שנדונו\n" +
        "לכל נושא כתוב מה בדיוק נאמר עליו — שמות, תפקידים, מספרים, וציטוטים " +
        "קצרים מהתמליל. אל תסתפק בשם הנושא; שורה שאומרת רק במה עסקו היא כישלון.\n" +
        "## החלטות\n" +
        "כל דבר שנקבע או שסוכם לעשות, גם אם נאמר כבדרך אגב באמצע משפט. " +
        "נסח כל אחד כמשפט שלם ועומד בפני עצמו.\n" +
        "## משימות להמשך\n" +
        "מה צריך לקרות, מי אחראי ומתי — ככל שצוין בתמליל.\n" +
        "## שאלות פתוחות\n" +
        "מה נשאל ולא נענה, ומה הושאר להכרעה.\n\n" +
        "התמליל הופק בזיהוי דיבור אוטומטי ועלולות להיות בו מילים משובשות. " +
        "הסתמך על ההקשר, אל תנחש שמות שאינך בטוח בהם, וציין במפורש כשמשהו לא ברור.\n" +
        "הסתמך רק על מה שנאמר בתמליל ואל תמציא. סעיף שאין לו תוכן — השמט אותו " +
        "לגמרי, אל תכתוב בו שאין.\n\n" +
        "כלל מחייב: כל תו בתשובה חייב להיות בעברית, בספרות או בסימני פיסוק."

/**
 * Whisper conditions its decoder on this text. Even with no domain terms in
 * it, a Hebrew sentence with full punctuation tells the model what language
 * and what writing style to produce — which matters most exactly where the
 * model is weakest. Users append their own recurring names and jargon.
 */
const val DEFAULT_TRANSCRIPTION_PROMPT: String =
    "תמליל ישיבה בעברית. להלן דברי המשתתפים, בעברית תקנית ועם פיסוק מלא."

data class AppSettings(
    val whisperModelUri: String = "",
    val whisperModelName: String = "",
    val llamaModelUri: String = "",
    val llamaModelName: String = "",
    val masterPrompt: String = DEFAULT_MASTER_PROMPT,
    val language: String = "he",
    val threads: Int = 0,
    val contextSize: Int = 8192,
    val maxTokens: Int = 1500,
    val temperature: Float = 0.2f,
    /** Above 1 selects beam search; 1 keeps the cheaper greedy decoding. */
    val beamSize: Int = 5,
    /** Condition each 30s window on the text before it. */
    val transcriptionContext: Boolean = true,
    /** Glossary carried into every decode window. */
    val transcriptionPrompt: String = DEFAULT_TRANSCRIPTION_PROMPT,
    /** Make non-Hebrew tokens unreachable during summarization. */
    val hebrewOnlyOutput: Boolean = true,
    /** Optional GitHub token, only used to reach the private release. */
    val githubToken: String = "",
) {
    val hasWhisperModel: Boolean get() = whisperModelUri.isNotBlank()
    val hasLlamaModel: Boolean get() = llamaModelUri.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val whisperModelUri = stringPreferencesKey("whisper_model_uri")
        val whisperModelName = stringPreferencesKey("whisper_model_name")
        val llamaModelUri = stringPreferencesKey("llama_model_uri")
        val llamaModelName = stringPreferencesKey("llama_model_name")
        val masterPrompt = stringPreferencesKey("master_prompt")
        val language = stringPreferencesKey("language")
        val threads = intPreferencesKey("threads")
        val contextSize = intPreferencesKey("context_size")
        val maxTokens = intPreferencesKey("max_tokens")
        val temperature = floatPreferencesKey("temperature")
        val beamSize = intPreferencesKey("beam_size")
        val transcriptionContext = booleanPreferencesKey("transcription_context")
        val transcriptionPrompt = stringPreferencesKey("transcription_prompt")
        val hebrewOnlyOutput = booleanPreferencesKey("hebrew_only_output")
        val githubToken = stringPreferencesKey("github_token")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            whisperModelUri = prefs[Keys.whisperModelUri] ?: "",
            whisperModelName = prefs[Keys.whisperModelName] ?: "",
            llamaModelUri = prefs[Keys.llamaModelUri] ?: "",
            llamaModelName = prefs[Keys.llamaModelName] ?: "",
            masterPrompt = prefs[Keys.masterPrompt] ?: DEFAULT_MASTER_PROMPT,
            language = prefs[Keys.language] ?: "he",
            threads = prefs[Keys.threads] ?: 0,
            contextSize = prefs[Keys.contextSize] ?: 8192,
            maxTokens = prefs[Keys.maxTokens] ?: 1500,
            temperature = prefs[Keys.temperature] ?: 0.2f,
            beamSize = prefs[Keys.beamSize] ?: 5,
            transcriptionContext = prefs[Keys.transcriptionContext] ?: true,
            transcriptionPrompt = prefs[Keys.transcriptionPrompt]
                ?: DEFAULT_TRANSCRIPTION_PROMPT,
            hebrewOnlyOutput = prefs[Keys.hebrewOnlyOutput] ?: true,
            githubToken = prefs[Keys.githubToken] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWhisperModel(uri: String, name: String) {
        context.dataStore.edit {
            it[Keys.whisperModelUri] = uri
            it[Keys.whisperModelName] = name
        }
    }

    suspend fun setLlamaModel(uri: String, name: String) {
        context.dataStore.edit {
            it[Keys.llamaModelUri] = uri
            it[Keys.llamaModelName] = name
        }
    }

    suspend fun setMasterPrompt(prompt: String) {
        context.dataStore.edit { it[Keys.masterPrompt] = prompt }
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { it[Keys.language] = language }
    }

    suspend fun setThreads(threads: Int) {
        context.dataStore.edit { it[Keys.threads] = threads }
    }

    suspend fun setContextSize(size: Int) {
        context.dataStore.edit { it[Keys.contextSize] = size }
    }

    suspend fun setMaxTokens(tokens: Int) {
        context.dataStore.edit { it[Keys.maxTokens] = tokens }
    }

    suspend fun setTemperature(temperature: Float) {
        context.dataStore.edit { it[Keys.temperature] = temperature }
    }

    suspend fun setBeamSize(size: Int) {
        context.dataStore.edit { it[Keys.beamSize] = size }
    }

    suspend fun setTranscriptionContext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.transcriptionContext] = enabled }
    }

    suspend fun setTranscriptionPrompt(prompt: String) {
        context.dataStore.edit { it[Keys.transcriptionPrompt] = prompt }
    }

    suspend fun setHebrewOnlyOutput(enabled: Boolean) {
        context.dataStore.edit { it[Keys.hebrewOnlyOutput] = enabled }
    }

    suspend fun setGithubToken(token: String) {
        context.dataStore.edit { it[Keys.githubToken] = token.trim() }
    }
}
