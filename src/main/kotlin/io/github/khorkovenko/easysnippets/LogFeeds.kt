package io.github.khorkovenko.easysnippets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.remote.RemoteCredentials
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch

/** Where the lines of a log session come from. A feed is used for one run; a restart makes a new one. */
interface LogFeed {
    /**
     * Reads until the source ends or [stop] is called, handing over text to append as it is (whole lines with their
     * line break, or bigger pieces of a file). Runs on a background thread and may throw.
     */
    fun run(onStatus: (String) -> Unit, onText: (String) -> Unit)

    fun stop()
}

/** A command on a host over the IDE's SSH connection: "tail -F" for a file, or the command as it is. */
class SshLogFeed(
    private val project: Project,
    private val credentials: RemoteCredentials,
    private val hostLabel: String,
    private val target: LogTarget,
    private val wholeFile: Boolean,
) : LogFeed {
    @Volatile private var process: Process? = null
    @Volatile private var stopped = false

    /** Set while reading: what to tell the user when the session ends because of it. */
    @Volatile var hint: String = ""
        private set

    override fun run(onStatus: (String) -> Unit, onText: (String) -> Unit) {
        val file = target.file
        val quoted = "'" + file.orEmpty().replace("'", "'\\''") + "'"
        val what = when {
            file == null -> target.command.orEmpty()
            wholeFile -> "cat $quoted" // the file as it is now; it is not followed
            else -> "tail -n $TAIL_LINES -F $quoted"
        }
        val command = (if (target.sudo) "sudo -n " else "") + what + " 2>&1"
        // sudo gets a terminal: many hosts refuse to run it without one ("you must have a tty to run sudo")
        val started = SshConnections.exec(credentials, project, command, target.sudo)
        if (stopped) {
            started.destroy()
            return
        }
        process = started
        if (file != null && wholeFile) {
            onStatus("Reading the whole of ${target.description} on $hostLabel ...")
            // Big pieces instead of lines: a large file arrives many times faster this way
            started.inputStream.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(1 shl 16)
                while (!stopped) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val piece = String(buffer, 0, count).replace("\r", "")
                    if (!target.sudo && "Permission denied" in piece) hint = "Tick sudo and press Watch again to read this."
                    onText(piece)
                }
            }
            return
        }
        val scope = if (file == null) "" else " (last $TAIL_LINES lines, then live)"
        onStatus("Following ${target.description} on $hostLabel$scope")
        val onLine = { line: String -> onText(line + "\n") }
        started.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (!stopped) {
                val line = (reader.readLine() ?: break).trimEnd('\r')
                when {
                    !target.sudo && "Permission denied" in line -> hint = "Tick sudo and press Watch again to read this."
                    target.sudo && "a password is required" in line -> hint = "sudo on this host asks for a password, so the plugin cannot use it here."
                }
                onLine(line)
            }
        }
    }

    override fun stop() {
        stopped = true
        destroyInBackground(process)
    }

    companion object {
        const val TAIL_LINES = 5000
    }
}

/**
 * The console of a web page, read through the debugging connection of Chrome or Edge. When no browser is
 * listening on the debugging port, one is started with a profile of its own (a normally started browser does not
 * accept debugging connections) and the page is opened in it.
 */
