package io.github.khorkovenko.easysnippets

import com.intellij.openapi.util.Key
import java.util.concurrent.CopyOnWriteArrayList

/** Where an SSH connection goes: taken from a tab title "user@host:port" or from a typed "ssh -p 4311 user@host". */
data class HostAddress(val user: String?, val host: String, val port: Int?) {
    companion object {
        private val TITLE = Regex("""^([^@\s]+)@([A-Za-z0-9._\-]+)(?::(\d+))?(?:\s.*)?$""")

        /** The title IntelliJ gives a Start SSH Session tab. */
        fun fromTitle(title: String?): HostAddress? {
            val match = TITLE.matchEntire(title?.trim().orEmpty()) ?: return null
            return HostAddress(match.groupValues[1], match.groupValues[2], match.groupValues[3].toIntOrNull())
        }
    }
}

/** Work that optional parts of the plugin need done before the plugin is unloaded without a restart. */
object UnloadHooks {
    val hooks = CopyOnWriteArrayList<() -> Unit>()
}

/** Finds the host of an ssh command that was typed, or sent by a snippet, in a terminal tab. */
object SshCommands {
    /** The host of the last ssh command Easy Snippets itself sent to a tab; used when the tab's text is gone. */
    val SENT_TO_TAB: Key<HostAddress> = Key.create("EasySnippets.sshHost")

    private val COMMAND = Regex("""(?:^|[\s>$#%;&|(])ssh(?:\.exe)?\s+(\S.*)$""")
    private val CLOSED = Regex("""^(Connection to \S+ closed|logout\s*$)""")
    private val DESTINATION = Regex("""^(?:ssh://)?(?:([^@\s/]+)@)?([A-Za-z0-9._\-]+)(?::(\d+))?/?$""")

    /** ssh options that are followed by a value. */
    private const val OPTIONS_WITH_VALUE = "BbcDEeFIiJLlmOoPpQRSWw"

    /** The destination of "ssh ..." when [command] is such a command. */
    fun destinationOf(command: String): HostAddress? =
        COMMAND.find(command.trim())?.let { parseArguments(it.groupValues[1]) }

    /**
     * The host the tab is connected to, judging by its [text]: the last ssh command that [accept]s, unless the
     * connection was closed after it.
     */
    fun lastDestination(text: CharSequence?, accept: (HostAddress) -> Boolean): HostAddress? {
        var current: HostAddress? = null
        for (line in text?.lineSequence().orEmpty()) {
            val trimmed = line.trim()
            if (CLOSED.containsMatchIn(trimmed)) {
                current = null
                continue
            }
            val address = COMMAND.find(trimmed)?.let { parseArguments(it.groupValues[1]) } ?: continue
            if (accept(address)) current = address
        }
        return current
    }

    /** True when the last thing [text] says about SSH is that a connection was closed. */
    fun endsDisconnected(text: CharSequence?): Boolean {
        var disconnected = false
        for (line in text?.lineSequence().orEmpty()) {
            val trimmed = line.trim()
            when {
                CLOSED.containsMatchIn(trimmed) -> disconnected = true
                COMMAND.find(trimmed)?.let { parseArguments(it.groupValues[1]) } != null -> disconnected = false
            }
        }
        return disconnected
    }

    /** Splits on spaces, keeping quoted parts ("ssh -W %h:%p jump") together and dropping the quotes. */
    private fun tokenize(arguments: String): List<String> {
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        for (char in arguments.trim()) {
            when {
                quote != null -> if (char == quote) quote = null else token.append(char)
                char == '"' || char == '\'' -> {
                    quote = char
                    started = true
                }
                char.isWhitespace() -> {
                    if (started || token.isNotEmpty()) tokens += token.toString()
                    token.setLength(0)
                    started = false
                }
                else -> token.append(char)
            }
        }
        if (started || token.isNotEmpty()) tokens += token.toString()
        return tokens
    }

    private fun parseArguments(arguments: String): HostAddress? {
        val tokens = tokenize(arguments)
        var port: Int? = null
        var user: String? = null
        var destination: String? = null
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.length >= 2 && token.startsWith("-")) {
                // Flags may be bundled ("-vp 2222"): the first one that takes a value ends the bundle
                val valueFlagAt = (1 until token.length).firstOrNull { token[it] in OPTIONS_WITH_VALUE }
                if (valueFlagAt != null) {
                    val option = token[valueFlagAt]
                    val value = if (valueFlagAt < token.length - 1) token.substring(valueFlagAt + 1) else tokens.getOrNull(++index)
                    if (option == 'p') port = value?.toIntOrNull()
                    if (option == 'l') user = value
                }
            } else if (destination == null) {
                destination = token
            } else {
                break // the rest is the command to run on the host
            }
            index++
        }
        val match = DESTINATION.matchEntire(destination ?: return null) ?: return null
        return HostAddress(
            match.groupValues[1].ifEmpty { user },
            match.groupValues[2],
            match.groupValues[3].toIntOrNull() ?: port,
        )
    }
}
