package io.github.khorkovenko.easysnippets

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
import kotlin.coroutines.coroutineContext

private val LOG = logger<SnippetRunner>()

private const val POLL_MS = 150L
private const val PROMPT_QUIET_MS = 600L
private const val UNFAMILIAR_PROMPT_QUIET_MS = 2_500L
private const val ECHO_WAIT_MS = 60_000
private const val PROMPT_ENDINGS = "$#>%❯»"

/** The line the cursor is on, and a number that changes whenever the terminal prints something. */
private class ScreenState(val cursorLine: String, val stamp: Long)

/**
 * Commands that start (or leave) an interactive shell or program, so the lines after them go to that program and the
 * local shell cannot report when each of them ends.
 */
private val OPENS_SHELL = Regex(
    """^(?:(?:sudo\s+(?:-\S+\s+(?:[^-\s]\S*\s+)?)*)?(?:su|bash|sh|zsh|fish|ksh|pwsh|powershell|cmd)(?:\.exe)?(?:\s+-\S*)*(?:\s+[\w.-]+)?$""" +
        """|sudo\s+(?:.*\s)?-[is]\b|ssh(?:\.exe)?\s|(?:exit|logout)\b|(?:docker|podman|kubectl)\s.*\b(?:exec|run|attach)\b""" +
        """|(?:psql|mysql|mariadb|sqlite3|redis-cli|mongo|mongosh|python|python3|node|irb|telnet|ftp|sftp|screen|tmux)\b)"""
)

/** Sends saved commands to the selected tab of the Terminal tool window (a new tab when there is none). */
@Service(Service.Level.APP)
class SnippetRunner(private val scope: CoroutineScope) {

