// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * What a read of the wish store found.
 *
 * 🔴 **[Absent] and [Unreadable] are NOT the same answer, and collapsing them is a data-loss bug
 * waiting on a feature.** Today a missing file and a failed read both resolve to today's default, so
 * the difference is invisible. Once absence carries meaning — *the owner has expressed no wish, so
 * this source is not set up and is not running* — an unreadable store read as [Absent] would report
 * every source as never-set-up and stop capturing, across the whole installed base, while looking
 * exactly like the feature working.
 */
sealed interface WishStoreState {
    /** The store was read. May legitimately be empty if it was written empty. */
    data class Loaded(val wishes: Map<String, SourceWish>) : WishStoreState

    /** No store yet — a genuine "no wish has ever been expressed on this install". */
    data object Absent : WishStoreState

    /** A store exists and could not be read. ⛔ Never treat this as [Absent]. */
    data object Unreadable : WishStoreState
}

interface SourceWishStore {
    fun read(): WishStoreState
    fun saveAll(wishes: Map<String, SourceWish>)
}

class InMemorySourceWishStore(
    initial: Map<String, SourceWish> = emptyMap(),
) : SourceWishStore {
    private val wishes = LinkedHashMap<String, SourceWish>(initial)

    override fun read(): WishStoreState =
        if (absent) WishStoreState.Absent else WishStoreState.Loaded(LinkedHashMap(wishes))

    private var absent: Boolean = initial.isEmpty()

    override fun saveAll(wishes: Map<String, SourceWish>) {
        this.wishes.clear()
        this.wishes.putAll(wishes)
        absent = false
    }
}

class FileSourceWishStore(private val file: File) : SourceWishStore {
    override fun read(): WishStoreState {
        // ⛔ These two returns were one line and the same value. A store that exists and will not
        // read is not a store with nothing in it.
        if (!file.exists()) return WishStoreState.Absent
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
            ?: return WishStoreState.Unreadable
        val loaded = LinkedHashMap<String, SourceWish>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val tab = trimmed.indexOf('\t')
            if (tab <= 0) return@forEach
            val id = trimmed.substring(0, tab)
            val wish = when (trimmed.substring(tab + 1)) {
                "On" -> SourceWish.On
                "Off" -> SourceWish.Off
                else -> return@forEach
            }
            if (id.isNotBlank()) loaded[id] = wish
        }
        return WishStoreState.Loaded(loaded)
    }

    override fun saveAll(wishes: Map<String, SourceWish>) {
        val body = buildString {
            wishes.forEach { (id, wish) ->
                append(id)
                append('\t')
                append(wish.name)
                append('\n')
            }
        }
        atomicWrite(file, body.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        fun atomicWrite(target: File, bytes: ByteArray) {
            val parent = target.absoluteFile.parentFile ?: error("wish-store path has no parent")
            Files.createDirectories(parent.toPath())
            var temp: Path? = Files.createTempFile(parent.toPath(), "source-wishes", ".tmp")
            try {
                Files.write(requireNotNull(temp), bytes)
                FileChannel.open(requireNotNull(temp), WRITE).use { it.force(true) }
                try {
                    Files.move(requireNotNull(temp), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(requireNotNull(temp), target.toPath(), REPLACE_EXISTING)
                }
                temp = null
            } finally {
                temp?.let { Files.deleteIfExists(it) }
            }
        }
    }
}