class BrowserLogFeed(private val url: String) : LogFeed {
    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(3)).build()
    private val done = CountDownLatch(1)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false

    override fun run(onStatus: (String) -> Unit, onText: (String) -> Unit) {
        val onLine = { line: String -> onText(line + "\n") }
        if (pages() == null) {
            onStatus("Starting a browser for $url ...")
            startBrowser()
            val deadline = System.currentTimeMillis() + 15_000
            while (pages() == null) {
                if (stopped) return
                if (System.currentTimeMillis() > deadline) error("The browser did not open its debugging port $PORT.")
                Thread.sleep(300)
            }
        }
        val page = findPage() ?: openPage() ?: error("Cannot open $url in the browser.")
        val address = page.get("webSocketDebuggerUrl")?.asString ?: error("The page is already being debugged by another tool.")
        val connection = http.newWebSocketBuilder().buildAsync(URI(address), Listener(onLine)).join()
        socket = connection
        if (stopped) {
            connection.abort()
            return
        }
        listOf("Runtime.enable", "Log.enable").forEachIndexed { index, method ->
            connection.sendText("""{"id":${index + 1},"method":"$method"}""", true).join()
        }
        onStatus("Following the browser console of $url (reload the page to see what it logs while loading)")
        done.await()
    }

    override fun stop() {
        stopped = true
        socket?.abort()
        done.countDown()
    }

    /** The open pages of the browser listening on the debugging port, or null when there is none. */
    private fun pages(): JsonArray? = try {
        val response = http.send(HttpRequest.newBuilder(URI("$ENDPOINT/json/list")).timeout(Duration.ofSeconds(3)).build(),
            HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) JsonParser.parseString(response.body()).asJsonArray else null
    } catch (e: Exception) {
        null
    }

    private fun findPage(): JsonObject? = pages()?.map { it.asJsonObject }
        ?.firstOrNull { it.get("type")?.asString == "page" && it.get("url")?.asString.orEmpty().startsWith(url.trimEnd('/')) }

    private fun openPage(): JsonObject? = try {
        val request = HttpRequest.newBuilder(URI("$ENDPOINT/json/new?" + URLEncoder.encode(url, Charsets.UTF_8)))
            .timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.noBody()).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) JsonParser.parseString(response.body()).asJsonObject else null
    } catch (e: Exception) {
        null
    }

    private fun startBrowser() {
        val browser = findBrowser() ?: error("Neither Chrome nor Edge was found on this computer.")
        val profile = File(PathManager.getSystemPath(), "easy-snippets-browser")
        GeneralCommandLine(browser, "--remote-debugging-port=$PORT", "--user-data-dir=${profile.absolutePath}",
            "--no-first-run", "--no-default-browser-check", url).createProcess()
    }

    private fun findBrowser(): String? {
        val candidates = when {
            SystemInfo.isWindows -> listOf("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA").mapNotNull { System.getenv(it) }.flatMap {
                listOf("$it\\Google\\Chrome\\Application\\chrome.exe", "$it\\Microsoft\\Edge\\Application\\msedge.exe")
            }
            SystemInfo.isMac -> listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
            )
            else -> listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge")
                .mapNotNull { PathEnvironmentVariableUtil.findInPath(it)?.absolutePath }
        }
        return candidates.firstOrNull { File(it).isFile }
    }

    /** Turns the browser's console, exception and log events into lines "time LEVEL text". */
    private inner class Listener(private val onLine: (String) -> Unit) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val message = buffer.toString()
                buffer.setLength(0)
                try {
                    handle(JsonParser.parseString(message).asJsonObject)
                } catch (e: Exception) {
                    onLine("${now()} WARN  Easy Snippets could not read a browser message: ${e.message}")
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            done.countDown()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (!stopped) onLine("${now()} ERROR The connection to the browser was lost: ${error.message}")
            done.countDown()
        }

        private fun handle(message: JsonObject) {
            val params = message.getAsJsonObject("params") ?: return
            when (message.get("method")?.asString) {
                "Runtime.consoleAPICalled" -> {
                    val level = when (val type = params.get("type")?.asString.orEmpty()) {
                        "error", "assert" -> "ERROR"
                        "warning" -> "WARN "
                        "info" -> "INFO "
                        "debug", "trace" -> "DEBUG"
                        else -> type.uppercase().padEnd(5).take(5)
                    }
                    val text = params.getAsJsonArray("args")?.joinToString(" ") { argument(it.asJsonObject) }.orEmpty()
                    emit(level, text)
                }
                "Runtime.exceptionThrown" -> {
                    val details = params.getAsJsonObject("exceptionDetails") ?: return
                    val description = details.getAsJsonObject("exception")?.get("description")?.asString
                    emit("ERROR", description ?: details.get("text")?.asString.orEmpty())
                }
                "Log.entryAdded" -> {
                    val entry = params.getAsJsonObject("entry") ?: return
                    val level = when (entry.get("level")?.asString) {
                        "error" -> "ERROR"
                        "warning" -> "WARN "
                        "verbose" -> "DEBUG"
                        else -> "INFO "
                    }
                    val where = entry.get("url")?.asString?.takeIf { it.isNotEmpty() }?.let { "  ($it)" }.orEmpty()
                    emit(level, "[${entry.get("source")?.asString.orEmpty()}] ${entry.get("text")?.asString.orEmpty()}$where")
                }
            }
        }

        private fun argument(value: JsonObject): String {
            value.get("value")?.takeIf { !it.isJsonNull }?.let { return if (it.isJsonPrimitive) it.asString else it.toString() }
            return value.get("description")?.asString ?: value.get("unserializableValue")?.asString ?: value.get("type")?.asString.orEmpty()
        }

        private fun emit(level: String, text: String) {
            val lines = text.lines()
            onLine("${now()} $level ${lines.first()}")
            lines.drop(1).forEach { onLine(it) } // stack trace lines keep the colour of the first one
        }

        private fun now(): String = LocalTime.now().format(TIME)
    }

    companion object {
        // Not the usual 9222: other programs (vendor tools with an embedded browser) already listen there
        private const val PORT = 9339
        private const val ENDPOINT = "http://127.0.0.1:$PORT"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val ADDRESS = Regex("""^(?:(https?)://)?((?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?:/\S*)?)$""")
        private val ANY_URL = Regex("""^https?://\S+$""")

        /** "localhost:3000", "http://127.0.0.1:8080/app" or any http(s) address: the page to follow, or null. */
        fun urlOf(text: String): String? {
            val trimmed = text.trim()
            ADDRESS.matchEntire(trimmed)?.let { return (it.groupValues[1].ifEmpty { "http" }) + "://" + it.groupValues[2] }
            return trimmed.takeIf { ANY_URL.matches(it) }
        }
    }
}
