// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import app.solstone.core.pl.parseJson

private val telegramTypes = setOf("personal_chat", "bot_chat", "private_group", "private_supergroup", "public_supergroup", "private_channel", "public_channel", "saved_messages")
private val roles = setOf("you", "user", "assistant", "human", "system", "chatgpt", "claude", "gemini")
private val slackTime = Regex("[0-9]{1,10}\\.[0-9]{1,6}")
private val podcastVersion = Regex("[0-9]+(?:\\.[0-9]+)+")
private val clockField = Regex("(?:[0-9]+:)?[0-5]?[0-9]:[0-5][0-9](?:[.,][0-9]{1,3})?")
private val uuidField = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

/** Keep the recursive JSON reader within the journal's maximum nesting depth. */
private fun jsonDepthAllowed(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escape = false
    for (ch in text) {
        if (quoted) {
            if (escape) escape = false else if (ch == '\\') escape = true else if (ch == '"') quoted = false
        } else when (ch) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 32) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return depth == 0 && !quoted
}

private fun jsonTranscript(text: String): Boolean {
    if (!jsonDepthAllowed(text)) return false
    val root = try { parseJson(text) } catch (_: IllegalArgumentException) { return false }
    if (root is List<*>) {
        return root.isNotEmpty() && root.all {
            val message = it as? Map<*, *> ?: return@all false
            message["type"] == "message" && message["text"] is String &&
                slackTime.matches(message["ts"] as? String ?: "") &&
                listOf("user", "bot_id", "client_msg_id", "team").any { key -> message[key] is String }
        }
    }
    val obj = root as? Map<*, *> ?: return false
    val messages = obj["messages"] as? List<*>
    if (messages != null && obj["type"] in telegramTypes && "chats" !in obj && "personal_information" !in obj) {
        return messages.isNotEmpty() && messages.all {
            val message = it as? Map<*, *> ?: return@all false
            message["type"] == "message" && message["id"] is Number &&
                (message["date"] is String || message["date_unixtime"] is String) &&
                (message["text"] is String || message["text"] is List<*>)
        }
    }
    val segments = obj["segments"] as? List<*> ?: return false
    if (segments.isEmpty()) return false
    if (obj["text"] is String && (obj["language"] is String || segments.any {
            val row = it as? Map<*, *> ?: return@any false
            row["speaker"] is String || uuidField.matches(row["id"] as? String ?: "") || (row["words"] as? List<*>)?.isNotEmpty() == true
        })) {
        return segments.all {
            val row = it as? Map<*, *> ?: return@all false
            val start = (row["start"] as? Number)?.toDouble() ?: return@all false
            val end = (row["end"] as? Number)?.toDouble() ?: return@all false
            row["text"] is String && start >= 0 && end >= start && end.isFinite()
        }
    }
    return podcastVersion.matches(obj["version"] as? String ?: "") && segments.all {
        val row = it as? Map<*, *> ?: return@all false
        val start = row["startTime"]?.toString()?.toDoubleOrNull() ?: return@all false
        val end = row["endTime"]?.toString()?.toDoubleOrNull() ?: return@all false
        row["body"] is String && start >= 0 && end >= start && end.isFinite()
    }
}

private val ircRow = Regex("""^[0-9]{2}:[0-5][0-9](?::[0-5][0-9])?\s+<[ @+%~&]?([^>\s]{1,40})>\s+\S.*$""")
private val ircEvent = Regex("""^[0-9]{2}:[0-5][0-9](?::[0-5][0-9])?\s+-!- .+$""")
private val otterHeader = Regex("""(?:[^:]{1,60}?\s{2,})?[0-9]{1,3}:[0-5][0-9](?::[0-5][0-9])?""")

private fun textExportLayout(text: String): Boolean {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(257).toList()
    val speakers = mutableSetOf<String>()
    val ircSpeakers = mutableSetOf<String>()
    var ircTurns = 0
    var ircContext = 0
    for ((idx, line) in lines.take(256).withIndex()) {
        val roleLine = line.trimStart('#').trimStart()
        val role = roleLine.substringBefore(':').trim('*').lowercase()
        val hasBody = roleLine.substringAfter(':', "").trim('*', ' ').isNotEmpty() ||
            line.startsWith('#') && lines.getOrNull(idx + 1)?.startsWith('#') == false
        if (':' in roleLine && role in roles && hasBody) speakers.add(role)
        val irc = ircRow.matchEntire(line)
        if (irc != null) {
            ircTurns++
            ircSpeakers.add(irc.groupValues[1])
        } else if (line.startsWith("--- Log ") || line.startsWith("--- Day changed ") || ircEvent.matches(line)) {
            ircContext++
        }
    }
    if (speakers.size >= 2 || (ircTurns >= 3 && ircSpeakers.size >= 2 && (ircTurns + ircContext) * 3 >= lines.size * 2)) return true
    val tail = text.trimEnd().takeLast(2048).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList().takeLast(3)
    val border = "=".repeat(62)
    if (lines.size >= 9 && lines.first() == border && lines[1].startsWith("Guild: ") &&
        lines[2].startsWith("Channel: ") && tail.size == 3 && tail.last() == border && tail.first() == border &&
        tail[1].startsWith("Exported ") && tail[1].endsWith(" message(s)") &&
        lines.drop(3).any { it.startsWith('[') && "] " in it }) return true
    if (tail.lastOrNull() != "Transcribed by https://otter.ai") return false
    var turns = 0
    var hasBody = false
    for (line in lines.takeWhile { it != "Transcribed by https://otter.ai" }) {
        if (otterHeader.matches(line)) {
            if (turns > 0 && !hasBody) return false
            turns++
            hasBody = false
        } else if (turns == 0) {
            return false
        } else {
            hasBody = true
        }
    }
    return turns >= 2 && hasBody
}

internal fun transcriptLayout(ext: String, bytes: ByteArray?): Boolean {
    if (ext == "vtt" || ext == "srt") return true
    val text = bytes?.let(::decodeStrictUtf8)?.removePrefix("\uFEFF") ?: return false
    val lines = text.lineSequence().filter { it.isNotBlank() }
    return when (ext) {
        "json" -> jsonTranscript(text)
        "tsv" -> lines.firstOrNull() == "start\tend\ttext" && lines.drop(1).any() && lines.drop(1).all {
            val fields = it.split('\t', limit = 3)
            val start = fields.getOrNull(0)?.toLongOrNull()
            val end = fields.getOrNull(1)?.toLongOrNull()
            start != null && end != null && start >= 0 && end >= start && fields.getOrNull(2)?.isNotBlank() == true
        }
        "csv" -> lines.firstOrNull()?.split(',')?.map { it.trim().trim('"').lowercase() } ==
            listOf("speaker", "start time", "end time", "sentence") && lines.drop(1).any() && lines.drop(1).all {
                val fields = it.split(',', limit = 4)
                fields.size == 4 && clockField.matches(fields[1].trim().trim('"')) &&
                    clockField.matches(fields[2].trim().trim('"')) && fields[3].isNotBlank()
            }
        "html" -> listOf("page_body chat_page", "history", "message default", "text").all {
            text.contains("class=\"$it\"") || text.contains("class='$it'")
        }
        "txt", "md", "markdown", "log" -> textExportLayout(text)
        else -> false
    }
}
