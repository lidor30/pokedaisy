package com.pokedaisy.app.companion

import com.pokedaisy.app.GameSaves
import kotlin.test.*

class SaveImportFlowTest {
    @Test fun staleReadsAndErrorsCannotReplaceANewerGamesConfirmation() {
        val flow = SaveImportFlow<String>(GameSaves.MAX_BYTES)
        val old = flow.begin("old.gba")!!
        val current = flow.begin("current.gb")!!
        flow.ready(current, byteArrayOf(1, 2), "current.sav")
        val pending = flow.pending!!
        assertNull(flow.ready(old, null, "failed.sav"))
        flow.ready(old, byteArrayOf(3), "old.sav")
        flow.ready(current, byteArrayOf(4), "duplicate.sav")
        assertSame(pending, flow.pending)
        assertEquals("current.gb", pending.target)
        assertContentEquals(byteArrayOf(1, 2), pending.bytes)
    }

    @Test fun onlyBoundedReadableBytesReachConfirmation() {
        val flow = SaveImportFlow<Int>(GameSaves.MAX_BYTES)
        for (bytes in listOf(null, byteArrayOf())) {
            val pick = flow.begin(1)!!
            assertEquals(SaveImportFlow.Problem("empty", SaveImportFlow.Failure.UNREADABLE), flow.ready(pick, bytes, "empty"))
            assertNull(flow.pending)
        }
        assertEquals(SaveImportFlow.Failure.TOO_LARGE,
            flow.ready(flow.begin(1)!!, ByteArray(GameSaves.MAX_BYTES + 1), "large")?.failure)
        flow.ready(flow.begin(2)!!, ByteArray(GameSaves.MAX_BYTES), "bounded")
        assertEquals(2, flow.pending?.target)
        assertEquals(GameSaves.MAX_BYTES, flow.pending?.bytes?.size)
    }

    @Test fun cancelledOrReplacedDialogsCannotConfirmOrDismissTheCurrentOne() {
        val flow = SaveImportFlow<String>(GameSaves.MAX_BYTES)
        val cancelled = flow.begin("a")!!
        flow.cancel(cancelled); flow.ready(cancelled, byteArrayOf(1), "a.sav")
        assertNull(flow.pending)
        flow.ready(flow.begin("b")!!, byteArrayOf(2), "b.sav")
        val old = flow.pending!!
        flow.ready(flow.begin("c")!!, byteArrayOf(3), "c.sav")
        val current = flow.pending!!
        flow.dismiss(old); assertNull(flow.confirm(old))
        assertSame(current, flow.pending)
        flow.dismiss(current); assertNull(flow.pending)
        assertNull(flow.confirm(current))
    }

    @Test fun confirmationClaimsExactlyOneTransactionUntilItFinishes() {
        val flow = SaveImportFlow<String>(GameSaves.MAX_BYTES)
        flow.ready(flow.begin("a")!!, byteArrayOf(1), "a.sav")
        val candidate = flow.pending!!
        assertSame(candidate, flow.confirm(candidate))
        assertNull(flow.confirm(candidate)); assertNull(flow.pending)
        assertSame(candidate, flow.importing)
        assertNull(flow.begin("b"))
        assertTrue(flow.finished(candidate)); assertNull(flow.importing)
        assertFalse(flow.finished(candidate))
        assertNotNull(flow.begin("b"))
    }

    @Test fun retirementRejectsLateReadsAndCompletionWithoutCancellingTheAcceptedTransaction() {
        val flow = SaveImportFlow<String>(GameSaves.MAX_BYTES)
        flow.ready(flow.begin("a")!!, byteArrayOf(1), "a.sav")
        val transaction = flow.confirm(flow.pending!!)!!
        flow.close()
        assertFalse(flow.finished(transaction)); assertNull(flow.begin("b"))
        assertContentEquals(byteArrayOf(1), transaction.bytes)
        val reading = SaveImportFlow<String>(GameSaves.MAX_BYTES)
        val request = reading.begin("c")!!
        reading.close(); reading.ready(request, byteArrayOf(2), "c.sav")
        assertNull(reading.pending)
    }
}
