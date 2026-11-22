// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import app.solstone.observer.formfactor.phone.refusalText
import app.solstone.platform.persistence.room.ImportHandoffStatus
import java.math.BigDecimal

/** The journal's import host contract (v2), as the app vendors it. */
internal data class ImportHostContract(
    val json: String,
    val capability: String,
    val pick: String,
    val reply: String,
    val unsupported: String,
    val contextMaxBytes: Int,
)

/** The vendored contract, or null when it isn't the version this app speaks. */
internal fun parseImportHostContract(bytes: ByteArray): ImportHostContract? {
    val text = bytes.toString(Charsets.UTF_8)
    val root = runCatching { parseJson(text) }.getOrNull() as? Map<*, *> ?: return null
    val version = root["version"] as? BigDecimal ?: return null
    if (version.compareTo(BigDecimal(2)) != 0) return null
    fun name(key: String) = (root[key] as? String)?.takeIf { it.isNotEmpty() }
    return ImportHostContract(
        json = text.trim(),
        capability = name("capability") ?: return null,
        pick = name("pick") ?: return null,
        reply = name("reply") ?: return null,
        unsupported = name("unsupported") ?: return null,
        contextMaxBytes = (root["contextMaxBytes"] as? BigDecimal)?.toInt() ?: return null,
    )
}

/** The name of the object the app injects for the import host script to talk through. */
internal const val JOURNAL_IMPORT_BRIDGE = "solstoneJournalImportBridge"

/** The document-start script for [origin], built from the vendored script and contracts. */
internal fun journalImportHostScript(
    template: String,
    contract: ImportHostContract,
    origin: String,
    authContract: AuthHostContract? = null,
): String =
    template.replace("__CONTRACT__", contract.json)
        .replace("__AUTH_CONTRACT__", authContract?.json ?: "null")
        .replace("__ORIGIN__", toJson(origin))
        .replace("__BRIDGE__", toJson(JOURNAL_IMPORT_BRIDGE))

/**
 * The app side of the journal import page's handoff. The page asks for a pick; the owner chooses
 * files in the system picker; each is copied into the same durable store and upload queue that
 * Share and the import tile use, and only then does the page hear `queued`. The page never
 * receives a path, the bytes or the pairing credential: only queue ids, names and states.
 *
 * Messages are taken only from the main frame on the journal's exact [origin]. One pick runs at a
 * time; a competing one is told `busy`. A pick whose document went away while the picker was open
 * is `cancelled` and nothing is copied.
 */
internal class JournalImportHost<P>(
    private val origin: String,
    private val contract: ImportHostContract,
    /** Opens the system picker; the callback gets the choice, or null or empty when dismissed. */
    private val openPicker: (multiple: Boolean, onPicked: (List<P>?) -> Unit) -> Unit,
    /** Describes and opens one picked item at once, while its read grant holds. */
    private val itemFor: (P) -> IntakeItem.Stream,
    private val submit: (List<IntakeItem>, (IntakeOutcome) -> Unit) -> Unit,
    private val status: (String) -> ImportHandoffStatus,
    /** Runs store reads off the main thread. */
    private val background: (() -> Unit) -> Unit,
    internal val authHost: JournalAuthHost? = null,
) {
    @Volatile private var busy = false
    @Volatile private var document = 0L

    /** A new main-frame document started; a pick or OAuth session open for the old one is cancelled. */
    fun documentStarted() {
        document++
        authHost?.tearDown()
    }

    /** Tears down any in-flight OAuth session when the WebView is released or crashes. */
    fun tearDown() {
        authHost?.tearDown()
    }

    /**
     * Handle one message. [reply] receives the answer text for the page; it may be called on
     * another thread. A message from a subframe or another origin gets no answer at all.
     */
    fun onMessage(text: String?, sourceOrigin: String, isMainFrame: Boolean, reply: (String) -> Unit) {
        if (!isMainFrame || sourceOrigin.trimEnd('/') != origin) return
        val packet = runCatching { parseJson(text ?: return) }.getOrNull() as? Map<*, *> ?: return
        val seq = packet["seq"] as? BigDecimal ?: return
        val message = packet["message"] as? Map<*, *> ?: return
        val answer = { body: Map<String, Any?> -> reply(toJson(linkedMapOf("seq" to seq, "body" to body))) }
        val type = message["type"]
        when {
            type == "host.status" -> {
                val item = message["item"] as? String
                if (item == null) {
                    answer(mapOf("state" to "gone"))
                    return
                }
                background { answer(statusBody(status(item))) }
            }
            type == contract.pick -> pick(message, packet["context"], answer)
            authHost != null && authHost.handles(type) -> authHost.handle(message, answer)
            else -> answer(mapOf("type" to contract.unsupported, "id" to message["id"]))
        }
    }

    private fun pick(message: Map<*, *>, contextText: Any?, answer: (Map<String, Any?>) -> Unit) {
        val id = message["id"] as? String
        if (id == null || id.isEmpty() || id.length > 128) {
            answer(mapOf("type" to contract.unsupported, "id" to id))
            return
        }
        fun outcome(value: String, extra: Map<String, Any?> = emptyMap()) =
            answer(linkedMapOf<String, Any?>("type" to contract.reply, "id" to id, "outcome" to value) + extra)
        if (busy) {
            outcome("busy")
            return
        }
        val area = (message["area"] as? String)?.takeIf { validArea(it) }
        val multiple = message["multiple"] as? Boolean
        val context = (contextText as? String)?.takeIf { validContext(it) }
        if (area == null || multiple == null || message["accept"] !is List<*> || context == null) {
            outcome("refused")
            return
        }
        busy = true
        val pickedFor = document
        val finish = { value: String, extra: Map<String, Any?> ->
            busy = false
            outcome(value, extra)
        }
        try {
            openPicker(multiple) { picked ->
                if (picked.isNullOrEmpty() || pickedFor != document) {
                    finish("cancelled", emptyMap())
                    return@openPicker
                }
                val handoff = IntakeHandoff(area, context)
                val items = picked.map { itemFor(it).copy(handoff = handoff) }
                submit(items) { result ->
                    finish(
                        if (result.saved.isEmpty()) "refused" else "queued",
                        mapOf(
                            "items" to result.saved.map { mapOf("item" to it.id, "name" to it.label) },
                            "refused" to result.refused.map { mapOf("name" to it.label, "reason" to refusalText(it.reason.toLine())) },
                        ),
                    )
                }
            }
        } catch (_: RuntimeException) {
            finish("refused", emptyMap())
        }
    }

    /** A card name the journal sends: short, and nothing that could break a form field. */
    private fun validArea(area: String): Boolean = area.length in 1..96 && area.none { it < ' ' || it == '\u007f' }

    /** The owner's choices: one JSON object within the journal's limit, sent on as it came. */
    private fun validContext(text: String): Boolean =
        text.toByteArray(Charsets.UTF_8).size <= contract.contextMaxBytes &&
            text.none { it < ' ' } &&
            runCatching { parseJson(text) }.getOrNull() is Map<*, *>

    private fun statusBody(status: ImportHandoffStatus): Map<String, Any?> = buildMap {
        put("state", status.state)
        status.importId?.let { put("import_id", it) }
        status.reason?.let { put("reason", it) }
    }
}
