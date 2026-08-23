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

interface SourceWishStore {
    fun loadAll(): Map<String, SourceWish>
    fun saveAll(wishes: Map<String, SourceWish>)
}

class InMemorySourceWishStore(
    initial: Map<String, SourceWish> = emptyMap(),
) : SourceWishStore {
    private val wishes = LinkedHashMap<String, SourceWish>(initial)

    override fun loadAll(): Map<String, SourceWish> = LinkedHashMap(wishes)

    override fun saveAll(wishes: Map<String, SourceWish>) {
        this.wishes.clear()
        this.wishes.putAll(wishes)
    }
}

class FileSourceWishStore(private val file: File) : SourceWishStore {
    override fun loadAll(): Map<String, SourceWish> {
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return emptyMap()
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
        return loaded
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