    fun run(project: Project, items: List<SnippetItem>) {
        val selected = items.flatMap { it.snippets() }.distinctBy { it.id }
        // A paste-only command must never be run with Enter as a step of a longer run
        val snippets = if (selected.size > 1) selected.filterNot { it.pasteOnly } else selected
        val leftOut = selected.size - snippets.size
        if (leftOut > 0) {
            notify(project, "$leftOut paste-only command(s) were left out of the run. Run them on their own.", NotificationType.WARNING)
        }
        val commands = snippets.flatMap { it.steps() }
        if (commands.isEmpty()) return
        val pasteOnly = snippets.size == 1 && snippets[0].pasteOnly
        if (SnippetStore.getInstance().copyToClipboard) {
            CopyPasteManager.getInstance().setContents(StringSelection(commands.joinToString("\n")))
            notify(project, if (commands.size == 1) "Command copied. Paste it where you need it." else "${commands.size} commands copied, one per line.",
                NotificationType.INFORMATION)
            return
        }
        scope.launch(Dispatchers.EDT) {
            val terminal = TerminalTarget.resolve(project)
            if (terminal == null) {
                notify(project, "The Terminal tool window is not available in this project.", NotificationType.ERROR)
                return@launch
            }
            commands.mapNotNull { SshCommands.destinationOf(it) }.lastOrNull()?.let { host ->
                ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.contentManagerIfCreated?.selectedContent
                    ?.putUserData(SshCommands.SENT_TO_TAB, host)
            }
            try {
                when {
                    pasteOnly -> terminal.paste(commands.joinToString(" && "))
                    commands.size == 1 -> terminal.execute(commands[0])
                    else -> runSequence(project, terminal, commands)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Cannot send the command to the terminal", e)
                notify(project, "Cannot send the command to the terminal: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    private suspend fun runSequence(project: Project, terminal: TerminalTarget, commands: List<String>) {
        val stopOnFailure = SnippetStore.getInstance().stopOnFailure
        val integration = (terminal as? ReworkedTarget)?.awaitShellIntegration()
        // The local shell is busy running a program (ssh, for one), so the commands go to that program and the
        // local shell will not report when each of them ends
        val busy = integration != null && integration.outputStatus.value is TerminalOutputStatus.ExecutingCommand
        val opensShell = commands.dropLast(1).any { OPENS_SHELL.containsMatchIn(it.trim()) }
        if (terminal !is ReworkedTarget || integration == null || busy || opensShell) {
            runWaitingForPrompt(project, terminal, commands)
            return
        }
        withBackgroundProgress(project, "Easy Snippets: running ${commands.size} commands", true) {
            for ((index, command) in commands.withIndex()) {
                if (index == commands.lastIndex) {
                    terminal.execute(command) // nothing follows it: no need to wait, it may run for long
                    return@withBackgroundProgress
                }
                val exitCode = terminal.executeAndAwait(integration, command)
                if (stopOnFailure && exitCode != null && exitCode != 0) {
                    val skipped = commands.size - index - 1
                    if (skipped > 0) {
                        notify(project, "'$command' failed with exit code $exitCode. $skipped more command(s) were not run.",
                            NotificationType.WARNING)
                    }
                    return@withBackgroundProgress
                }
            }
        }
    }

    /**
     * For tabs where the shell cannot report the end of a command (SSH sessions, the classic terminal, a sequence
     * that goes through "sudo su" or "ssh host"): sends a command, then watches the screen and sends the next one
     * only when a prompt is back. While a command runs, or waits for input (a "read", a password), the cursor is
     * not on a prompt, so what the user types goes to that command and the sequence continues afterwards.
     */
    private suspend fun runWaitingForPrompt(project: Project, terminal: TerminalTarget, commands: List<String>) {
        val start = terminal.screenState()
        if (start == null) {
            // The screen cannot be read: send the lines ahead and let the terminal queue them
            for ((index, command) in commands.withIndex()) {
                if (index > 0) delay(300)
                terminal.execute(command)
            }
            return
        }
        val knownPrompt = start.cursorLine.trim().takeIf { it.isNotEmpty() }
        withBackgroundProgress(project, "Easy Snippets: running ${commands.size} commands, waiting for the prompt between them", true) {
            for ((index, command) in commands.withIndex()) {
                val before = terminal.screenState() ?: start
                terminal.execute(command)
                if (index < commands.lastIndex) awaitPrompt(terminal, before, command, knownPrompt)
            }
        }
    }

    private suspend fun awaitPrompt(terminal: TerminalTarget, before: ScreenState, command: String, knownPrompt: String?) {
        // First let the screen react to the command, so the prompt it was typed at is not taken for the next one
        var waited = 0
        var stamp = before.stamp
        while (waited < ECHO_WAIT_MS && stamp == before.stamp) {
            delay(POLL_MS)
            waited += POLL_MS.toInt()
            stamp = terminal.screenState()?.stamp ?: return
        }
        val commandTail = command.trim().takeLast(24)
        var quiet = 0L
        while (true) {
            delay(POLL_MS)
            val now = terminal.screenState() ?: return
            if (now.stamp != stamp) {
                stamp = now.stamp
                quiet = 0
                continue
            }
            quiet += POLL_MS
            val line = now.cursorLine.trim()
            // An empty line: the command is still running or reads input. "...:" or "...?": it asks a question
            if (line.isEmpty() || line.last() in ":?" || line.endsWith(commandTail)) continue
            // Anything else that is not a prompt may be text the user is typing for the command: never send into it
            if (quiet >= PROMPT_QUIET_MS && looksLikeKnownPrompt(line, knownPrompt)) return
            // A line that only ends like a prompt may also be a question ("name> ") or a stalled "45%": give it longer
            if (quiet >= UNFAMILIAR_PROMPT_QUIET_MS && line.last() in PROMPT_ENDINGS) return
        }
    }

    /** The prompt the sequence started at, or a line shaped like it ("host site ~" and "host site /some/dir"). */
    private fun looksLikeKnownPrompt(line: String, knownPrompt: String?): Boolean {
        if (knownPrompt == null) return false
        if (line == knownPrompt) return true
        val tokens = line.split(Regex("""\s+"""))
        val known = knownPrompt.split(Regex("""\s+"""))
        return tokens.size == known.size &&
            (tokens.first() == known.first() || tokens.last().startsWith("~") || tokens.last().startsWith("/"))
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Easy Snippets")
            .createNotification("Easy Snippets", text, type).notify(project)
    }

    companion object {
        fun getInstance(): SnippetRunner = service()
    }
}

/** Everything a terminal tab has printed so far, or null when the tab cannot be read. */
fun readTerminalText(project: Project, content: Content): CharSequence? {
    try {
        val tab = TerminalToolWindowTabsManager.getInstance(project).tabs.firstOrNull { it.content === content }
        if (tab != null) {
            val model = tab.view.outputModels.regular
            return model.getText(model.startOffset, model.endOffset)
        }
    } catch (e: LinkageError) {
        LOG.info("Reworked terminal API is not available, using the classic one", e)
    } catch (e: Exception) {
        LOG.info("Cannot read the terminal text", e)
    }
    return try {
        val buffer = TerminalToolWindowManager.getWidgetByContent(content)?.terminalTextBuffer ?: return null
        buffer.lock()
        try {
            (-buffer.historyLinesCount until buffer.screenLinesCount).joinToString("\n") { buffer.getLine(it).text }
        } finally {
            buffer.unlock()
        }
    } catch (e: Exception) {
        LOG.info("Cannot read the terminal text", e)
        null
    }
}

private sealed interface TerminalTarget {
    fun execute(command: String)
    fun paste(text: String)

    /** Null when the tab's screen cannot be read. */
    fun screenState(): ScreenState?

    companion object {
        fun resolve(project: Project): TerminalTarget? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
            val existing = toolWindow.contentManagerIfCreated?.selectedContent?.let { find(project, it) }
            val target = existing ?: create(project, toolWindow)
            toolWindow.activate(null)
            return target
        }

        private fun find(project: Project, content: Content): TerminalTarget? =
            findReworked(project, content) ?: TerminalToolWindowManager.findWidgetByContent(content)?.let { ClassicTarget(it) }

        private fun findReworked(project: Project, content: Content): TerminalTarget? = try {
            TerminalToolWindowTabsManager.getInstance(project).tabs.firstOrNull { it.content === content }
                ?.let { ReworkedTarget(it.view, justCreated = false) }
        } catch (e: LinkageError) {
            LOG.info("Reworked terminal API is not available, using the classic one", e)
            null
        }

        /** A new tab of the engine chosen in Settings | Tools | Terminal. */
        private fun create(project: Project, toolWindow: ToolWindow): TerminalTarget {
            if (TerminalOptionsProvider.instance.terminalEngine == TerminalEngine.REWORKED) {
                try {
                    val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder().requestFocus(true).createTab()
                    return ReworkedTarget(tab.view, justCreated = true)
                } catch (e: LinkageError) {
                    LOG.info("Reworked terminal API is not available, using the classic one", e)
                }
            }
            val widget = TerminalToolWindowManager.getInstance(project).createNewSession()
            val reworked = toolWindow.contentManagerIfCreated?.selectedContent?.let { findReworked(project, it) }
            return (reworked as? ReworkedTarget)?.let { ReworkedTarget(it.view, justCreated = true) } ?: ClassicTarget(widget)
        }
    }
}

/** A tab of the classic terminal, or any other tab that only offers the old widget API. */
private class ClassicTarget(private val widget: TerminalWidget) : TerminalTarget {
    override fun execute(command: String) = widget.sendCommandToExecute(command)

    override fun paste(text: String) {
        // Typed as soon as the shell is connected; never executed, also in a tab that is still starting
        widget.ttyConnectorAccessor.executeWithTtyConnector { it.write(text) }
    }

    override fun screenState(): ScreenState? = try {
        val jediTerm = JBTerminalWidget.asJediTermWidget(widget)
        if (jediTerm == null) null else {
            val buffer = jediTerm.terminalTextBuffer
            buffer.lock()
            try {
                val screen = buffer.getScreenLines()
                val cursorRow = (jediTerm.terminal.cursorY - 1).coerceIn(0, maxOf(0, buffer.screenLinesCount - 1))
                ScreenState(buffer.getLine(cursorRow).text, screen.hashCode().toLong() * 31 + buffer.historyLinesCount)
            } finally {
                buffer.unlock()
            }
        }
    } catch (e: Exception) {
        LOG.info("Cannot read the terminal screen", e)
        null
    }
}

/** A tab of the reworked (new) terminal. */
private class ReworkedTarget(val view: TerminalView, private val justCreated: Boolean) : TerminalTarget {

    override fun execute(command: String) = view.createSendTextBuilder().shouldExecute().send(command)

    override fun paste(text: String) = view.sendText(text)

    override fun screenState(): ScreenState? = try {
        val models = view.outputModels
        val model = models.active.value
        if (model !== models.regular) {
            ScreenState("", model.modificationStamp) // a full-screen program (less, vim) is open: not at a prompt
        } else {
            val line = model.getLineByOffset(model.cursorOffset)
            ScreenState(model.getText(model.getStartOfLine(line), model.getEndOfLine(line, false)).toString(), model.modificationStamp)
        }
    } catch (e: Exception) {
        LOG.info("Cannot read the terminal screen", e)
        null
    }

    /** Null when the shell does not report its commands (remote shell, integration turned off). */
    suspend fun awaitShellIntegration(): TerminalShellIntegration? = try {
        // A new tab needs time to start the shell; an existing tab either has the integration already or never will
        withTimeoutOrNull(if (justCreated) 10_000L else 1_000L) { view.shellIntegrationDeferred.await() }
    } catch (e: Exception) {
        coroutineContext.ensureActive()
        null
    }

    /** Runs [command] and waits until the shell reports that it has finished; returns its exit code. */
    suspend fun executeAndAwait(integration: TerminalShellIntegration, command: String): Int? {
        val finished = CompletableDeferred<Int?>()
        val listenerLifetime = Disposer.newDisposable("Easy Snippets command")
        val tabClosed = view.coroutineScope.coroutineContext.job.invokeOnCompletion { finished.cancel() }
        try {
            integration.addCommandExecutionListener(listenerLifetime, object : TerminalCommandExecutionListener {
                override fun commandFinished(event: TerminalCommandFinishedEvent) {
                    finished.complete(event.commandBlock.exitCode)
                }
            })
            execute(command)
            return finished.await()
        } finally {
            tabClosed.dispose()
            Disposer.dispose(listenerLifetime)
        }
    }
}
