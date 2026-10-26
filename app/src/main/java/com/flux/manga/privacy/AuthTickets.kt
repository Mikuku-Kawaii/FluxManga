package com.flux.manga.privacy

/** Activity-local, single-use authorization. Never serialized or retained across hosts. */
internal class AuthTickets {
    data class Ticket(val sequence: Long, val session: String)
    private var sequence = 0L
    private var active: Ticket? = null
    fun begin(session: String): Ticket = Ticket(++sequence, session).also { active = it }
    fun isCurrent(ticket: Ticket, session: String, foreground: Boolean) =
        foreground && active == ticket && ticket.session == session
    fun consume(ticket: Ticket, session: String, foreground: Boolean): Boolean {
        if (!isCurrent(ticket, session, foreground)) return false
        active = null
        return true
    }
    fun cancel() { active = null }
}

internal enum class LauncherStyle(val title: String, val alias: String) {
    STANDARD("FluxManga", "DefaultLauncher"), NOTES("随手记", "NotesLauncher")
}

/** Older Android versions must enable the target before disabling any other entry. */
internal fun applyLauncherSelection(target: LauncherStyle, setEnabled: (LauncherStyle, Boolean) -> Unit) {
    setEnabled(target, true)
    LauncherStyle.entries.filter { it != target }.forEach { setEnabled(it, false) }
}
