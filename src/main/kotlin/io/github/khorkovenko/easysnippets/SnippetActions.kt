package io.github.khorkovenko.easysnippets

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionButtonComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.SwingUtilities

const val TOOL_WINDOW_ID = "Easy Snippets"

object EasySnippetsIcons {
    val Plugin: Icon = IconLoader.getIcon("/icons/easySnippets.svg", EasySnippetsIcons::class.java)
    val Command: Icon = AllIcons.Debugger.Console
    val Folder: Icon = AllIcons.Nodes.Folder
    val Favorite: Icon = AllIcons.Nodes.Favorite
    /** The star of an item that is not a favourite, to click in the tree. */
    val NotFavorite: Icon = IconLoader.getIcon("/icons/starOutline.svg", EasySnippetsIcons::class.java)
    val ClearFavourites: Icon = IconLoader.getIcon("/icons/clearFavourites.svg", EasySnippetsIcons::class.java)
}

/**
 * The popup opened by the plugin's one shortcut and by its button in the terminal; type to filter, Enter to run. The
 * last entry opens the tool window. From the shortcut it lists only the favourites and opens at the mouse pointer;
 * from a button or menu it lists the favourites first, then every command under the name of its folder, and opens
 * under that button.
 */
class ShowSnippetsPopupAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val store = SnippetStore.getInstance()
        val group = DefaultActionGroup()
        fun add(item: SnippetItem) {
            val text = if (item.folder) "${item.name}  (${item.snippets().size} commands)" else item.label()
            val icon = if (item.favorite) EasySnippetsIcons.Favorite else if (item.folder) EasySnippetsIcons.Folder else EasySnippetsIcons.Command
            group.add(simpleAction(StringUtil.shortenTextWithEllipsis(text, 110, 0), icon) {
                SnippetRunner.getInstance().run(project, listOf(item))
            })
        }
        val favourites = store.all().filter { it.favorite }
        val onlyFavourites = e.inputEvent is KeyEvent
        if (onlyFavourites) {
            favourites.forEach { add(it) }
            if (favourites.isEmpty()) {
                group.add(simpleAction("No favourites yet: star a command in Easy Snippets", null) { showToolWindow(project) })
            }
            group.add(Separator.getInstance())
            group.add(simpleAction("Open Easy Snippets...", null) { showToolWindow(project) })
            val popup = JBPopupFactory.getInstance().createActionGroupPopup("Easy Snippets Favourites", group, e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            showNearInvoker(popup, e)
            return
        }
        if (favourites.isNotEmpty()) {
            group.add(Separator.create("Favourites"))
            favourites.forEach { add(it) }
        }
        // Commands at the top level, then each folder as a section of its own (nested ones as "Parent / Child")
        val topLevel = store.items.filter { !it.folder }
        if (topLevel.isNotEmpty()) {
            if (favourites.isNotEmpty()) group.add(Separator.create("Commands"))
            topLevel.forEach { add(it) }
        }
        fun section(folder: SnippetItem, path: String) {
            val commands = folder.children.filter { !it.folder }
            if (commands.isNotEmpty()) {
                group.add(Separator.create(path))
                commands.forEach { add(it) }
            }
            folder.children.filter { it.folder }.forEach { section(it, "$path / ${it.name}") }
        }
        store.items.filter { it.folder }.forEach { section(it, it.name) }
        if (store.all().none { !it.folder }) {
            group.add(simpleAction("No commands yet: add them in Easy Snippets", null) { showToolWindow(project) })
        }
        group.add(Separator.getInstance())
        group.add(simpleAction("Open Easy Snippets...", null) { showToolWindow(project) })
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup("Easy Snippets", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
        showNearInvoker(popup, e)
    }
}

private val POPUP_LOG = logger<ShowSnippetsPopupAction>()

/**
 * Under the toolbar button that was clicked; for a shortcut or a menu item, at the mouse pointer when it is over an
 * IDE window; otherwise next to the focused component. Never centred.
 */
fun showNearInvoker(popup: JBPopup, e: AnActionEvent) {
    val button = clickedButton(e)
    if (button != null) {
        POPUP_LOG.info("Easy Snippets popup: under ${button.javaClass.simpleName} (${e.place})")
        popup.showUnderneathOf(button)
        return
    }
    val pointer = MouseInfo.getPointerInfo()?.location
    val window = pointer?.let { windowAt(it) }
    if (pointer != null && window != null) {
        POPUP_LOG.info("Easy Snippets popup: at the mouse pointer (${e.place}, ${e.inputEvent?.javaClass?.simpleName})")
        val local = Point(pointer).also { SwingUtilities.convertPointFromScreen(it, window) }
        popup.show(RelativePoint(window, local))
    } else {
        POPUP_LOG.info("Easy Snippets popup: near the focused component (${e.place})")
        popup.showInBestPositionFor(e.dataContext)
    }
}

/**
 * The toolbar button the action was clicked on. The mouse event may come from the toolbar or a panel around the
 * button, or be missing when the toolbar performs the action later, so the button is also looked up at the point
 * of the click and, failing that, under the mouse pointer. A shortcut never has one.
 */
private fun clickedButton(e: AnActionEvent): JComponent? {
    val input = e.inputEvent
    if (input is KeyEvent) return null
    if (input is MouseEvent) {
        val source = input.component
        if (source != null && source.isShowing && source !is JMenuItem) {
            val deepest = SwingUtilities.getDeepestComponentAt(source, input.x, input.y) ?: source
            buttonAround(deepest)?.let { return it }
        }
    }
    val pointer = MouseInfo.getPointerInfo()?.location ?: return null
    val window = windowAt(pointer) ?: return null
    val local = Point(pointer).also { SwingUtilities.convertPointFromScreen(it, window) }
    return SwingUtilities.getDeepestComponentAt(window, local.x, local.y)?.let { buttonAround(it) }
}

/** The component itself or the nearest one around it that is an action button. */
private fun buttonAround(component: Component): JComponent? {
    var current: Component? = component
    var level = 0
    while (current != null && current !is Window && level++ < 4) {
        if (current is ActionButtonComponent && current is JComponent && current.isShowing) return current
        current = current.parent
    }
    return null
}

/** The topmost showing IDE window under the point: a popup or dialog before the frame under it. */
private fun windowAt(point: Point): Window? =
    Window.getWindows().filter { it.isShowing && it.bounds.contains(point) }.maxByOrNull { if (it.owner != null) 1 else 0 }

fun simpleAction(text: String, icon: Icon?, perform: () -> Unit): AnAction = object : DumbAwareAction() {
    init {
        templatePresentation.setText(text, false)
        templatePresentation.icon = icon
    }

    override fun actionPerformed(e: AnActionEvent) = perform()
}

fun showToolWindow(project: Project) {
    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
}
