// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File
import java.io.FileOutputStream

/**
 * Durable atomic JSON persistence for watched folders.
 * Contains ZERO journal identity (no instance ID, URL, or certificate fingerprint).
 */
object FolderLedgerStore {
    private const val LEDGER_VERSION = 1

    fun load(file: File): MutableList<FolderRecord> {
        if (!file.isFile) return ArrayList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return ArrayList()
        return parseLedger(text)
    }

    fun save(file: File, folders: List<FolderRecord>): Boolean = runCatching {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${file.name}.tmp")
        val bytes = serializeLedger(folders).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(false, false)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) {
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("failed to atomically replace ${file.name}")
            }
        }
        true
    }.getOrDefault(false)

    internal fun serializeLedger(folders: List<FolderRecord>): String {
        val root = linkedMapOf<String, Any?>(
            "v" to LEDGER_VERSION,
            "folders" to folders.map(::serializeFolder),
        )
        return toJson(root)
    }

    internal fun parseLedger(text: String): MutableList<FolderRecord> {
        val root = runCatching { parseJson(text) as? Map<*, *> }.getOrNull() ?: return ArrayList()
        val rawFolders = root["folders"] as? List<*> ?: return ArrayList()
        val out = ArrayList<FolderRecord>(rawFolders.size)
        for (entry in rawFolders) {
            val map = entry as? Map<*, *> ?: continue
            parseFolder(map)?.let { out.add(it) }
        }
        return out
    }

    private fun serializeFolder(f: FolderRecord): Map<String, Any?> = linkedMapOf(
        "id" to f.id,
        "tree_uri" to f.treeUri,
        "name" to f.name,
        "label" to f.label,
        "canonical_path" to f.canonicalPath,
        "mode" to f.mode.wire,
        "returning" to f.returning,
        "kinds" to linkedMapOf(
            "notes" to f.kinds.notes,
            "documents" to f.kinds.documents,
            "transcripts" to f.kinds.transcripts,
            "screenshots" to f.kinds.screenshots,
            "photos" to f.kinds.photos,
            "other" to f.kinds.other,
        ),
        "facet" to f.facet?.let {
            linkedMapOf("name" to it.name, "title" to it.title, "color" to it.color)
        },
        "overlap" to f.overlap?.let(::serializeOverlap),
        "attention" to f.attention?.wire,
        "span_from_ms" to f.spanFromMs,
        "selected_span" to f.selectedSpan.wire,
        "last_checked_ms" to f.lastCheckedMs,
        "last_taken_in_ms" to f.lastTakenInMs,
        "items_taken_in" to f.itemsTakenIn,
        "backfill_done" to f.backfillDone,
        "backfill_total" to f.backfillTotal,
        "cant_read" to f.cantRead,
        "fallback_to_details" to f.fallbackToDetails,
        "files" to f.files.values.map(::serializeTrackedFile),
        "pending" to f.pending.map(::serializePendingEvent),
    )

    private fun serializeOverlap(overlap: OverlapTarget): Map<String, Any?> = when (overlap) {
        is OverlapTarget.Obsidian -> linkedMapOf("kind" to "obsidian", "name" to overlap.name)
        is OverlapTarget.Screenshots -> linkedMapOf("kind" to "screenshots", "name" to overlap.name)
        is OverlapTarget.Folder -> linkedMapOf("kind" to "folder", "id" to overlap.id, "name" to overlap.name)
    }

    private fun serializeTrackedFile(t: TrackedFile): Map<String, Any?> = linkedMapOf(
        "rel_path" to t.relPath,
        "document_id" to t.documentId,
        "size_bytes" to t.sizeBytes,
        "modified_ms" to t.modifiedMs,
        "content_hash" to t.contentHash,
        "kind" to t.kind.wireName,
        "delivery" to t.delivery.wire,
        "delivered_to_journal" to t.deliveredToJournal,
        "excluded_before_span" to t.excludedBeforeSpan,
        "last_sent_hash" to t.lastSentHash,
        "last_note_text" to t.lastNoteText,
        "pending_revision_save_ms" to t.pendingRevisionSaveMs,
        "pending_revision_hash" to t.pendingRevisionHash,
    )

    private fun serializePendingEvent(ev: PendingEvent): Map<String, Any?> = linkedMapOf(
        "id" to ev.id,
        "rel_path" to ev.relPath,
        "document_id" to ev.documentId,
        "previous_path" to ev.previousPath,
        "verb" to ev.verb.wire,
        "kind" to ev.kind.wireName,
        "delivery" to ev.delivery.wire,
        "event_ms" to ev.eventMs,
        "observed_ms" to ev.observedMs,
        "offset_s" to ev.offsetSeconds,
        "day" to ev.window.day,
        "segment" to ev.window.segment,
        "window_end_ms" to ev.windowEndMs,
        "metadata" to linkedMapOf(
            "size_bytes" to ev.metadata.sizeBytes,
            "modified_ms" to ev.metadata.modifiedMs,
            "created_ms" to ev.metadata.createdMs,
            "extension" to ev.metadata.extension,
            "mime_type" to ev.metadata.mimeType,
            "origin_url" to ev.metadata.originUrl,
            "referrer_url" to ev.metadata.referrerUrl,
            "downloaded_by" to ev.metadata.downloadedBy,
            "source_app" to ev.metadata.sourceApp,
        ),
        "content_hash" to ev.contentHash,
        "note_payload" to ev.notePayload,
        "is_revision" to ev.isRevision,
        "backfill" to ev.backfill,
        "retry_at_ms" to ev.retryAtMs,
        "tries" to ev.tries,
    )

    private fun parseFolder(map: Map<*, *>): FolderRecord? {
        val id = map["id"] as? String ?: return null
        val treeUri = map["tree_uri"] as? String ?: return null
        val name = map["name"] as? String ?: return null
        val label = map["label"] as? String ?: name
        val kindsMap = map["kinds"] as? Map<*, *>
        val kinds = FolderKinds(
            notes = kindsMap?.get("notes") as? Boolean ?: true,
            documents = kindsMap?.get("documents") as? Boolean ?: true,
            transcripts = kindsMap?.get("transcripts") as? Boolean ?: true,
            screenshots = kindsMap?.get("screenshots") as? Boolean ?: true,
            photos = kindsMap?.get("photos") as? Boolean ?: true,
            other = kindsMap?.get("other") as? Boolean ?: true,
        )
        val facetMap = map["facet"] as? Map<*, *>
        val facet = facetMap?.let { fm ->
            val fname = fm["name"] as? String ?: return@let null
            FacetOption(
                name = fname,
                title = (fm["title"] as? String) ?: fname,
                color = fm["color"] as? String,
            )
        }
        val overlapMap = map["overlap"] as? Map<*, *>
        val overlap = overlapMap?.let { om ->
            val oname = om["name"] as? String ?: return@let null
            when (om["kind"] as? String) {
                "obsidian" -> OverlapTarget.Obsidian(oname)
                "screenshots" -> OverlapTarget.Screenshots(oname)
                "folder" -> OverlapTarget.Folder(om["id"] as? String ?: "", oname)
                else -> null
            }
        }
        val filesMap = LinkedHashMap<String, TrackedFile>()
        (map["files"] as? List<*>)?.forEach { entry ->
            val tf = parseTrackedFile(entry as? Map<*, *> ?: return@forEach) ?: return@forEach
            filesMap[tf.relPath] = tf
        }
        val pendingList = ArrayList<PendingEvent>()
        (map["pending"] as? List<*>)?.forEach { entry ->
            val ev = parsePendingEvent(entry as? Map<*, *> ?: return@forEach) ?: return@forEach
            pendingList.add(ev)
        }
        return FolderRecord(
            id = id,
            treeUri = treeUri,
            name = name,
            label = label,
            canonicalPath = map["canonical_path"] as? String,
            mode = FolderMode.fromWire(map["mode"] as? String),
            returning = map["returning"] as? Boolean ?: false,
            kinds = kinds,
            facet = facet,
            overlap = overlap,
            attention = FolderAttention.fromWire(map["attention"] as? String),
            spanFromMs = (map["span_from_ms"] as? Number)?.toLong(),
            selectedSpan = FolderSpan.fromWire(map["selected_span"] as? String),
            lastCheckedMs = (map["last_checked_ms"] as? Number)?.toLong(),
            lastTakenInMs = (map["last_taken_in_ms"] as? Number)?.toLong(),
            itemsTakenIn = (map["items_taken_in"] as? Number)?.toLong() ?: 0L,
            backfillDone = (map["backfill_done"] as? Number)?.toLong() ?: 0L,
            backfillTotal = (map["backfill_total"] as? Number)?.toLong() ?: 0L,
            cantRead = (map["cant_read"] as? Number)?.toLong() ?: 0L,
            fallbackToDetails = (map["fallback_to_details"] as? Number)?.toLong() ?: 0L,
            files = filesMap,
            pending = pendingList,
        )
    }

    private fun parseTrackedFile(map: Map<*, *>): TrackedFile? {
        val relPath = map["rel_path"] as? String ?: return null
        return TrackedFile(
            relPath = relPath,
            documentId = map["document_id"] as? String,
            sizeBytes = (map["size_bytes"] as? Number)?.toLong() ?: 0L,
            modifiedMs = (map["modified_ms"] as? Number)?.toLong() ?: 0L,
            contentHash = map["content_hash"] as? String,
            kind = FileKind.fromWire(map["kind"] as? String),
            delivery = DeliveryMode.fromWire(map["delivery"] as? String),
            deliveredToJournal = map["delivered_to_journal"] as? Boolean ?: false,
            excludedBeforeSpan = map["excluded_before_span"] as? Boolean ?: false,
            lastSentHash = map["last_sent_hash"] as? String,
            lastNoteText = map["last_note_text"] as? String,
            pendingRevisionSaveMs = (map["pending_revision_save_ms"] as? Number)?.toLong(),
            pendingRevisionHash = map["pending_revision_hash"] as? String,
        )
    }

    private fun parsePendingEvent(map: Map<*, *>): PendingEvent? {
        val id = map["id"] as? String ?: return null
        val relPath = map["rel_path"] as? String ?: return null
        val day = map["day"] as? String ?: return null
        val segment = map["segment"] as? String ?: return null
        val metaMap = map["metadata"] as? Map<*, *> ?: return null
        val metadata = PlatformMetadata(
            sizeBytes = (metaMap["size_bytes"] as? Number)?.toLong() ?: 0L,
            modifiedMs = (metaMap["modified_ms"] as? Number)?.toLong() ?: 0L,
            createdMs = (metaMap["created_ms"] as? Number)?.toLong(),
            extension = metaMap["extension"] as? String,
            mimeType = metaMap["mime_type"] as? String,
            originUrl = metaMap["origin_url"] as? String,
            referrerUrl = metaMap["referrer_url"] as? String,
            downloadedBy = metaMap["downloaded_by"] as? String,
            sourceApp = metaMap["source_app"] as? String,
        )
        return PendingEvent(
            id = id,
            relPath = relPath,
            documentId = map["document_id"] as? String,
            previousPath = map["previous_path"] as? String,
            verb = ActivityVerb.fromWire(map["verb"] as? String),
            kind = FileKind.fromWire(map["kind"] as? String),
            delivery = DeliveryMode.fromWire(map["delivery"] as? String),
            eventMs = (map["event_ms"] as? Number)?.toLong() ?: 0L,
            observedMs = (map["observed_ms"] as? Number)?.toLong() ?: 0L,
            offsetSeconds = (map["offset_s"] as? Number)?.toInt() ?: 0,
            window = FolderWindow(day, segment),
            windowEndMs = (map["window_end_ms"] as? Number)?.toLong() ?: 0L,
            metadata = metadata,
            contentHash = map["content_hash"] as? String,
            notePayload = map["note_payload"] as? String,
            isRevision = map["is_revision"] as? Boolean ?: false,
            backfill = map["backfill"] as? Boolean ?: false,
            retryAtMs = (map["retry_at_ms"] as? Number)?.toLong(),
            tries = (map["tries"] as? Number)?.toInt() ?: 0,
        )
    }
}
