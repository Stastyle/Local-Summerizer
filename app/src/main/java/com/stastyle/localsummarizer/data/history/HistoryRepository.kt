package com.stastyle.localsummarizer.data.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class MeetingRecord(
    val id: String = UUID.randomUUID().toString(),
    val createdAtEpochMs: Long,
    val audioFileName: String,
    val transcript: String,
    val summary: String,
    val processingTimeMs: Long = 0,
    val whisperModelName: String = "",
    val llamaModelName: String = "",
)

class HistoryRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val dir: File get() = File(context.filesDir, "history").apply { mkdirs() }

    private val _records = MutableStateFlow<List<MeetingRecord>>(emptyList())
    val records: StateFlow<List<MeetingRecord>> = _records

    suspend fun reload() = withContext(Dispatchers.IO) {
        val list = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<MeetingRecord>(file.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.createdAtEpochMs }
            ?: emptyList()
        _records.value = list
    }

    suspend fun save(record: MeetingRecord) = withContext(Dispatchers.IO) {
        File(dir, "${record.id}.json").writeText(json.encodeToString(MeetingRecord.serializer(), record))
        reload()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        reload()
    }
}
