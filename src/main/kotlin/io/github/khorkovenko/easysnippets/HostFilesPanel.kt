package io.github.khorkovenko.easysnippets

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.remote.RemoteCredentialsHolder
import com.intellij.ssh.channels.SftpChannel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.jetbrains.plugins.webDeployment.config.Deployable
import com.jetbrains.plugins.webDeployment.config.WebServerConfig
import com.jetbrains.plugins.webDeployment.remoteEdit.EditRemoteFileAction
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

private val LOG = logger<HostFilesPanel>()

private enum class EntryKind { FOLDER, FILE, LINK }

/** A remote file or folder. A [EntryKind.LINK] turns out to be a folder or a file when it is opened. */
private class RemoteEntry(val path: String, val name: String, var kind: EntryKind, val size: Long) {
    /** A listing was requested (and maybe has arrived): expanding the node must not request it again. */
    var loaded = false

    /** What the folder contains, once it has been listed; for a search result, the matches found inside it. */
    var children: List<RemoteEntry>? = null
}

private val ENTRY_ORDER = compareBy<RemoteEntry> { it.kind == EntryKind.FILE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/**
 * The file tree of one host, shown inside a terminal tab in place of the terminal. A folder is listed with a
 * single SFTP request, so big folders such as /usr/bin open quickly. Double-click opens a file in the IDE's
 * remote-file editor (edit, then upload from the banner at the top of the editor); the toolbar also copies an
 * scp command that downloads the selection to the local machine.
 *
 * The search field has three behaviours, chosen with the two switches beside it. Deep search (the default) runs
 * "find" on the host under the selected folder. Without deep search it filters the folders opened so far. With
 * search switched off, typing does nothing. In every mode "/some/path" + Enter jumps to that path.
 */
class HostFilesPanel(private val project: Project, server: WebServerConfig, parent: Disposable) :
    JPanel(BorderLayout()), Disposable {

    val serverId: String? = server.id
    private val store = SnippetStore.getInstance()
    private val deployable: Deployable = Deployable.create(server, project)
    private val credentials = RemoteCredentialsHolder().also { deployable.copyToCredentials(it) }
    private val sftp = AppExecutorUtil.createBoundedApplicationPoolExecutor("Easy Snippets SFTP", 1)
    private val finder = AppExecutorUtil.createBoundedApplicationPoolExecutor("Easy Snippets Find", 1)
    private var channel: SftpChannel? = null // used on the sftp executor only
    @Volatile private var disposed = false
    @Volatile private var findProcess: Process? = null
    @Volatile private var searchGeneration = 0

    private val rootEntry = RemoteEntry("/", credentials.host ?: server.getName().orEmpty(), EntryKind.FOLDER, 0)
    private val root = DefaultMutableTreeNode(rootEntry)
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val search = SearchTextField(false)
    private val searchDelay = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Text the opened folders are filtered by (search on, deep search off). */
    private var filter = ""
    private var expandedBeforeFilter: Set<String> = emptySet()

    /** Matches of the last deep search, as a tree under the root; null while the normal tree is shown. */
    private var results: List<RemoteEntry>? = null
    private var resultsInfo = ""

    /** The folder a deep search looks in: the folder selected in the normal tree. */
    private var searchBase = "/"

    init {
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.cellRenderer = EntryRenderer()
        TreeSpeedSearch.installOn(tree, false) { path -> entryOf(path.lastPathComponent)?.name.orEmpty() }
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (entryOf(node)?.loaded == false) load(node)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })
        tree.addTreeSelectionListener {
            if (results != null) return@addTreeSelectionListener
            val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
            searchBase = folderOf(node)?.let { entryOf(it)?.path } ?: "/"
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val entry = selectedEntries().singleOrNull() ?: return false
                if (entry.kind != EntryKind.FILE) return false
                edit(entry)
                return true
            }
        }.installOn(tree)

        val actions = DefaultActionGroup(
            action("Edit File", "Open the file in the editor; upload your changes from the banner at the top of the editor",
                AllIcons.Actions.Edit, { selectedEntries().singleOrNull()?.kind == EntryKind.FILE }) {
                selectedEntries().singleOrNull()?.let { edit(it) }
            },
            action("Copy Download Command", "Copy an scp command; paste it in a local terminal to save the selection into that terminal's folder",
                AllIcons.Actions.Download, { selectedEntries().isNotEmpty() }) { copyDownloadCommand() },
            action("Copy Path", "Copy the remote path", AllIcons.Actions.Copy, { selectedEntries().isNotEmpty() }) {
                copy(selectedEntries().joinToString("\n") { it.path })
            },
            action("Refresh", "List the selected folder again", AllIcons.Actions.Refresh) { refreshSelection() },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EasySnippetsHostFiles", actions, true)
        toolbar.targetComponent = tree
        PopupHandler.installPopupMenu(tree, actions, "EasySnippetsHostFilesPopup")

        val switches = DefaultActionGroup(
            toggle("Search While Typing", "Off: typing in the field does nothing (a /path + Enter still jumps to the path)",
                AllIcons.Actions.Search, { store.hostSearchEnabled }) {
                store.hostSearchEnabled = it
                searchChanged()
            },
            toggle("Deep Search on the Host", "On: look through all sub-folders of the selected folder on the host. " +
                "Off: only filter the folders you have opened", AllIcons.Actions.ShowAsTree, { store.hostDeepSearch },
                { store.hostSearchEnabled }) {
                store.hostDeepSearch = it
                searchChanged()
            },
        )
        val switchBar = ActionManager.getInstance().createActionToolbar("EasySnippetsHostSearch", switches, true)
        switchBar.targetComponent = search

        search.textEditor.emptyText.text = "Search, or /path + Enter"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = searchChanged()
        })
        search.textEditor.addActionListener {
            val text = search.text.trim()
            if (text.startsWith("/")) reveal(text) else IdeFocusManager.getInstance(project).requestFocus(tree, true)
        }
        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.WEST)
        top.add(search, BorderLayout.CENTER)
        top.add(switchBar.component, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        Disposer.register(parent, this)
        load(root)
    }

    val focusTarget: JComponent get() = tree

    override fun dispose() {
        disposed = true
        destroyInBackground(findProcess)
        finder.shutdown()
        sftp.execute { closeChannel() }
        sftp.shutdown()
    }

    private fun action(text: String, description: String, icon: Icon, enabled: () -> Boolean = { true },
                       perform: () -> Unit): AnAction = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private fun toggle(text: String, description: String, icon: Icon, selected: () -> Boolean,
                       enabled: () -> Boolean = { true }, change: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = selected()
            override fun setSelected(e: AnActionEvent, state: Boolean) = change(state)
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isEnabled = enabled()
            }
        }

    private fun entryOf(node: Any?): RemoteEntry? = (node as? DefaultMutableTreeNode)?.userObject as? RemoteEntry

    private fun selectedEntries(): List<RemoteEntry> = tree.selectionPaths.orEmpty().mapNotNull { entryOf(it.lastPathComponent) }

    private fun nodes(): Sequence<DefaultMutableTreeNode> =
        root.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>()

    /** The node that shows [entry] now. The tree is rebuilt by searches, so nodes must not be kept across requests. */
    private fun nodeOf(entry: RemoteEntry): DefaultMutableTreeNode? = nodes().firstOrNull { it.userObject === entry }

    /** [node] itself when it is a folder, else the nearest folder above it. */
    private fun folderOf(node: DefaultMutableTreeNode?): DefaultMutableTreeNode? =
        generateSequence(node) { it.parent as? DefaultMutableTreeNode }.firstOrNull { entryOf(it)?.kind == EntryKind.FOLDER }

    /** Lists the folder of [node] in the background and fills its node when the answer arrives. */
    private fun load(node: DefaultMutableTreeNode, onLoaded: (() -> Unit)? = null) {
        val entry = entryOf(node) ?: return
        if (disposed) return
        entry.loaded = true
        entry.children = null
        setChildren(node, listOf(DefaultMutableTreeNode(LOADING)))
        sftp.execute {
            val result = runCatching { list(entry.path) }
            // A link that cannot be listed is a file only if the host says so; a timeout or "permission denied"
            // must stay an error
            val linkToFile = result.isFailure && entry.kind == EntryKind.LINK && runCatching { isFile(entry.path) }.getOrDefault(false)
            if (result.isFailure && !linkToFile) closeChannel() // a broken connection is opened again on the next request
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val entries = result.getOrNull()
                val target = nodeOf(entry)
                when {
                    entries != null -> {
                        entry.kind = EntryKind.FOLDER
                        entry.children = entries
                        if (target != null) setChildren(target, nodesFor(entries, showAll = false))
                        onLoaded?.invoke()
                    }
                    linkToFile -> {
                        // Not a folder after all: a link to a file
                        entry.kind = EntryKind.FILE
                        if (target != null) setChildren(target, emptyList())
                    }
                    else -> {
                        LOG.info("Cannot list ${entry.path}", result.exceptionOrNull())
                        entry.loaded = false
                        if (target != null) {
                            setChildren(target, listOf(DefaultMutableTreeNode("Cannot open: ${result.exceptionOrNull()?.message}")))
                        }
                    }
                }
            }, ModalityState.any())
        }
    }

    private fun setChildren(node: DefaultMutableTreeNode, children: List<DefaultMutableTreeNode>) {
        node.removeAllChildren()
        children.forEach { node.add(it) }
        model.nodeStructureChanged(node)
        if (node === root) tree.expandPath(TreePath(root.path))
    }

    private fun matches(entry: RemoteEntry): Boolean =
        filter.isEmpty() || entry.name.contains(filter, ignoreCase = true) || entry.children?.any { matches(it) } == true

    /** Tree nodes for [entries] that pass the filter. Under a folder whose own name matches, everything is shown. */
    private fun nodesFor(entries: List<RemoteEntry>, showAll: Boolean): List<DefaultMutableTreeNode> =
        entries.filter { showAll || matches(it) }.map { entry ->
            DefaultMutableTreeNode(entry).also { node ->
                val children = entry.children
                when {
                    children != null -> {
                        val all = showAll || filter.isEmpty() || (entry.name.contains(filter, ignoreCase = true) && children.none { matches(it) })
                        nodesFor(children, all).forEach { node.add(it) }
                    }
                    entry.kind != EntryKind.FILE -> node.add(DefaultMutableTreeNode(LOADING))
                }
            }
        }

    private fun listedNodes(): List<DefaultMutableTreeNode> = nodes().filter { entryOf(it)?.children != null }.toList()

    /** Rebuilds the tree under the root: the search results when there are any, else the (filtered) folders. */
    private fun showTree(expand: (RemoteEntry) -> Boolean) {
        val found = results
        val children = when {
            found != null -> listOf(DefaultMutableTreeNode(resultsInfo)) + nodesFor(found, showAll = true)
            else -> rootEntry.children?.let { nodesFor(it, showAll = false) } ?: listOf(DefaultMutableTreeNode(LOADING))
        }
        setChildren(root, children)
        for (node in listedNodes()) {
            val entry = entryOf(node) ?: continue
            if (expand(entry)) tree.expandPath(TreePath(node.path))
        }
    }

    /** Called when the search text or one of the two switches changes. */
    private fun searchChanged() {
        searchDelay.cancelAllRequests()
        val text = search.text.trim()
        val active = store.hostSearchEnabled && text.isNotEmpty() && !text.startsWith("/")
        if (active && store.hostDeepSearch) {
            applyFilter("")
            if (text.length < MIN_DEEP_SEARCH_LENGTH) clearResults() else searchDelay.addRequest({ deepSearch(text) }, DEEP_SEARCH_DELAY_MS)
        } else {
            clearResults()
            applyFilter(if (active) text else "")
        }
    }

    /** Shows only what matches [text] in the folders that have been opened so far; nothing is asked from the host. */
    private fun applyFilter(text: String) {
        if (text == filter) return
        if (filter.isEmpty() && results == null) {
            expandedBeforeFilter = listedNodes().filter { tree.isExpanded(TreePath(it.path)) }.mapNotNull { entryOf(it)?.path }.toSet()
        }
        filter = text
        if (results == null) showTree { filter.isNotEmpty() || it.path in expandedBeforeFilter }
    }

    private fun clearResults() {
        searchGeneration++
        destroyInBackground(findProcess)
        if (results == null) return
        results = null
        showTree { filter.isNotEmpty() || it.path in expandedBeforeFilter }
    }

    /** Runs "find" on the host under [searchBase] and shows the matches as a tree. */
    private fun deepSearch(text: String) {
        if (disposed) return
        if (results == null && filter.isEmpty()) {
            expandedBeforeFilter = listedNodes().filter { tree.isExpanded(TreePath(it.path)) }.mapNotNull { entryOf(it)?.path }.toSet()
        }
        val generation = ++searchGeneration
        val base = searchBase
        destroyInBackground(findProcess)
        results = emptyList()
        resultsInfo = "Searching in $base ..."
        showTree { true }
        finder.execute {
            val outcome = runCatching { find(base, text) }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || generation != searchGeneration) return@invokeLater
                val lines = outcome.getOrNull()
                if (lines == null) {
                    LOG.info("Search on the host failed", outcome.exceptionOrNull())
                    resultsInfo = "Search failed: ${outcome.exceptionOrNull()?.message}"
                } else {
                    results = resultTree(lines)
                    resultsInfo = when {
                        lines.isEmpty() -> "Nothing named like '$text' in $base"
                        lines.size >= MAX_RESULTS -> "First $MAX_RESULTS matches in $base (select a deeper folder, clear the search and type again to narrow it)"
                        else -> "${lines.size} match(es) in $base"
                    }
                }
                showTree { true }
            }, ModalityState.any())
        }
    }

    /** Runs on the finder executor. Each line is "type<TAB>size<TAB>path". */
    private fun find(base: String, text: String): List<String> {
        val pattern = "*" + text.replace(Regex("""([\\*?\[\]])"""), """\\$1""") + "*"
        // From the root, skip the virtual file systems: they are huge and never what one is looking for
        val prune = if (base == "/") """\( -path /proc -o -path /sys -o -path /dev -o -path /run \) -prune -o """ else ""
        val command = "find ${shellQuote(base)} $prune-iname ${shellQuote(pattern)} -printf '%y\\t%s\\t%p\\n' 2>/dev/null | head -n $MAX_RESULTS"
        val process = SshConnections.exec(credentials, project, command)
        findProcess = process
        try {
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).useLines { it.take(MAX_RESULTS).toList() }
            process.waitFor(5, TimeUnit.SECONDS)
            return lines
        } finally {
            process.destroy()
            if (findProcess === process) findProcess = null
        }
    }

    private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** Turns the found paths into entries nested like their folders, starting below the root. */
    private fun resultTree(lines: List<String>): List<RemoteEntry> {
        val top = ArrayList<RemoteEntry>()
        val byPath = HashMap<String, RemoteEntry>()
        val childLists = HashMap<String, MutableList<RemoteEntry>>()
        fun folder(path: String): MutableList<RemoteEntry> {
            if (path == "/" || path.isEmpty()) return top
            childLists[path]?.let { return it }
            val entry = byPath[path] ?: RemoteEntry(path, path.substringAfterLast('/'), EntryKind.FOLDER, 0).also {
                byPath[path] = it
                folder(path.substringBeforeLast('/', "")).add(it)
            }
            val list = ArrayList<RemoteEntry>()
            entry.kind = EntryKind.FOLDER
            entry.loaded = true // shows the matches inside it; Refresh lists everything
            entry.children = list
            childLists[path] = list
            return list
        }
        for (line in lines) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) continue
            val path = parts[2].trimEnd('/')
            if (path.isEmpty() || path in byPath) continue
            val kind = when (parts[0]) {
                "d" -> EntryKind.FOLDER
                "l" -> EntryKind.LINK
                else -> EntryKind.FILE
            }
            val entry = RemoteEntry(path, path.substringAfterLast('/'), kind, parts[1].toLongOrNull() ?: 0)
            byPath[path] = entry
            folder(path.substringBeforeLast('/', "")).add(entry)
        }
        fun sort(list: MutableList<RemoteEntry>) {
            list.sortWith(ENTRY_ORDER)
            list.forEach { entry -> childLists[entry.path]?.let { sort(it) } }
        }
        sort(top)
        return top
    }

    /** Opens the folders along [path] one by one and selects its last part. */
    private fun reveal(path: String) {
        searchDelay.cancelAllRequests()
        clearResults()
        applyFilter("")
        step(root, path.split('/').filter { it.isNotEmpty() }, 0)
    }

    private fun step(node: DefaultMutableTreeNode, segments: List<String>, index: Int) {
        val entry = entryOf(node) ?: return
        val treePath = TreePath(node.path)
        if (index == segments.size) {
            tree.selectionPath = treePath
            tree.scrollPathToVisible(treePath)
            IdeFocusManager.getInstance(project).requestFocus(tree, true)
            return
        }
        if (entry.children == null) {
            if (entry.kind == EntryKind.FILE) {
                notify("${entry.path} is a file, not a folder.")
                return
            }
            load(node) { nodeOf(entry)?.let { step(it, segments, index) } }
            return
        }
        tree.expandPath(treePath)
        val child = node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().firstOrNull { entryOf(it)?.name == segments[index] }
        if (child == null) {
            tree.selectionPath = treePath
            notify("There is no '${segments[index]}' in ${entry.path}.")
            return
        }
        step(child, segments, index + 1)
    }

    /** Runs on the sftp executor. One request returns the names, types and sizes of everything in [path]. */
    private fun list(path: String): List<RemoteEntry> {
        val sftpChannel = channel ?: SshConnections.openSftp(credentials, project).also { channel = it }
        val prefix = if (path.endsWith("/")) path else "$path/"
        return sftpChannel.ls(path)
            .filter { it.name != "." && it.name != ".." }
            .map { info ->
                val kind = when (info.attrs.permissions and FILE_TYPE_MASK) {
                    TYPE_FOLDER -> EntryKind.FOLDER
                    TYPE_LINK, 0 -> EntryKind.LINK
                    else -> EntryKind.FILE
                }
                RemoteEntry(prefix + info.name, info.name, kind, info.attrs.size)
            }
            .sortedWith(ENTRY_ORDER)
    }

    /** Runs on the sftp executor. Follows links: true when [path] exists and is not a folder. */
    private fun isFile(path: String): Boolean {
        val sftpChannel = channel ?: SshConnections.openSftp(credentials, project).also { channel = it }
        return (sftpChannel.stat(path).permissions and FILE_TYPE_MASK) != TYPE_FOLDER
    }

    private fun closeChannel() {
        try {
            channel?.close()
        } catch (e: Exception) {
            LOG.info("Cannot close the SFTP channel", e)
        }
        channel = null
    }

    private fun refreshSelection() {
        val folder = folderOf(tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode) ?: root
        if (folder === root && results != null) {
            search.text = "" // back from the search results to the folders
            return
        }
        val entry = entryOf(folder) ?: return
        load(folder) { nodeOf(entry)?.let { tree.expandPath(TreePath(it.path)) } }
    }

    private fun edit(entry: RemoteEntry) {
        EditRemoteFileAction.editRemoteFile(deployable, project, null, WebServerConfig.RemotePath(entry.path), null, null)
    }

    private fun copyDownloadCommand() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        val host = credentials.host?.takeIf { it.isNotEmpty() } ?: rootEntry.name
        // One command per line, so it works in every shell
        val command = entries.joinToString("\n") { entry ->
            scpDownloadCommand(credentials.userName, host, credentials.port, entry.path, recursive = entry.kind != EntryKind.FILE)
        }
        copy(command)
        notify("Download command copied. Paste it in a local terminal: " +
            (if (entries.size == 1) "'${entries[0].name}' is" else "the ${entries.size} items are") +
            " saved into the folder that terminal is in.")
    }

    private fun copy(text: String) =CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun notify(text: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Easy Snippets")
            .createNotification("Easy Snippets", text, NotificationType.INFORMATION).notify(project)
    }

    private class EntryRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                                           leaf: Boolean, row: Int, hasFocus: Boolean) {
            val userObject = (value as? DefaultMutableTreeNode)?.userObject
            val entry = userObject as? RemoteEntry
            if (entry == null) {
                append(userObject?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                return
            }
            icon = when (entry.kind) {
                EntryKind.FOLDER -> AllIcons.Nodes.Folder
                EntryKind.LINK -> AllIcons.Nodes.Related
                EntryKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon ?: AllIcons.FileTypes.Any_type
            }
            append(entry.name)
            if (entry.kind == EntryKind.FILE) append("  " + StringUtil.formatFileSize(entry.size), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (entry.kind == EntryKind.LINK) append("  link", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private companion object {
        const val FILE_TYPE_MASK = 0xF000
        const val TYPE_FOLDER = 0x4000
        const val TYPE_LINK = 0xA000
        const val LOADING = "Loading..."
        const val MAX_RESULTS = 500
        const val MIN_DEEP_SEARCH_LENGTH = 2
        const val DEEP_SEARCH_DELAY_MS = 500
    }
}
