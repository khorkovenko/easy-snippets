package io.github.khorkovenko.easysnippets

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.keymap.impl.ui.EditKeymapsDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class SnippetsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SnippetsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Renders a command as "description - command" and a folder with the number of commands in it. */
fun renderSnippet(component: SimpleColoredComponent, item: SnippetItem) {
    if (item.folder) {
        component.icon = EasySnippetsIcons.Folder
        component.append(item.name)
        val count = item.snippets().size
        component.append(if (count == 1) "  1 command" else "  $count commands", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    } else {
        component.icon = EasySnippetsIcons.Command
        val preview = item.commandPreview()
        if (item.name.isBlank() || item.name == preview) {
            component.append(preview)
        } else {
            component.append(item.name)
            component.append(" - $preview", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        if (item.pasteOnly) component.append("  paste only", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }
    if (item.favorite) component.append("  ★", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    val shortcut = SnippetShortcuts.shortcutText(item)
    if (shortcut.isNotEmpty()) component.append("  $shortcut", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
}

/**
 * The tool window: a tree of folders and commands with a preview of what Run would send to the terminal.
 * The layout follows the shape of the tool window. Tall (docked left or right): toolbar on top, preview under
 * the tree. Wide (docked at the bottom): toolbar on the left, preview beside the tree.
 */
class SnippetsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val store = SnippetStore.getInstance()
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val preview = JBTextArea()
    private val splitter = OnePixelSplitter(true, 0.7f)
    private val horizontalToolbar: ActionToolbar
    private val verticalToolbar: ActionToolbar
    private var wideLayout: Boolean? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                                               leaf: Boolean, row: Int, hasFocus: Boolean) {
                itemOf(value)?.let { renderSnippet(this, it) }
            }
        }
        TreeSpeedSearch.installOn(tree, true) { path -> itemOf(path.lastPathComponent)?.label().orEmpty() }
        tree.emptyText.text = "No commands yet"
        tree.emptyText.appendSecondaryText("Add a command", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addSnippet() }
        tree.addTreeSelectionListener { updatePreview() }

        preview.isEditable = false
        preview.lineWrap = true
        preview.wrapStyleWord = true
        preview.font = Font(Font.MONOSPACED, Font.PLAIN, preview.font.size)
        preview.border = JBUI.Borders.empty(6, 8)
        preview.emptyText.text = "Select commands to see what will run"

        val run = action("Run in Terminal", "Run the selected commands one after another in the current terminal tab",
            AllIcons.Actions.Execute, { runnableSelection().isNotEmpty() }) { runSelected() }
        val addCommand = action("Add Command", "Add a command to the selected folder, or to the top level",
            AllIcons.General.Add) { addSnippet() }
        val addFolder = action("Add Folder", "Add an empty folder", AllIcons.Actions.NewFolder) { addFolder() }
        val edit = action("Edit", "Edit the selected command or rename the folder", AllIcons.Actions.Edit,
            { selectedItems().size == 1 }) { editSelected() }
        val remove = action("Delete", "Delete the selected commands and folders", AllIcons.General.Remove,
            { selectedItems().isNotEmpty() }) { removeSelected() }
        val up = action("Move Up", null, AllIcons.Actions.MoveUp, { canMove(-1) }) { move(-1) }
        val down = action("Move Down", null, AllIcons.Actions.MoveDown, { canMove(1) }) { move(1) }
        val group = action("Group into Folder", "Put the selected commands into a new folder", AllIcons.Actions.GroupBy,
            { topSelected().isNotEmpty() }) { groupSelected() }
        val moveTo = action("Move to Folder...", "Move the selection into another folder or to the top level",
            AllIcons.Actions.Forward, { topSelected().isNotEmpty() }) { moveSelectedToFolder() }
        val chain = action("Create Chained Command", "Make one command out of the selected commands; it runs them one after another",
            AllIcons.Actions.RunAll, { runnableSelection().size > 1 }) { createChainedCommand() }
        val favorite = object : DumbAwareAction("Add to Favourites", "Favourites are listed in the popup opened by the shortcut",
            AllIcons.Nodes.Favorite) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val items = selectedItems()
                e.presentation.isEnabled = items.isNotEmpty()
                e.presentation.text = if (items.isNotEmpty() && items.all { it.favorite }) "Remove from Favourites" else "Add to Favourites"
            }

            override fun actionPerformed(e: AnActionEvent) = toggleFavorite()
        }
        val stopOnFailure = object : ToggleAction("Stop When a Command Fails",
            "When several commands run one after another, skip the rest after a failed command", AllIcons.Actions.Suspend), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = store.stopOnFailure
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                store.stopOnFailure = state
            }
        }
        val import = action("Import from JSON...", "Add commands from a JSON file, or replace all commands with it",
            AllIcons.ToolbarDecorator.Import) { SnippetTransfer.import(project)?.let { select(it) } }
        val export = action("Export to JSON...", "Save the selected commands, or all commands when nothing is selected, as JSON",
            AllIcons.ToolbarDecorator.Export, { store.items.isNotEmpty() }) { SnippetTransfer.export(project, topSelected()) }
        val shortcut = action("Assign Shortcut...", "Give the selected command or folder a shortcut of its own",
            AllIcons.Actions.Lightning, { selectedItems().size == 1 }) { assignShortcut() }
        val copyMode = object : ToggleAction("Copy Instead of Insert",
            "On: Run copies the command to the clipboard and you paste it yourself. Off: Run types it into the terminal",
            AllIcons.Actions.Copy), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = store.copyToClipboard
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                store.copyToClipboard = state
            }
        }
        val expandAll = action("Expand All", null, AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) }
        val collapseAll = action("Collapse All", null, AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) }

        run.registerCustomShortcutSet(CommonShortcuts.ENTER, tree)
        remove.registerCustomShortcutSet(CommonShortcuts.getDelete(), tree)
        edit.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)), tree)
        addCommand.registerCustomShortcutSet(CommonShortcuts.getInsert(), tree)

        val actions = DefaultActionGroup(run, Separator.getInstance(), addCommand, addFolder, edit, remove,
            Separator.getInstance(), chain, favorite, shortcut, Separator.getInstance(), up, down, group, moveTo, Separator.getInstance(), copyMode, stopOnFailure,
            Separator.getInstance(), import, export, Separator.getInstance(), expandAll, collapseAll)
        horizontalToolbar = ActionManager.getInstance().createActionToolbar("EasySnippetsToolbar", actions, true)
        verticalToolbar = ActionManager.getInstance().createActionToolbar("EasySnippetsToolbar", actions, false)
        horizontalToolbar.targetComponent = tree
        verticalToolbar.targetComponent = tree
        PopupHandler.installPopupMenu(tree, actions, "EasySnippetsPopup")

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val item = selectedItems().singleOrNull() ?: return false
                if (item.folder) return false
                runSelected()
                return true
            }
        }.installOn(tree)

        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
        splitter.secondComponent = ScrollPaneFactory.createScrollPane(preview, true)
        add(splitter, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = adaptLayout()
        })
        adaptLayout()

        store.addListener(this) { rebuild(expandEverything = false) }
        rebuild(expandEverything = true)
    }

    override fun dispose() {}

    private fun adaptLayout() {
        val wide = width > JBUI.scale(520) && width > height * 1.4
        if (wideLayout == wide) return
        wideLayout = wide
        remove(horizontalToolbar.component)
        remove(verticalToolbar.component)
        if (wide) add(verticalToolbar.component, BorderLayout.WEST) else add(horizontalToolbar.component, BorderLayout.NORTH)
        splitter.orientation = !wide
        splitter.proportion = if (wide) 0.6f else 0.7f
        revalidate()
        repaint()
    }

    private fun action(text: String, description: String?, icon: Icon, enabled: () -> Boolean = { true },
                       perform: () -> Unit): AnAction = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private fun itemOf(node: Any?): SnippetItem? = (node as? DefaultMutableTreeNode)?.userObject as? SnippetItem

    private fun selectedPaths(): List<TreePath> = tree.selectionPaths.orEmpty().sortedBy { tree.getRowForPath(it) }

    /** Selected items in the order they are shown. */
    private fun selectedItems(): List<SnippetItem> = selectedPaths().mapNotNull { itemOf(it.lastPathComponent) }

    /** Selected items without those that are inside a selected folder. */
    private fun topSelected(): List<SnippetItem> {
        val paths = selectedPaths()
        return paths.filter { path -> paths.none { it !== path && it.isDescendant(path) } }
            .mapNotNull { itemOf(it.lastPathComponent) }
    }

    private fun runnableSelection(): List<SnippetItem> = topSelected().flatMap { it.snippets() }.distinctBy { it.id }

    private fun updatePreview() {
        val commands = runnableSelection().flatMap { it.steps() }
        preview.text = when (commands.size) {
            0 -> ""
            1 -> commands[0]
            else -> commands.withIndex().joinToString("\n") { (index, command) -> "${index + 1}. $command" }
        }
        preview.caretPosition = 0
    }

    private fun runSelected() {
        SnippetRunner.getInstance().run(project, runnableSelection())
    }

    private fun addSnippet() {
        val dialog = SnippetDialog(project, null)
        if (dialog.showAndGet()) insert(dialog.createItem())
    }

    private fun addFolder() {
        val name = askFolderName("New Folder", "") ?: return
        insert(newFolder(name))
    }

    private fun askFolderName(title: String, initial: String): String? =
        Messages.showInputDialog(project, "Folder name:", title, null, initial, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Into the selected folder, after the selected command, or at the end of the top level. */
    private fun insert(item: SnippetItem) {
        val selected = selectedItems().singleOrNull()
        val siblings = selected?.let { store.parentList(it) }
        when {
            selected == null || siblings == null -> store.items.add(item)
            selected.folder -> selected.children.add(item)
            else -> siblings.add(siblings.indexOfFirst { it === selected } + 1, item)
        }
        store.changed()
        select(listOf(item))
    }

    private fun editSelected() {
        val item = selectedItems().singleOrNull() ?: return
        if (item.folder) {
            item.name = askFolderName("Rename Folder", item.name) ?: return
        } else {
            val dialog = SnippetDialog(project, item)
            if (!dialog.showAndGet()) return
            dialog.applyTo(item)
        }
        store.changed()
        select(listOf(item))
    }

    private fun removeSelected() {
        val items = topSelected()
        if (items.isEmpty()) return
        val commands = items.sumOf { it.snippets().size }
        val what = if (items.size == 1 && !items[0].folder) "'${items[0].label()}'" else "$commands command(s) in ${items.size} selected item(s)"
        if (Messages.showYesNoDialog(project, "Delete $what?", "Easy Snippets", Messages.getQuestionIcon()) != Messages.YES) return
        items.forEach { item -> store.parentList(item)?.removeIf { it === item } }
        store.changed()
    }

    private fun canMove(direction: Int): Boolean {
        val item = selectedItems().singleOrNull() ?: return false
        val siblings = store.parentList(item) ?: return false
        return siblings.indexOfFirst { it === item } + direction in siblings.indices
    }

    private fun move(direction: Int) {
        val item = selectedItems().singleOrNull() ?: return
        val siblings = store.parentList(item) ?: return
        val index = siblings.indexOfFirst { it === item }
        if (index + direction !in siblings.indices) return
        siblings[index] = siblings[index + direction]
        siblings[index + direction] = item
        store.changed()
        select(listOf(item))
    }

    /** The new folder takes the place of the first selected item. */
    private fun groupSelected() {
        val items = topSelected()
        val first = items.firstOrNull() ?: return
        val name = askFolderName("Group into Folder", "") ?: return
        val siblings = store.parentList(first) ?: return
        val folder = newFolder(name)
        siblings.add(siblings.indexOfFirst { it === first }, folder)
        items.forEach { item -> store.parentList(item)?.removeIf { it === item } }
        folder.children.addAll(items)
        store.changed()
        expand(folder)
        select(listOf(folder))
    }

    private class MoveTarget(val label: String, val list: MutableList<SnippetItem>, val folder: SnippetItem?)

    private fun moveSelectedToFolder() {
        val items = topSelected()
        if (items.isEmpty()) return
        val targets = ArrayList<MoveTarget>()
        targets += MoveTarget("Top level (no folder)", store.items, null)
        fun collect(list: List<SnippetItem>, prefix: String) {
            for (item in list) {
                if (!item.folder || items.any { it === item }) continue
                targets += MoveTarget(prefix + item.name, item.children, item)
                collect(item.children, prefix + item.name + " / ")
            }
        }
        collect(store.items, "")
        JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
            .setTitle("Move To")
            .setRenderer(object : ColoredListCellRenderer<MoveTarget>() {
                override fun customizeCellRenderer(list: JList<out MoveTarget>, value: MoveTarget?, index: Int,
                                                   selected: Boolean, hasFocus: Boolean) {
                    if (value == null) return
                    icon = if (value.folder == null) EasySnippetsIcons.Plugin else EasySnippetsIcons.Folder
                    append(value.label)
                }
            })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { target ->
                items.forEach { item -> store.parentList(item)?.removeIf { it === item } }
                target.list.addAll(items)
                store.changed()
                target.folder?.let { expand(it) }
                select(items)
            }
            .createPopup()
            .showInCenterOf(tree)
    }

    /** The selected commands, in the order shown, as the lines of one new command. */
    private fun createChainedCommand() {
        val parts = runnableSelection()
        if (parts.size < 2) return
        val draft = newSnippet(parts.joinToString(" + ") { it.name }, parts.flatMap { it.steps() }.joinToString("\n"))
        val dialog = SnippetDialog(project, draft, "Create Chained Command")
        if (!dialog.showAndGet()) return
        val chained = dialog.createItem()
        val last = parts.last()
        val siblings = store.parentList(last) ?: store.items
        siblings.add(siblings.indexOfFirst { it === last } + 1, chained)
        store.changed()
        select(listOf(chained))
    }

    /**
     * Makes the item an action of its own and opens the keymap dialog on it. When the dialog is closed without a
     * shortcut the action is removed again, so the keymap lists only commands that really have one.
     */
    private fun assignShortcut() {
        val item = selectedItems().singleOrNull() ?: return
        item.hotkey = true
        SnippetShortcuts.sync()
        EditKeymapsDialog(project, SnippetShortcuts.actionId(item)).show()
        item.hotkey = SnippetShortcuts.hasShortcut(item)
        store.changed()
        select(listOf(item))
    }

    /** Favourites are what the shortcut popup lists. */
    private fun toggleFavorite() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val makeFavorite = items.any { !it.favorite }
        items.forEach { it.favorite = makeFavorite }
        store.changed()
        select(items)
    }

    private fun rebuild(expandEverything: Boolean) {
        val expandedIds = TreeUtil.collectExpandedPaths(tree).mapNotNull { itemOf(it.lastPathComponent)?.id }.toSet()
        val selectedIds = selectedItems().map { it.id }.toSet()
        root.removeAllChildren()
        fun fill(parent: DefaultMutableTreeNode, items: List<SnippetItem>) {
            for (item in items) {
                val node = DefaultMutableTreeNode(item)
                parent.add(node)
                if (item.folder) fill(node, item.children)
            }
        }
        fill(root, store.items)
        model.reload()
        for (node in nodes()) {
            val item = itemOf(node) ?: continue
            val parentPath = TreePath((node.parent as DefaultMutableTreeNode).path)
            val parentExpanded = node.parent === root || tree.isExpanded(parentPath)
            if (item.folder && parentExpanded && (expandEverything || item.id in expandedIds)) tree.expandPath(TreePath(node.path))
        }
        select(store.all().filter { it.id in selectedIds })
        updatePreview()
    }

    private fun nodes(): List<DefaultMutableTreeNode> =
        root.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>().filter { it !== root }.toList()

    private fun pathOf(item: SnippetItem): TreePath? = nodes().firstOrNull { itemOf(it) === item }?.let { TreePath(it.path) }

    private fun expand(item: SnippetItem) {
        pathOf(item)?.let { tree.expandPath(it) }
    }

    private fun select(items: List<SnippetItem>) {
        val paths = items.mapNotNull { pathOf(it) }
        tree.selectionPaths = paths.toTypedArray()
        paths.firstOrNull()?.let { tree.scrollPathToVisible(it) }
    }
}
