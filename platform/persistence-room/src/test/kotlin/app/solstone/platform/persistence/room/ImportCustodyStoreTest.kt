// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.queue.RetryDecision
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportCustodyStoreTest {

    private class InMemoryImportDao : ImportDao {
        val rows = ConcurrentHashMap<String, ImportItemRow>()
        var throwOnInsert: Boolean = false
        var throwOnUpdate: Boolean = false
        var throwOnDelete: Boolean = false

        override fun insert(row: ImportItemRow) {
            if (throwOnInsert) throw RuntimeException("injected insert error")
            rows[row.id] = row
        }

        override fun update(row: ImportItemRow): Int {
            if (throwOnUpdate) throw RuntimeException("injected update error")
            return if (rows.containsKey(row.id)) {
                rows[row.id] = row
                1
            } else {
                0
            }
        }

        override fun delete(id: String): Int {
            if (throwOnDelete) throw RuntimeException("injected delete error")
            return if (rows.remove(id) != null) 1 else 0
        }

        override fun byId(id: String): ImportItemRow? = rows[id]

        override fun all(): List<ImportItemRow> =
            rows.values.sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })

        var delivery: ImportDeliveryRow? = null

        override fun putDelivery(row: ImportDeliveryRow) {
            delivery = row
        }

        override fun delivery(): ImportDeliveryRow? = delivery
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("import-custody-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writePayload(root: Path, id: String, content: ByteArray = "hello-payload".toByteArray()): Long {
        val payload = root.resolve(".staging").resolve(id).resolve("payload")
        Files.write(payload, content)
        return content.size.toLong()
    }

    @Test
    fun publishFailuresEachReturnFalseAndEligibleIsEmpty() = withTempDir { root ->
        val dao = InMemoryImportDao()

        // 1. fsync throws on the payload path
        var failPayloadFsync = true
        val store1 = ImportCustodyStore(
            root = root,
            dao = dao,
            fsync = { path ->
                if (failPayloadFsync && path.fileName.toString() == "payload") {
                    throw IOException("injected payload fsync error")
                }
            },
        )
        val id1 = store1.begin("file")!!
        val size1 = writePayload(root, id1)
        assertFalse(store1.publish(id1, "name1", "text/plain", size1))
        assertTrue(store1.eligible("inst", "v1", 1000L, 10).isEmpty())
        failPayloadFsync = false

        // 2. fsync throws on the staging directory
        var failStagingFsync = true
        val store2 = ImportCustodyStore(
            root = root,
            dao = dao,
            fsync = { path ->
                if (failStagingFsync && path.parent?.fileName?.toString() == ".staging") {
                    throw IOException("injected staging dir fsync error")
                }
            },
        )
        val id2 = store2.begin("file")!!
        val size2 = writePayload(root, id2)
        assertFalse(store2.publish(id2, "name2", "text/plain", size2))
        assertTrue(store2.eligible("inst", "v1", 1000L, 10).isEmpty())
        failStagingFsync = false

        // 3. move throws
        val store3 = ImportCustodyStore(
            root = root,
            dao = dao,
            move = { _, _ -> throw IOException("injected move error") },
        )
        val id3 = store3.begin("file")!!
        val size3 = writePayload(root, id3)
        assertFalse(store3.publish(id3, "name3", "text/plain", size3))
        assertTrue(store3.eligible("inst", "v1", 1000L, 10).isEmpty())

        // 4. fsync throws on a destination path after the move
        var failDestFsync = true
        val store4 = ImportCustodyStore(
            root = root,
            dao = dao,
            fsync = { path ->
                if (failDestFsync && path.parent == root) {
                    throw IOException("injected destination fsync error")
                }
            },
        )
        val id4 = store4.begin("file")!!
        val size4 = writePayload(root, id4)
        assertFalse(store4.publish(id4, "name4", "text/plain", size4))
        assertTrue(store4.eligible("inst", "v1", 1000L, 10).isEmpty())
        failDestFsync = false

        // 5. insert throws
        dao.throwOnInsert = true
        val store5 = ImportCustodyStore(root = root, dao = dao)
        val id5 = store5.begin("file")!!
        val size5 = writePayload(root, id5)
        assertFalse(store5.publish(id5, "name5", "text/plain", size5))
        assertTrue(store5.eligible("inst", "v1", 1000L, 10).isEmpty())
        dao.throwOnInsert = false
    }

    @Test
    fun publishEdgeCasesRejectAndLeaveNoRow() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        // Size mismatch leaves staging dir and inserts no row
        val idSizeMismatch = store.begin("text")!!
        writePayload(root, idSizeMismatch, "correct-bytes".toByteArray())
        assertFalse(store.publish(idSizeMismatch, "name", "text/plain", 9999L))
        assertTrue(Files.exists(root.resolve(".staging").resolve(idSizeMismatch).resolve("payload")))
        assertNull(dao.byId(idSizeMismatch))

        // Symlink payload returns false with no row
        val idSymlink = store.begin("file")!!
        val stagingDir = root.resolve(".staging").resolve(idSymlink)
        val outsideFile = root.resolve("outside-target.txt")
        Files.write(outsideFile, "outside".toByteArray())
        Files.createSymbolicLink(stagingDir.resolve("payload"), outsideFile)
        assertFalse(store.publish(idSymlink, "name", "text/plain", Files.size(outsideFile)))
        assertNull(dao.byId(idSymlink))

        // Unsafe id in publish
        assertFalse(store.publish("../x", "name", "text/plain", 10L))
        assertFalse(store.publish("a/b", "name", "text/plain", 10L))
        assertFalse(store.publish("a\\b", "name", "text/plain", 10L))
        assertFalse(store.publish(".hidden", "name", "text/plain", 10L))
        assertFalse(Files.exists(root.resolve("../x")))
        assertFalse(Files.exists(root.resolve("a/b")))
    }

    @Test
    fun twoStoreObjectsOnOneRootCoordinateThroughLock() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val inHook = CountDownLatch(1)
        val releaseHook = CountDownLatch(1)
        val recoverDone = AtomicBoolean(false)

        val orphanDir = root.resolve("orphan-folder")
        Files.createDirectories(orphanDir)

        val storeA = ImportCustodyStore(
            root = root,
            dao = dao,
            afterMove = {
                inHook.countDown()
                releaseHook.await(5, TimeUnit.SECONDS)
            },
        )
        val storeB = ImportCustodyStore(root = root, dao = dao)

        val id = storeA.begin("file")!!
        val size = writePayload(root, id, "payload-content".toByteArray())

        val threadA = thread(name = "test-storeA") {
            val res = storeA.publish(id, "display", "text/plain", size)
            assertTrue(res)
        }

        assertTrue(inHook.await(5, TimeUnit.SECONDS))

        val threadB = thread(name = "test-storeB") {
            storeB.recover()
            recoverDone.set(true)
        }

        val lock = ImportRootLock.lockFor(root)
        val waitStart = System.currentTimeMillis()
        while (!lock.hasQueuedThread(threadB)) {
            assertFalse(recoverDone.get(), "recover must not finish while publish holds the lock")
            if (System.currentTimeMillis() - waitStart > 5000L) {
                error("Timed out waiting for recover thread to queue on lock")
            }
            Thread.yield()
        }

        assertTrue(Files.exists(orphanDir))
        assertNull(dao.byId(id))

        releaseHook.countDown()
        threadA.join(5000)
        threadB.join(5000)

        assertTrue(Files.exists(root.resolve(id).resolve("payload")))
        val row = dao.byId(id)
        assertNotNull(row)
        assertEquals("pending", row.state)
        assertFalse(Files.exists(orphanDir))
    }

    @Test
    fun stagingExpirationAndMissingDirectoryRecovery() = withTempDir { root ->
        val dao = InMemoryImportDao()
        var testTime = 10_000_000L
        val store = ImportCustodyStore(root = root, dao = dao, clock = { testTime })

        val idExact = store.begin("file")!!
        val idYoung = store.begin("file")!!
        val idOld = store.begin("file")!!

        val staging = root.resolve(".staging")
        val dirExact = staging.resolve(idExact)
        val dirYoung = staging.resolve(idYoung)
        val dirOld = staging.resolve(idOld)

        val baseMtime = 5_000_000L
        Files.setLastModifiedTime(dirExact, FileTime.fromMillis(baseMtime))
        Files.setLastModifiedTime(dirYoung, FileTime.fromMillis(baseMtime + 500_000L))
        Files.setLastModifiedTime(dirOld, FileTime.fromMillis(baseMtime - 500_000L))

        val mtimeExact = Files.getLastModifiedTime(dirExact).toMillis()
        testTime = mtimeExact + 3_600_000L // age == 3_600_000 for exact, younger for young, older for old

        // Also place a regular file directly in the import root
        val rootFile = root.resolve("regular-file-in-root.txt")
        Files.write(rootFile, "file-content".toByteArray())

        // Also publish a pending row whose directory is deleted
        val idPending = store.begin("text")!!
        val sizePending = writePayload(root, idPending)
        assertTrue(store.publish(idPending, "pending-doc", "text/plain", sizePending))
        root.resolve(idPending).toFile().deleteRecursively()

        store.recover()

        assertTrue(Files.exists(rootFile), "regular file directly in import root must still be there after recover")
        assertEquals("file-content", Files.readAllBytes(rootFile).decodeToString())
        assertTrue(Files.exists(staging), ".staging itself must survive")
        assertTrue(Files.exists(dirExact), "age == 3_600_000 must stay")
        assertTrue(Files.exists(dirYoung), "age < 3_600_000 must stay")
        assertFalse(Files.exists(dirOld), "age > 3_600_000 must be removed")

        val localAttentionRow = dao.byId(idPending)
        assertNotNull(localAttentionRow)
        assertEquals("attention", localAttentionRow.state)
        assertEquals("missing_copy", localAttentionRow.attentionKind)
        assertNull(localAttentionRow.attentionInstanceId)
        assertNull(localAttentionRow.attentionJournalVersion)
        assertNull(localAttentionRow.attentionError)
        assertNull(localAttentionRow.attentionReasonCode)
        assertNull(localAttentionRow.progressPath)
        assertNull(localAttentionRow.backoffInstanceId)

        // Eligible at A and B, versions 2.0.52 and 2.0.53, does not return local attention
        assertTrue(store.eligible("A", "2.0.52", testTime, 10).isEmpty())
        assertTrue(store.eligible("B", "2.0.52", testTime, 10).isEmpty())
        assertTrue(store.eligible("A", "2.0.53", testTime, 10).isEmpty())
        assertTrue(store.eligible("B", "2.0.53", testTime, 10).isEmpty())

        // A second recover leaves that row as it is
        store.recover()
        assertEquals(localAttentionRow, dao.byId(idPending))
    }

    @Test
    fun eligibilityRulesAndBackoffTransitions() = withTempDir { root ->
        val dao = InMemoryImportDao()
        var now = 1_000_000L
        val store = ImportCustodyStore(root = root, dao = dao, clock = { now })

        // 1. Journal attention at A is eligible at B; attention at A on 2.0.52 is not eligible at A/2.0.52 but eligible at A/2.0.53
        val idAtt = store.begin("file")!!
        val sizeAtt = writePayload(root, idAtt)
        assertTrue(store.publish(idAtt, "att-file", "text/plain", sizeAtt))
        assertTrue(store.markAttention(idAtt, "some error", "code", "warn", "A", "2.0.52"))

        assertEquals(listOf(idAtt), store.eligible("B", "2.0.52", now, 10).map { it.id })
        assertTrue(store.eligible("A", "2.0.52", now, 10).isEmpty())
        assertEquals(listOf(idAtt), store.eligible("A", "2.0.53", now, 10).map { it.id })

        // 2. markRetry at A holds while now < next_attempt_at and is due when now == next_attempt_at
        val idRetry = store.begin("file")!!
        val sizeRetry = writePayload(root, idRetry)
        assertTrue(store.publish(idRetry, "retry-file", "text/plain", sizeRetry))
        assertTrue(store.markRetry(idRetry, "A", now))

        val heldA = store.held("A").first { it.id == idRetry }
        val nextAttemptAt = heldA.backoff!!.nextAttemptAt
        assertEquals(1, heldA.backoff.attemptCount)
        assertEquals(now + 15 * 60_000L, nextAttemptAt)

        // While now < next_attempt_at, ineligible at A, eligible at B
        assertTrue(store.eligible("A", "2.0.52", nextAttemptAt - 1, 10).map { it.id }.none { it == idRetry })
        assertTrue(store.eligible("B", "2.0.52", nextAttemptAt - 1, 10).map { it.id }.contains(idRetry))

        // When now == next_attempt_at, eligible at A
        assertTrue(store.eligible("A", "2.0.52", nextAttemptAt, 10).map { it.id }.contains(idRetry))

        // After two retries at A, B's first markRetry has attemptCount == 1 and 15 min delay
        assertTrue(store.markRetry(idRetry, "A", nextAttemptAt))
        assertEquals(2, store.held("A").first { it.id == idRetry }.backoff!!.attemptCount)

        now = nextAttemptAt + 30 * 60_000L
        assertTrue(store.markRetry(idRetry, "B", now))
        val heldB = store.held("B").first { it.id == idRetry }
        assertEquals(1, heldB.backoff!!.attemptCount)
        assertEquals(now + 15 * 60_000L, heldB.backoff.nextAttemptAt)

        // 3. markSaved at A is reported by eligible and held at A, absent at B
        val idSaved = store.begin("file")!!
        val sizeSaved = writePayload(root, idSaved)
        assertTrue(store.publish(idSaved, "saved-file", "text/plain", sizeSaved))
        assertTrue(store.markSaved(idSaved, "/progress/path", "20261019_101500", "A"))

        val candA = store.eligible("A", "2.0.52", now, 10).first { it.id == idSaved }
        assertNotNull(candA.progress)
        assertEquals("/progress/path", candA.progress.path)
        assertEquals("20261019_101500", candA.progress.timestamp)

        val candB = store.eligible("B", "2.0.52", now, 10).first { it.id == idSaved }
        assertNull(candB.progress)

        val heldItemA = store.held("A").first { it.id == idSaved }
        assertNotNull(heldItemA.progress)
        val heldItemB = store.held("B").first { it.id == idSaved }
        assertNull(heldItemB.progress)

        // 4. markSaved then markRetry: progress is still reported
        assertTrue(store.markRetry(idSaved, "A", now))
        val candAfterRetry = store.held("A").first { it.id == idSaved }
        assertNotNull(candAfterRetry.progress)
        assertEquals("/progress/path", candAfterRetry.progress.path)
    }

    @Test
    fun orderingAndLimit() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        dao.insert(createRow("item-1", createdAt = 100L))
        dao.insert(createRow("item-2", createdAt = 200L))
        dao.insert(createRow("item-3", createdAt = 300L))

        val candidates = store.eligible("inst", "v1", 1000L, limit = 2)
        assertEquals(listOf("item-1", "item-2"), candidates.map { it.id })

        // Tie breaking by id
        dao.delete("item-1")
        dao.delete("item-2")
        dao.delete("item-3")
        dao.insert(createRow("item-z", createdAt = 100L))
        dao.insert(createRow("item-a", createdAt = 100L))
        val tieCandidates = store.eligible("inst", "v1", 1000L, limit = 2)
        assertEquals(listOf("item-a", "item-z"), tieCandidates.map { it.id })
    }

    @Test
    fun resetAttentionTransitions() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        val idJournal = store.begin("file")!!
        val sizeJ = writePayload(root, idJournal)
        assertTrue(store.publish(idJournal, "journal-att", "text/plain", sizeJ))
        assertTrue(store.markSaved(idJournal, "/tmp/p", "20261019_101500", "A"))
        assertTrue(store.markRetry(idJournal, "A", 1000L))
        assertTrue(store.markAttention(idJournal, "err", "reason", "warn", "A", "2.0.52"))

        // resetAttention on journal attention succeeds
        assertTrue(store.resetAttention(idJournal))
        val after = dao.byId(idJournal)!!
        assertEquals("pending", after.state)
        assertNull(after.progressPath)
        assertNull(after.backoffInstanceId)
        assertNull(after.attentionInstanceId)
        assertEquals(0, after.attemptCount)
        assertTrue(store.eligible("A", "2.0.52", 1000L, 10).map { it.id }.contains(idJournal))

        // resetAttention on local attention returns false and row is unchanged
        val idLocal = store.begin("file")!!
        val sizeL = writePayload(root, idLocal)
        assertTrue(store.publish(idLocal, "local-att", "text/plain", sizeL))
        root.resolve(idLocal).toFile().deleteRecursively()
        store.recover() // becomes local attention
        val localBefore = dao.byId(idLocal)!!
        assertEquals("missing_copy", localBefore.attentionKind)
        assertFalse(store.resetAttention(idLocal))
        assertEquals(localBefore, dao.byId(idLocal))
    }

    @Test
    fun illegalTransitionsReturnFalseAndLeaveRowUnchanged() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        // 1. markRetry on journal attention
        val idJ = store.begin("file")!!
        writePayload(root, idJ)
        store.publish(idJ, "j", "text/plain", 13L)
        store.markAttention(idJ, "e", "r", "k", "A", "2.0.52")
        val rowJ = dao.byId(idJ)!!
        assertFalse(store.markRetry(idJ, "A", 1000L))
        assertEquals(rowJ, dao.byId(idJ))

        // 2. markDelivered on local attention
        val idL = store.begin("file")!!
        writePayload(root, idL)
        store.publish(idL, "l", "text/plain", 13L)
        root.resolve(idL).toFile().deleteRecursively()
        store.recover()
        val rowL = dao.byId(idL)!!
        assertFalse(store.markDelivered(idL))
        assertEquals(rowL, dao.byId(idL))

        // 3. resetAttention on pending
        val idP = store.begin("file")!!
        writePayload(root, idP)
        store.publish(idP, "p", "text/plain", 13L)
        val rowP = dao.byId(idP)!!
        assertFalse(store.resetAttention(idP))
        assertEquals(rowP, dao.byId(idP))

        // 4. markAttention with null instance
        assertFalse(store.markAttention(idP, "e", "r", "k", null, "2.0.52"))
        assertFalse(store.markAttention(idP, "e", "r", "k", "A", null))
        assertEquals(rowP, dao.byId(idP))

        // 5. markSaved on delivered
        assertTrue(store.markDelivered(idP))
        val rowD = dao.byId(idP)!!
        assertEquals("delivered", rowD.state)
        assertFalse(store.markSaved(idP, "/p", "20261019_101500", "A"))
        assertEquals(rowD, dao.byId(idP))
    }

    @Test
    fun finishAndRecoverLifecycle() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        // markDelivered then finish removes directory and row
        val id1 = store.begin("file")!!
        val size1 = writePayload(root, id1)
        store.publish(id1, "doc1", "text/plain", size1)
        assertTrue(store.markDelivered(id1))
        store.finish()
        assertFalse(Files.exists(root.resolve(id1)))
        assertNull(dao.byId(id1))

        // DAO throws on markDelivered: false, bytes remain, state not delivered
        val id2 = store.begin("file")!!
        val size2 = writePayload(root, id2)
        store.publish(id2, "doc2", "text/plain", size2)
        dao.throwOnUpdate = true
        assertFalse(store.markDelivered(id2))
        dao.throwOnUpdate = false
        assertTrue(Files.exists(root.resolve(id2).resolve("payload")))
        assertEquals("pending", dao.byId(id2)!!.state)

        // Remover returns false for one delivered id and succeeds for another
        val idFail = store.begin("file")!!
        val sizeFail = writePayload(root, idFail)
        store.publish(idFail, "fail-doc", "text/plain", sizeFail)
        store.markDelivered(idFail)

        val idOk = store.begin("file")!!
        val sizeOk = writePayload(root, idOk)
        store.publish(idOk, "ok-doc", "text/plain", sizeOk)
        store.markDelivered(idOk)

        val delegateRemover = NioImportDirectoryRemover()
        val partialStore = ImportCustodyStore(
            root = root,
            dao = dao,
            remover = { dir ->
                if (dir.fileName.toString() == idFail) {
                    false
                } else {
                    delegateRemover.remove(dir)
                }
            },
        )
        partialStore.finish()
        assertEquals("delivered", dao.byId(idFail)?.state)
        assertTrue(Files.exists(root.resolve(idFail)))
        assertNull(dao.byId(idOk))
        assertFalse(Files.exists(root.resolve(idOk)))

        // Remover succeeds and delete throws: directory gone, row stays delivered, status attention count is 0
        dao.throwOnDelete = true
        val fullStore = ImportCustodyStore(root = root, dao = dao)
        fullStore.finish()
        assertFalse(Files.exists(root.resolve(idFail)))
        assertEquals("delivered", dao.byId(idFail)?.state)
        assertEquals(0, fullStore.status().attentionCount)

        // Clearing failure and calling recover() deletes the row and does not write local attention
        dao.throwOnDelete = false
        fullStore.recover()
        assertNull(dao.byId(idFail))
    }

    @Test
    fun deliveredDirectoryWithSymlinkRemovesLinkOnlyAndDoesNotTouchTarget() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        val outside = root.resolve("outside-file.txt")
        Files.write(outside, "outside-content".toByteArray())

        val idDelivered = store.begin("file")!!
        val sizeD = writePayload(root, idDelivered)
        store.publish(idDelivered, "del-doc", "text/plain", sizeD)
        store.markDelivered(idDelivered)

        // Create symlink inside delivered directory
        Files.createSymbolicLink(root.resolve(idDelivered).resolve("link-to-outside"), outside)

        // Neighbor pending item
        val idPending = store.begin("file")!!
        val sizeP = writePayload(root, idPending, "neighbor-content".toByteArray())
        store.publish(idPending, "pending-doc", "text/plain", sizeP)

        store.finish()

        assertFalse(Files.exists(root.resolve(idDelivered)))
        assertTrue(Files.exists(outside))
        assertEquals("outside-content", Files.readAllBytes(outside).decodeToString())
        assertTrue(Files.exists(root.resolve(idPending).resolve("payload")))
        assertEquals("neighbor-content", Files.readAllBytes(root.resolve(idPending).resolve("payload")).decodeToString())
    }

    @Test
    fun statusModelCountsAndAttentionFacts() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)

        val idP = store.begin("file")!!
        writePayload(root, idP)
        store.publish(idP, "pending-item", "text/plain", 13L)

        val idA = store.begin("file")!!
        writePayload(root, idA)
        store.publish(idA, "attention-item", "text/plain", 13L)
        store.markAttention(idA, "some error", "code", "kind-warn", "inst", "v1")

        val idD = store.begin("file")!!
        writePayload(root, idD)
        store.publish(idD, "delivered-item", "text/plain", 13L)
        store.markDelivered(idD)

        val status = store.status()
        assertEquals(1, status.pendingCount)
        assertEquals(1, status.attentionCount)
        assertEquals(1, status.attention.size)
        assertEquals("attention-item", status.attention[0].displayName)
        assertEquals("some error", status.attention[0].error)
        assertEquals("kind-warn", status.attention[0].kind)
    }

    @Test
    fun differentRootsDoNotShareALock() = withTempDir { base ->
        val rootA = base.resolve("rootA")
        val rootB = base.resolve("rootB")
        Files.createDirectories(rootA)
        Files.createDirectories(rootB)

        val daoA = InMemoryImportDao()
        val daoB = InMemoryImportDao()

        val inHookA = CountDownLatch(1)
        val releaseHookA = CountDownLatch(1)

        val storeA = ImportCustodyStore(
            root = rootA,
            dao = daoA,
            afterMove = {
                inHookA.countDown()
                releaseHookA.await(5, TimeUnit.SECONDS)
            },
        )
        val storeB = ImportCustodyStore(root = rootB, dao = daoB)

        val idA = storeA.begin("file")!!
        val sizeA = writePayload(rootA, idA)
        val threadA = thread(name = "test-storeA-root") {
            storeA.publish(idA, "docA", "text/plain", sizeA)
        }

        assertTrue(inHookA.await(5, TimeUnit.SECONDS))

        // While A is inside hook, B's publish on rootB completes without waiting on A's lock
        val idB = storeB.begin("file")!!
        val sizeB = writePayload(rootB, idB)
        val bPublished = storeB.publish(idB, "docB", "text/plain", sizeB)
        assertTrue(bPublished)
        assertNotNull(daoB.byId(idB))

        releaseHookA.countDown()
        threadA.join(5000)
        assertNotNull(daoA.byId(idA))
    }

    private fun published(store: ImportCustodyStore, root: Path): String {
        val id = store.begin("file")!!
        assertTrue(store.publish(id, "doc-$id", "application/pdf", writePayload(root, id)))
        return id
    }

    @Test
    fun anItemInAnExchangeCannotBeRemovedOrRetriedUntilTheExchangeEnds() = withTempDir { root ->
        val store = ImportCustodyStore(root = root, dao = InMemoryImportDao())
        val id = published(store, root)
        assertEquals(ImportChange.DONE, store.beginExchange(id, "A", "2.0.52"))
        assertTrue(store.isInFlight(id))
        assertEquals(ImportChange.IN_FLIGHT, store.beginExchange(id, "A", "2.0.52"))
        assertEquals(ImportChange.IN_FLIGHT, store.remove(id))
        assertEquals(ImportChange.IN_FLIGHT, store.retry(id))
        assertTrue(Files.exists(root.resolve(id).resolve("payload")))
        store.endExchange(id)
        assertFalse(store.isInFlight(id))
        assertEquals(ImportChange.DONE, store.remove(id))
        assertFalse(Files.exists(root.resolve(id)))
        assertEquals(ImportChange.GONE, store.remove(id))
    }

    @Test
    fun deliveryEndsTheExchangeAndRecordsWhichJournalHasIt() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao, clock = { 5_000L })
        val id = published(store, root)
        assertNull(store.lastDelivery())
        assertEquals(ImportChange.DONE, store.beginExchange(id, "A", "2.0.52"))
        assertEquals(ImportChange.DONE, store.recordSaved(id, "/imports/x/doc.pdf", "20261019_101500", "A"))
        assertTrue(store.isInFlight(id), "saving keeps the exchange open for its start")
        assertEquals(ImportChange.DONE, store.recordDelivered(id, "A"))
        assertFalse(store.isInFlight(id))
        assertEquals(ImportLastDelivery(5_000L, "A"), store.lastDelivery())
        assertEquals(ImportChange.DELIVERED, store.remove(id))
        assertEquals(ImportChange.DELIVERED, store.retry(id))
        store.finish()
        assertEquals(ImportChange.GONE, store.remove(id))
    }

    @Test
    fun aVanishedItemIsGoneForEveryRecordAndNothingIsCreated() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)
        val id = published(store, root)
        assertEquals(ImportChange.DONE, store.beginExchange(id, "A", "2.0.52"))
        dao.rows.remove(id)
        assertEquals(ImportChange.GONE, store.recordSaved(id, "/p", "20261019_101500", "A"))
        assertEquals(ImportChange.GONE, store.recordDelivered(id, "A"))
        assertEquals(ImportChange.GONE, store.recordAttention(id, "refused", "import_conflict", null, "A", "2.0.52"))
        assertEquals(ImportChange.GONE, store.beginExchange(id, "A", "2.0.52"))
        assertFalse(store.markRetry(id, "A", 1L))
        assertNull(dao.byId(id))
        assertFalse(store.isInFlight(id))
    }

    @Test
    fun attentionFromAnotherJournalOrVersionGoesAgainButNotFromThisOne() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)
        val id = published(store, root)
        assertEquals(ImportChange.DONE, store.beginExchange(id, "A", "2.0.52"))
        assertEquals(ImportChange.DONE, store.recordAttention(id, "too large", "multipart_part_too_large", null, "A", "2.0.52"))
        assertEquals(ImportAttentionScope.HERE, store.items("A", "2.0.52").single().scope)
        assertEquals(ImportChange.REFUSED, store.beginExchange(id, "A", "2.0.52"))
        assertEquals(ImportAttentionScope.ELSEWHERE, store.items("A", "2.0.53").single().scope)
        assertEquals(ImportAttentionScope.ELSEWHERE, store.items("B", "2.0.52").single().scope)
        assertEquals(ImportChange.DONE, store.beginExchange(id, "B", "2.0.52"))
        assertEquals("pending", dao.byId(id)!!.state)
        assertTrue(store.items("B", "2.0.52").single().inFlight)
    }

    @Test
    fun aMissingCopyIsLocalAndCanOnlyBeRemoved() = withTempDir { root ->
        val dao = InMemoryImportDao()
        val store = ImportCustodyStore(root = root, dao = dao)
        val id = published(store, root)
        root.resolve(id).toFile().deleteRecursively()
        store.recover()
        val item = store.items("A", "2.0.52").single()
        assertEquals(ImportAttentionScope.LOCAL, item.scope)
        assertEquals(ImportChange.REFUSED, store.retry(id))
        assertEquals(ImportChange.REFUSED, store.beginExchange(id, "A", "2.0.52"))
        assertEquals(ImportChange.DONE, store.remove(id))
    }

    @Test
    fun anInterruptedRemovalLeavesOnlyBytesWhichRecoveryReaps() = withTempDir { root ->
        val dao = InMemoryImportDao()
        var failRemove = true
        val real = NioImportDirectoryRemover()
        val store = ImportCustodyStore(root = root, dao = dao, remover = { dir -> if (failRemove) false else real.remove(dir) })
        val id = published(store, root)
        assertEquals(ImportChange.DONE, store.remove(id))
        assertNull(dao.byId(id))
        assertTrue(Files.exists(root.resolve(id)))
        failRemove = false
        store.recover()
        assertFalse(Files.exists(root.resolve(id)))
        assertTrue(store.items("A", "2.0.52").isEmpty(), "no missing-copy item appears for a removed one")
    }

    @Test
    fun savedProgressIsReported() = withTempDir { root ->
        val store = ImportCustodyStore(root = root, dao = InMemoryImportDao())
        val id = published(store, root)
        assertFalse(store.items("A", "2.0.52").single().hasSavedProgress)
        assertEquals(ImportChange.DONE, store.beginExchange(id, "A", "2.0.52"))
        assertEquals(ImportChange.DONE, store.recordSaved(id, "/p", "20261019_101500", "A"))
        store.endExchange(id)
        assertTrue(store.items("A", "2.0.52").single().hasSavedProgress)
    }

    private fun createRow(
        id: String,
        state: String = "pending",
        createdAt: Long = 1000L,
    ): ImportItemRow =
        ImportItemRow(
            id = id,
            payloadKind = "file",
            displayName = "name-$id",
            mime = "text/plain",
            byteSize = 10L,
            state = state,
            createdAt = createdAt,
            progressPath = null,
            progressTimestamp = null,
            progressInstanceId = null,
            backoffInstanceId = null,
            attemptCount = 0,
            nextAttemptAt = null,
            attentionError = null,
            attentionReasonCode = null,
            attentionKind = null,
            attentionInstanceId = null,
            attentionJournalVersion = null,
        )
}
