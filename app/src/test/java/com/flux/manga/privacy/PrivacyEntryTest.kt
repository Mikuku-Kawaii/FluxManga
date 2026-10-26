package com.flux.manga.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyEntryTest {
    @Test fun authorizationIsSingleUse() {
        val gate = AuthTickets(); val ticket = gate.begin("public:g1")
        assertTrue(gate.consume(ticket, "public:g1", true))
        assertFalse(gate.consume(ticket, "public:g1", true))
    }
    @Test fun changedWorldOrGenerationCannotAcceptCallback() {
        val gate = AuthTickets(); val ticket = gate.begin("public:g1")
        assertFalse(gate.consume(ticket, "private:g1", true))
        assertFalse(gate.consume(ticket, "public:g2", true))
    }
    @Test fun backgroundCallbackCannotAuthorize() {
        val gate = AuthTickets(); val ticket = gate.begin("public:g1")
        assertFalse(gate.consume(ticket, "public:g1", false))
        gate.cancel()
        assertFalse(gate.consume(ticket, "public:g1", true))
    }
    @Test fun cancellationRejectsLateSuccessAfterResume() {
        val gate = AuthTickets(); val previous = gate.begin("public:g1")
        gate.cancel(); val next = gate.begin("public:g1")
        assertFalse(gate.consume(previous, "public:g1", true))
        assertTrue(gate.consume(next, "public:g1", true))
    }
    @Test fun mismatchDoesNotConsumeTicketButTerminalFailureDoes() {
        val gate = AuthTickets(); val ticket = gate.begin("public:g1")
        assertTrue(gate.isCurrent(ticket, "public:g1", true))
        assertTrue(gate.isCurrent(ticket, "public:g1", true))
        assertTrue(gate.consume(ticket, "public:g1", true))
        assertFalse(gate.isCurrent(ticket, "public:g1", true))
    }
    @Test fun repeatedRequestReplacesPriorTicket() {
        val gate = AuthTickets(); val first = gate.begin("public:g1"); val second = gate.begin("public:g1")
        assertFalse(gate.consume(first, "public:g1", true))
        assertTrue(gate.consume(second, "public:g1", true))
    }
    @Test fun legacyAliasAlwaysHasAnEnabledEntry() {
        for (target in LauncherStyle.entries) {
            val enabled = mutableSetOf(LauncherStyle.STANDARD)
            applyLauncherSelection(target) { item, on ->
                if (on) enabled.add(item) else enabled.remove(item)
                assertTrue(enabled.isNotEmpty())
            }
            assertEquals(setOf(target), enabled)
        }
    }
    @Test fun failedEnableKeepsOriginalEntry() {
        val enabled = mutableSetOf(LauncherStyle.STANDARD)
        runCatching { applyLauncherSelection(LauncherStyle.NOTES) { _, _ -> error("PackageManager failure") } }
        assertEquals(setOf(LauncherStyle.STANDARD), enabled)
    }
    @Test fun failedDisableLeavesTargetEnabledAndCanRetry() {
        val enabled = mutableSetOf(LauncherStyle.STANDARD)
        runCatching { applyLauncherSelection(LauncherStyle.NOTES) { item, on ->
            if (on) enabled.add(item) else error("PackageManager failure")
        } }
        assertTrue(LauncherStyle.NOTES in enabled)
        applyLauncherSelection(LauncherStyle.STANDARD) { item, on -> if (on) enabled.add(item) else enabled.remove(item) }
        assertEquals(setOf(LauncherStyle.STANDARD), enabled)
    }
}
