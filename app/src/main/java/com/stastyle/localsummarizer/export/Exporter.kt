package com.stastyle.localsummarizer.export

import android.content.Context
import android.net.Uri
import com.stastyle.localsummarizer.data.history.MeetingRecord
import java.text.DateFormat
import java.util.Date

object Exporter {

    fun buildMarkdown(record: MeetingRecord): String = buildString {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(record.createdAtEpochMs))
        append("# ").append(record.audioFileName).append('\n')
        append('*').append(date).append("*\n\n")
        if (record.summary.isNotBlank()) {
            append("## סיכום\n\n")
            append(record.summary.trim()).append("\n\n")
        }
        if (record.transcript.isNotBlank()) {
            append("## תמליל מלא\n\n")
            append(record.transcript.trim()).append('\n')
        }
    }

    fun buildPlainText(record: MeetingRecord): String = buildString {
        if (record.summary.isNotBlank()) {
            append(record.summary.trim()).append("\n\n")
            append("----------\n\n")
        }
        append(record.transcript.trim()).append('\n')
    }

    fun writeTo(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: throw IllegalStateException("Cannot open destination file")
    }

    fun suggestFileName(record: MeetingRecord, extension: String): String {
        val base = record.audioFileName.substringBeforeLast('.').ifBlank { "meeting" }
        return "$base-summary.$extension"
    }
}
