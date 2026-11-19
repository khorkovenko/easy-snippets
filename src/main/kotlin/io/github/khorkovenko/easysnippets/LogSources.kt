package io.github.khorkovenko.easysnippets

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag

/** A log the user saved in the Log Watcher with the + button: a name and what to follow. */
@Tag("log")
class SavedLog {
    @get:Attribute var name: String = ""
    /** A file path, a command that views a file ("less /path"), or any command that prints a log. */
    @get:Attribute var target: String = ""
    @get:Attribute var sudo: Boolean = false

    override fun toString(): String = if (name.isBlank() || name == target) target else "$name - $target"
}

/**
 * What the Log field asks for. A [file] is followed with "tail -F"; anything else is a [command] that is run as it
 * is and whose output is shown (journalctl -f, docker logs -f ...).
 */
class LogTarget(val file: String?, val command: String?, val sudo: Boolean) {
    val title: String get() = file?.substringAfterLast('/') ?: command.orEmpty().take(28)
    val description: String get() = file ?: command.orEmpty()

    companion object {
        /** "less /var/log/messages", "sudo tail -f /x/y.log", "cat /x/y": a viewer, optional flags, one absolute path. */
        private val VIEWER = Regex("""^(sudo\s+)?(?:less|more|tail|head|cat|zless|zcat|view|vi|vim|nano)\b(?:\s+-\S+(?:\s+\d+)?)*\s+(/[^\s|;&<>]+)$""")
        private val SUDO = Regex("""^sudo\s+(?:-n\s+)?""")

        fun parse(text: String, sudo: Boolean): LogTarget? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("/") && trimmed.none { it.isWhitespace() }) return LogTarget(trimmed, null, sudo)
            VIEWER.matchEntire(trimmed)?.let { return LogTarget(it.groupValues[2], null, sudo || it.groupValues[1].isNotEmpty()) }
            // Any other command; a leading "sudo" becomes the sudo option, so it gets the terminal sudo needs
            val withoutSudo = trimmed.replaceFirst(SUDO, "")
            return LogTarget(null, withoutSudo, sudo || withoutSudo != trimmed)
        }

        fun startsWithSudo(text: String): Boolean = SUDO.containsMatchIn(text.trim())
    }
}

/** The Log Watcher tab of the tool window, as seen from the parts of the plugin that work without SSH support. */
interface LogWatcherTab {
    /** Puts [command] into the Log field and starts watching when the host it was last watched on is known. */
    fun watch(command: String)
}

const val LOG_WATCHER_TAB = "Log Watcher"

/** The Log Watcher and the host file tree need the IDE's SSH support, which comes with these two bundled plugins. */
val hostFeaturesAvailable: Boolean by lazy {
    listOf("com.jetbrains.plugins.webDeployment", "org.jetbrains.plugins.remote-run")
        .all { PluginManager.getInstance().findEnabledPlugin(PluginId.getId(it)) != null }
}

/** Opens the Log Watcher tab of the Easy Snippets tool window, on [command] when there is one. */
fun showLogWatcher(project: Project, command: String?) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
    toolWindow.activate {
        val contents = toolWindow.contentManager
        val content = contents.contents.firstOrNull { it.component is LogWatcherTab } ?: return@activate
        contents.setSelectedContent(content, true)
        if (command != null) (content.component as LogWatcherTab).watch(command)
    }
}

/**
 * Stops a process that runs on a host. Ending an SSH process waits for the connection, which the IDE forbids on
 * the UI thread, so it is always done on a background thread.
 */
fun destroyInBackground(process: Process?) {
    if (process == null) return
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            process.destroy()
        } catch (e: Exception) {
            Logger.getInstance("EasySnippets").info("Cannot stop a remote process", e)
        }
    }
}

/**
 * An scp command that copies a remote file or folder into the current folder of a local terminal. Single quotes
 * keep "$", spaces and backticks literal; PowerShell on Windows doubles a quote inside them, other shells close
 * and reopen.
 */
fun scpDownloadCommand(user: String?, host: String, port: Int, path: String, recursive: Boolean): String {
    val target = (user?.takeIf { it.isNotEmpty() }?.let { "$it@" }.orEmpty()) + host + ":" + path
    val quoted = "'" + target.replace("'", if (SystemInfo.isWindows) "''" else "'\\''") + "'"
    val portOption = if (port > 0 && port != 22) "-P $port " else ""
    return "scp $portOption${if (recursive) "-r " else ""}$quoted ."
}
