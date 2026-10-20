// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportCustodyStoreInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SolstonePersistenceDatabase::class.java,
    )

    private val targetContext: Context get() = ApplicationProvider.getApplicationContext()
    private val executor = Executors.newSingleThreadExecutor()

    private fun <T> runDb(block: () -> T): T {
        try {
            return executor.submit(Callable { block() }).get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun writePayload(root: Path, id: String, content: ByteArray): Long {
        val payload = root.resolve(".staging").resolve(id).resolve("payload")
        Files.write(payload, content)
        return content.size.toLong()
    }

    @After
    fun tearDown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun beginAndPublishReopenPreservesContentAndDescriptors() {
        val dbName = "import-test-${UUID.randomUUID()}.db"
        val rootDir = targetContext.cacheDir.resolve("import-root-${UUID.randomUUID()}").toPath()
        Files.createDirectories(rootDir)

        var db: SolstonePersistenceDatabase? = null
        try {
            val (idText, sizeText, idFile, sizeFile) = runDb {
                val database = openSolstonePersistenceDatabase(targetContext, dbName).also { db = it }
                val store = ImportCustodyStore(root = rootDir, dao = database.importDao())

                val idT = store.begin("text")!!
                val textBytes = "hello-text".toByteArray()
                val sizeT = writePayload(rootDir, idT, textBytes)
                assertTrue(store.publish(idT, "Text Doc", "text/plain", sizeT))

                val idF = store.begin("file")!!
                val fileBytes = "hello-file-data".toByteArray()
                val sizeF = writePayload(rootDir, idF, fileBytes)
                assertTrue(store.publish(idF, "File Doc", "text/plain", sizeF))

                database.close()
                listOf(idT, sizeT, idF, sizeF)
            }
            val idT = idText as String
            val sizeT = sizeText as Long
            val idF = idFile as String
            val sizeF = sizeFile as Long

            assertEquals("hello-text", Files.readAllBytes(rootDir.resolve(idT).resolve("payload")).decodeToString())
            assertEquals("hello-file-data", Files.readAllBytes(rootDir.resolve(idF).resolve("payload")).decodeToString())

            runDb {
                val database = openSolstonePersistenceDatabase(targetContext, dbName).also { db = it }
                val reopenedStore = ImportCustodyStore(root = rootDir, dao = database.importDao())

                val descText = reopenedStore.item(idT)
                assertNotNull(descText)
                assertEquals("text", descText!!.payloadKind)
                assertEquals("Text Doc", descText.displayName)
                assertEquals("text/plain", descText.mime)
                assertEquals(sizeT, descText.size)
                assertEquals(rootDir.resolve(idT).resolve("payload"), descText.payloadPath)

                val descFile = reopenedStore.item(idF)
                assertNotNull(descFile)
                assertEquals("file", descFile!!.payloadKind)
                assertEquals("File Doc", descFile.displayName)
                assertEquals("text/plain", descFile.mime)
                assertEquals(sizeF, descFile.size)
                assertEquals(rootDir.resolve(idF).resolve("payload"), descFile.payloadPath)

                val rowText = database.importDao().byId(idT)
                assertNotNull(rowText)
                assertEquals("pending", rowText!!.state)

                val rowFile = database.importDao().byId(idF)
                assertNotNull(rowFile)
                assertEquals("pending", rowFile!!.state)

                val eligible = reopenedStore.eligible("inst", "v1", System.currentTimeMillis(), 10)
                assertEquals(setOf(idT, idF), eligible.map { it.id }.toSet())
            }
        } finally {
            runDb {
                try {
                    db?.close()
                } catch (_: Exception) {
                }
            }
            targetContext.deleteDatabase(dbName)
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun eligibilityAndTransitionsPersistAcrossReopen() {
        val dbName = "import-eligibility-${UUID.randomUUID()}.db"
        val rootDir = targetContext.cacheDir.resolve("import-root-${UUID.randomUUID()}").toPath()
        Files.createDirectories(rootDir)

        var db: SolstonePersistenceDatabase? = null
        val now = 1_000_000L
        val nextAttempt = now + 15 * 60_000L
        try {
            val (idAtt, idRetry, idSaved, idLocal) = runDb {
                val database = openSolstonePersistenceDatabase(targetContext, dbName).also { db = it }
                val store = ImportCustodyStore(root = rootDir, dao = database.importDao(), clock = { now })

                // 1. Attention item
                val idA = store.begin("file")!!
                val sizeA = writePayload(rootDir, idA, "att".toByteArray())
                assertTrue(store.publish(idA, "att", "text/plain", sizeA))
                assertTrue(store.markAttention(idA, "err", "code", "warn", "A", "2.0.52"))

                // 2. Retry item
                val idR = store.begin("file")!!
                val sizeR = writePayload(rootDir, idR, "retry".toByteArray())
                assertTrue(store.publish(idR, "retry", "text/plain", sizeR))
                assertTrue(store.markRetry(idR, "A", now))

                // 3. Saved item
                val idS = store.begin("file")!!
                val sizeS = writePayload(rootDir, idS, "saved".toByteArray())
                assertTrue(store.publish(idS, "saved", "text/plain", sizeS))
                assertTrue(store.markSaved(idS, "/path/p", "20261019_101500", "A"))

                // 4. Local attention item
                val idL = store.begin("file")!!
                val sizeL = writePayload(rootDir, idL, "local".toByteArray())
                assertTrue(store.publish(idL, "local", "text/plain", sizeL))
                rootDir.resolve(idL).toFile().deleteRecursively()
                store.recover()

                database.close()
                listOf(idA, idR, idS, idL)
            }

            // Reopen and run assertions entirely on the executor
            runDb {
                val database = openSolstonePersistenceDatabase(targetContext, dbName).also { db = it }
                val store = ImportCustodyStore(root = rootDir, dao = database.importDao(), clock = { now })

                // Attention instance/version assertions
                assertEquals(listOf(idAtt), store.eligible("B", "2.0.52", now, 10).map { it.id }.filter { it == idAtt })
                assertTrue(store.eligible("A", "2.0.52", now, 10).none { it.id == idAtt })
                assertEquals(listOf(idAtt), store.eligible("A", "2.0.53", now, 10).map { it.id }.filter { it == idAtt })

                // Backoff hold at A
                assertTrue(store.eligible("A", "2.0.52", nextAttempt - 1, 10).none { it.id == idRetry })
                assertTrue(store.eligible("A", "2.0.52", nextAttempt, 10).any { it.id == idRetry })

                // Retry id is eligible at instance B when now is still before nextAttemptAt
                assertTrue(store.eligible("B", "2.0.52", nextAttempt - 1, 10).any { it.id == idRetry })

                // idLocal is absent from eligible at A and B, for versions 2.0.52 and 2.0.53
                assertTrue(store.eligible("A", "2.0.52", now, 10).none { it.id == idLocal })
                assertTrue(store.eligible("B", "2.0.52", now, 10).none { it.id == idLocal })
                assertTrue(store.eligible("A", "2.0.53", now, 10).none { it.id == idLocal })
                assertTrue(store.eligible("B", "2.0.53", now, 10).none { it.id == idLocal })

                // Progress present only at recording instance
                val candA = store.eligible("A", "2.0.52", now, 10).first { it.id == idSaved }
                assertNotNull(candA.progress)
                assertEquals("/path/p", candA.progress!!.path)
                val candB = store.eligible("B", "2.0.52", now, 10).first { it.id == idSaved }
                assertNull(candB.progress)

                // Limit test (oldest 2): returns sorted ids of attention item, retry item, and saved item, take(2)
                val expectedOldestTwo = listOf(idAtt, idRetry, idSaved).sorted().take(2)
                val actualOldestTwo = store.eligible("B", "2.0.53", nextAttempt, limit = 2).map { it.id }
                assertEquals(expectedOldestTwo, actualOldestTwo)

                // resetAttention on journal attention id: id is eligible and progress is null
                assertTrue(store.resetAttention(idAtt))
                val attAfterReset = database.importDao().byId(idAtt)!!
                assertEquals("pending", attAfterReset.state)
                assertNull(attAfterReset.progressPath)
                val candAtt = store.eligible("A", "2.0.52", now, 10).first { it.id == idAtt }
                assertNull(candAtt.progress)

                // resetAttention refuses local attention
                assertFalse(store.resetAttention(idLocal))
            }
        } finally {
            runDb {
                try {
                    db?.close()
                } catch (_: Exception) {
                }
            }
            targetContext.deleteDatabase(dbName)
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun factoryMigrationAndSurvivalFromV4() {
        val instrTargetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "import-factory-mig-${UUID.randomUUID()}"
        val rootDir = instrTargetContext.cacheDir.resolve("import-factory-root-${UUID.randomUUID()}").toPath()
        Files.createDirectories(rootDir)

        val day = "20260617"
        val segmentId = "$day/main/120000_10"

        // 1. Create database at version 4 using MigrationTestHelper on test thread
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                INSERT INTO segment (
                    id, day, stream, segment, dir_segment, state, byte_size, sealed_at,
                    home_instance_id, observer_handle, attempt_count, last_status_code, last_attempt_at, last_error
                ) VALUES (
                    '$segmentId', '$day', 'main', '120000_10', '120000_10', 'SEALED', 10, 1000,
                    'home-inst', 'obs-handle', 1, 200, 500, 'none'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO segment_file (
                    segment_id, source_id, name, sha256, byte_size, media_type, capture_start_epoch_ms, capture_end_epoch_ms
                ) VALUES (
                    '$segmentId', 'audio', 'audio.bin', 'sha-audio', 10, 'application/octet-stream', 1, 2
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO event (segment_id, kind, at_epoch_ms, detail)
                VALUES ('$segmentId', 'eviction', 1000, 'detail')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO sync_state (id, pending_count, last_success_at, last_failure_at)
                VALUES (0, 1, 100, 200)
                """.trimIndent(),
            )
            close()
        }

        var db: SolstonePersistenceDatabase? = null
        try {
            // 2. Open via factory on executor, check segment row, publish import, close
            val importId = runDb {
                val database = openSolstonePersistenceDatabase(instrTargetContext, dbName).also { db = it }
                val segRow = database.segmentDao().segmentById(segmentId)
                assertNotNull(segRow)
                assertEquals(segmentId, segRow!!.id)

                val store = ImportCustodyStore(root = rootDir, dao = database.importDao())
                val id = store.begin("file")!!
                val bytes = "migrated-import-payload".toByteArray()
                val size = writePayload(rootDir, id, bytes)
                assertTrue(store.publish(id, "Migrated Doc", "text/plain", size))

                database.close()
                id
            }

            // 3. Reopen on executor, assert segment row and import item
            runDb {
                val database = openSolstonePersistenceDatabase(instrTargetContext, dbName).also { db = it }
                val segRowAfter = database.segmentDao().segmentById(segmentId)
                assertNotNull(segRowAfter)

                val importRowAfter = database.importDao().byId(importId)
                assertNotNull(importRowAfter)
                assertEquals("pending", importRowAfter!!.state)
            }

            val payloadBytes = Files.readAllBytes(rootDir.resolve(importId).resolve("payload"))
            assertEquals("migrated-import-payload", payloadBytes.decodeToString())
        } finally {
            runDb {
                try {
                    db?.close()
                } catch (_: Exception) {
                }
            }
            instrTargetContext.deleteDatabase(dbName)
            rootDir.toFile().deleteRecursively()
        }
    }
}
