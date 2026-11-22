// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.observer.IntakeRefusal
import app.solstone.core.pl.parseJson
import app.solstone.platform.persistence.room.ImportHandoffStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalImportHostTest {
    private val origin = "http://0123456789abcdef0123456789abcdef.localhost:7657"
    private val contract = assertNotNull(
        parseImportHostContract(File("src/main/assets/journal-web-host/import-host-contract.json").readBytes()),
    )
    private val context = """{"setting":"host conformance","future":{"preserve":true}}"""

    private var pickerOpen: ((List<String>?) -> Unit)? = null
    private var pickedMultiple: Boolean? = null
    private val submitted = mutableListOf<List<IntakeItem>>()
    private var outcome = IntakeOutcome(emptyList(), emptyList())
    private val statuses = mutableMapOf<String, ImportHandoffStatus>()
    private val replies = mutableListOf<Map<*, *>>()

    private val host = JournalImportHost<String>(
        origin = origin,
        contract = contract,
        openPicker = { multiple, onPicked -> pickedMultiple = multiple; pickerOpen = onPicked },
        itemFor = { name -> IntakeItem.Stream(name, null, "content", 1, ByteArrayInputStream(byteArrayOf(1))) },
        submit = { items, done -> submitted += items; done(outcome) },
        status = { id -> statuses[id] ?: ImportHandoffStatus("gone") },
        background = { it() },
    )

    private fun send(message: String, contextText: String? = context, from: String = origin, mainFrame: Boolean = true) {
        val packet = """{"seq":${replies.size + 100},"message":$message,"context":${contextText?.let { app.solstone.core.pl.toJson(it) } ?: "null"}}"""
        host.onMessage(packet, from, mainFrame) { text ->
            val answer = parseJson(text) as Map<*, *>
            replies += answer["body"] as Map<*, *>
        }
    }

    private fun pick(id: String, area: String = "quick", multiple: Boolean = true) =
        """{"type":"import.pick","id":"$id","area":"$area","accept":[".txt","text/plain"],"multiple":$multiple}"""

    @Test
    fun theVendoredContractIsVersionTwoWithTheJournalsNames() {
        assertEquals("solstoneJournalHost", contract.capability)
        assertEquals("import.pick", contract.pick)
        assertEquals("import.reply", contract.reply)
        assertEquals("unsupported", contract.unsupported)
        assertEquals(16384, contract.contextMaxBytes)
        assertNull(parseImportHostContract("""{"version":1,"capability":"x"}""".toByteArray()))
    }

    @Test
    fun theScriptGetsTheContractTheOriginAndTheBridge() {
        val template = File("src/main/assets/journal-web-host/import-host.js").readText()
        val script = journalImportHostScript(template, contract, origin)
        assertTrue(script.contains("\"capability\": \"solstoneJournalHost\""))
        assertTrue(script.contains("\"$origin\", \"$JOURNAL_IMPORT_BRIDGE\")"))
        assertTrue(script.contains("window !== window.top"), "only the top document gets the host")
        assertTrue("__" !in script.substringAfterLast("})("), "every placeholder is filled")
    }

    @Test
    fun anUnknownTypeIsUnsupportedAndAnInvalidPickIsRefusedWithoutAPicker() {
        send("""{"type":"future.example","id":"unknown-1","future":1}""")
        assertEquals(mapOf("type" to "unsupported", "id" to "unknown-1"), replies.last())
        send("""{"type":"import.pick","id":"invalid-1","area":"quick","multiple":true,"accept":[]}""", contextText = null)
        assertEquals("refused", replies.last()["outcome"])
        assertEquals("invalid-1", replies.last()["id"])
        assertEquals("import.reply", replies.last()["type"])
        send(pick("too-big"), contextText = """{"setting":"${"x".repeat(16384)}"}""")
        assertEquals("refused", replies.last()["outcome"], "context over the journal's limit is refused")
        send(pick("array"), contextText = "[]")
        assertEquals("refused", replies.last()["outcome"])
        send(pick("bad-area", area = "quick\r\nContent-Type: x"))
        assertEquals("refused", replies.last()["outcome"], "an area that could break a form field is refused")
        assertNull(pickerOpen)
    }

    @Test
    fun aSubframeOrAnotherOriginIsNeverAnswered() {
        send(pick("frame-1"), mainFrame = false)
        send(pick("other-1"), from = "http://0123456789abcdef0123456789abcdef.localhost:7658")
        send(pick("other-2"), from = "https://services.solstone.app")
        assertTrue(replies.isEmpty())
        assertNull(pickerOpen)
    }

    @Test
    fun anOverlappingPickIsBusyAndDismissingThePickerCancels() {
        send(pick("cancel-1"))
        assertNotNull(pickerOpen)
        send(pick("overlap-1"))
        assertEquals(mapOf("type" to "import.reply", "id" to "overlap-1", "outcome" to "busy"), replies.last())
        pickerOpen!!(emptyList())
        assertEquals(mapOf("type" to "import.reply", "id" to "cancel-1", "outcome" to "cancelled"), replies.last())
        assertTrue(submitted.isEmpty())
        send(pick("after-1"))
        assertNotNull(pickerOpen, "a dismissed pick frees the host")
    }

    @Test
    fun picksAreQueuedWithTheCardAndWholeContextAndMixedRefusalsAreReported() {
        outcome = IntakeOutcome(
            listOf(IntakeSaved("item-a", "sample.txt")),
            listOf(IntakeRefused("huge.zip", IntakeRefusal.TOO_LARGE)),
        )
        send(pick("pick-1", area = "chatgpt", multiple = false))
        assertEquals(false, pickedMultiple)
        pickerOpen!!(listOf("sample.txt", "huge.zip"))
        val handoffs = submitted.single().map { (it as IntakeItem.Stream).handoff }
        assertEquals(listOf(IntakeHandoff("chatgpt", context), IntakeHandoff("chatgpt", context)), handoffs)
        val reply = replies.last()
        assertEquals("queued", reply["outcome"])
        assertEquals("pick-1", reply["id"])
        assertEquals(listOf(mapOf("item" to "item-a", "name" to "sample.txt")), reply["items"])
        val refused = (reply["refused"] as List<*>).single() as Map<*, *>
        assertEquals("huge.zip", refused["name"])
        assertTrue((refused["reason"] as String).isNotEmpty())
    }

    @Test
    fun aSelectionThatSavesNothingIsRefused() {
        outcome = IntakeOutcome(emptyList(), listOf(IntakeRefused("a", IntakeRefusal.UNREADABLE)))
        send(pick("pick-2"))
        pickerOpen!!(listOf("a"))
        assertEquals("refused", replies.last()["outcome"])
    }

    @Test
    fun aPickWhoseDocumentWentAwayIsCancelledAndCopiesNothing() {
        send(pick("pick-3"))
        host.documentStarted()
        pickerOpen!!(listOf("sample.txt"))
        assertEquals("cancelled", replies.last()["outcome"])
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun statusNamesTheStateAndTheJournalRow() {
        statuses["item-a"] = ImportHandoffStatus("delivered", importId = "20261120_190754")
        send("""{"type":"host.status","item":"item-a"}""", contextText = null)
        assertEquals(mapOf("state" to "delivered", "import_id" to "20261120_190754"), replies.last())
        statuses["item-b"] = ImportHandoffStatus("attention", reason = "the journal couldn't read it")
        send("""{"type":"host.status","item":"item-b"}""", contextText = null)
        assertEquals(mapOf("state" to "attention", "reason" to "the journal couldn't read it"), replies.last())
        send("""{"type":"host.status","item":"item-c"}""", contextText = null)
        assertEquals(mapOf("state" to "gone"), replies.last())
    }

    @Test
    fun theOriginIsTheSchemeHostAndPortOnly() {
        assertEquals(origin, journalOriginOf("$origin/app/import/?x=1"))
        assertEquals(origin, journalOriginOf("$origin/"))
        assertNull(journalOriginOf("not a url"))
        assertEquals(BigDecimal(2), (parseJson(contract.json) as Map<*, *>)["version"])
    }

    @Test
    fun additiveV2StatusFieldsEmitReviewExpiredDeviceNounAndSizeBytesWithoutBreakingContract() {
        statuses["review-1"] = ImportHandoffStatus(
            state = "attention",
            importId = "20261122_120000",
            reason = "saved in your journal. preview and confirm to start.",
            reviewRequired = true,
            reviewExpired = false,
            reasonCode = "import_preview_required",
            kind = "body_confirmation_required",
            retryable = false,
        )
        send("""{"type":"host.status","item":"review-1"}""", contextText = null)
        assertEquals(
            mapOf(
                "state" to "attention",
                "import_id" to "20261122_120000",
                "reason" to "saved in your journal. preview and confirm to start.",
                "review_required" to true,
                "review_expired" to false,
                "reason_code" to "import_preview_required",
                "kind" to "body_confirmation_required",
                "retryable" to false,
            ),
            replies.last(),
        )

        statuses["expired-1"] = ImportHandoffStatus(
            state = "attention",
            importId = "20261122_110000",
            reason = "the waiting copy in your journal expired. this device still holds the file.",
            reviewRequired = false,
            reviewExpired = true,
            reasonCode = "import_preview_required",
            kind = "body_confirmation_required",
            retryable = true,
        )
        send("""{"type":"host.status","item":"expired-1"}""", contextText = null)
        assertEquals(true, replies.last()["review_expired"])
        assertEquals(false, replies.last()["review_required"])
        assertEquals(true, replies.last()["retryable"])

        statuses["waiting-1"] = ImportHandoffStatus(
            state = "waiting",
            deviceNoun = "phone",
            sizeBytes = 4096L,
        )
        send("""{"type":"host.status","item":"waiting-1"}""", contextText = null)
        assertEquals("waiting", replies.last()["state"])
        assertEquals("phone", replies.last()["device_noun"])
        assertEquals(BigDecimal(4096), replies.last()["size_bytes"])

        // import-host.js continues calm 2000ms polling on review_required === true.
        val script = File("src/main/assets/journal-web-host/import-host.js").readText()
        assertTrue(script.contains("status.review_required === true"))
        assertTrue(script.contains("2000"))
    }
}
