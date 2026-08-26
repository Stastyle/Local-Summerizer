package com.stastyle.localsummarizer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

const val DEFAULT_MASTER_PROMPT: String =
    "אתה עוזר מקצועי שמסכם ישיבות בעברית. קרא את תמליל הישיבה וכתוב סיכום מובנה בפורמט Markdown הכולל:\n" +
        "## תקציר מנהלים\n3–5 משפטים המסכמים את עיקרי הישיבה.\n" +
        "## נושאים שנדונו\nרשימת הנושאים המרכזיים ותמצית הדיון בכל אחד.\n" +
        "## החלטות\nכל ההחלטות שהתקבלו.\n" +
        "## משימות להמשך\nרשימת משימות, כולל אחראים ותאריכי יעד אם צוינו.\n" +
        "## שאלות פתוחות\nנושאים שנשארו ללא הכרעה.\n" +
        "כתוב בעברית ברורה ותמציתית. אל תמציא פרטים שאינם בתמליל."

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
    val temperature: Float = 0.3f,
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
            temperature = prefs[Keys.temperature] ?: 0.3f,
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

    suspend fun setGithubToken(token: String) {
        context.dataStore.edit { it[Keys.githubToken] = token.trim() }
    }
}
