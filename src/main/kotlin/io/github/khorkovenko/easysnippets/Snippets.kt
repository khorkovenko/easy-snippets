package io.github.khorkovenko.easysnippets

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A saved command ("description - command") or a folder of them. A command with several lines is a sequence:
 * its lines run one after another.
 */
@Tag("item")
class SnippetItem {
    @get:Attribute var id: String = UUID.randomUUID().toString()
    @get:Attribute var folder: Boolean = false
    @get:Attribute var name: String = ""
    /** Type the command into the prompt without pressing Enter. */
    @get:Attribute var pasteOnly: Boolean = false
    /** Listed in the popup opened by the shortcut. */
    @get:Attribute var favorite: Boolean = false
    /** Registered as an action of its own, so it can have a shortcut in the keymap. */
    @get:Attribute var hotkey: Boolean = false
    @get:Tag var command: String = ""
    @get:XCollection(style = XCollection.Style.v2) var children: MutableList<SnippetItem> = mutableListOf()
}

fun newSnippet(name: String, command: String, pasteOnly: Boolean = false) = SnippetItem().also {
    it.name = name
    it.command = command
    it.pasteOnly = pasteOnly
}

fun newFolder(name: String, children: List<SnippetItem> = emptyList()) = SnippetItem().also {
    it.folder = true
    it.name = name
    it.children = children.toMutableList()
}

/** The command lines this item runs, in order. */
fun SnippetItem.steps(): List<String> =
    if (folder) children.flatMap { it.steps() } else command.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** The commands of this item: itself, or everything inside the folder in tree order. */
fun SnippetItem.snippets(): List<SnippetItem> = if (folder) children.flatMap { it.snippets() } else listOf(this)

fun SnippetItem.commandPreview(): String = steps().joinToString("  ⏎  ")

/** "description - command", as shown in the popup and the tree. */
fun SnippetItem.label(): String {
    if (folder) return name
    val preview = commandPreview()
    return if (name.isBlank() || name == preview) preview else "$name - $preview"
}

@Service(Service.Level.APP)
@State(name = "EasySnippets", storages = [Storage("easySnippets.xml")])
class SnippetStore : PersistentStateComponent<SnippetStore.State> {

    class State {
        @get:XCollection(style = XCollection.Style.v2) var items: MutableList<SnippetItem> = mutableListOf()
        var stopOnFailure: Boolean = true
        /** Always true once saved, so an emptied list is still written to disk and the samples do not come back. */
        var seeded: Boolean = false
        /** Run copies the command to the clipboard instead of sending it to the terminal. */
        var copyToClipboard: Boolean = false
        /** The search field of the host file tree: react to typing at all, and search the host or only opened folders. */
        var hostSearchEnabled: Boolean = true
        var hostDeepSearch: Boolean = true
        /** Log Watcher: the SSH configuration each log was last watched on, by snippet id or path. */
        var logHosts: MutableMap<String, String> = HashMap()
        /** Log Watcher: the logs saved with its + button, the sudo box and the host used last. */
        @get:XCollection(style = XCollection.Style.v2) var savedLogs: MutableList<SavedLog> = mutableListOf()
        var logSudo: Boolean = true
        var lastLogHost: String = ""
        /** Log Watcher: what was watched recently, newest first. */
        @get:XCollection(style = XCollection.Style.v2) var logHistory: MutableList<String> = mutableListOf()
    }

    private var state = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state.also { it.seeded = true }
    }

    override fun noStateLoaded() {
        state = State().also {
            it.items = sampleItems()
            it.seeded = true
        }
    }

    val items: MutableList<SnippetItem> get() = state.items

    var stopOnFailure: Boolean
        get() = state.stopOnFailure
        set(value) {
            state.stopOnFailure = value
        }

    var copyToClipboard: Boolean
        get() = state.copyToClipboard
        set(value) {
            state.copyToClipboard = value
        }

    var hostSearchEnabled: Boolean
        get() = state.hostSearchEnabled
        set(value) {
            state.hostSearchEnabled = value
        }

    var hostDeepSearch: Boolean
        get() = state.hostDeepSearch
        set(value) {
            state.hostDeepSearch = value
        }

    val logHosts: MutableMap<String, String> get() = state.logHosts

    val savedLogs: MutableList<SavedLog> get() = state.savedLogs

    val logHistory: MutableList<String> get() = state.logHistory

    var logSudo: Boolean
        get() = state.logSudo
        set(value) {
            state.logSudo = value
        }

    var lastLogHost: String
        get() = state.lastLogHost
        set(value) {
            state.lastLogHost = value
        }

    /** Every item, folders before their content. */
    fun all(): List<SnippetItem> {
        val result = ArrayList<SnippetItem>()
        fun walk(list: List<SnippetItem>) {
            for (item in list) {
                result += item
                if (item.folder) walk(item.children)
            }
        }
        walk(items)
        return result
    }

    fun find(id: String): SnippetItem? = all().firstOrNull { it.id == id }

    /** The list (top level or a folder's children) that holds [item]. */
    fun parentList(item: SnippetItem): MutableList<SnippetItem>? {
        fun search(list: MutableList<SnippetItem>): MutableList<SnippetItem>? {
            if (list.any { it === item }) return list
            for (child in list) if (child.folder) search(child.children)?.let { return it }
            return null
        }
        return search(items)
    }

    /** Call after changing [items]: refreshes the open tool windows and the actions of commands with a shortcut. */
    fun changed() {
        SnippetShortcuts.sync()
        listeners.forEach { it() }
    }

    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun sampleItems(): MutableList<SnippetItem> = mutableListOf(
        newSnippet("List files", "ls -la"),
        newFolder("Git", listOf(
            newSnippet("Status", "git status"),
            newSnippet("Last 10 commits", "git log --oneline -10"),
        )),
    )

    companion object {
        fun getInstance(): SnippetStore = service()
    }
}
