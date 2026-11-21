package io.github.khorkovenko.easysnippets

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.Icon

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
 * The popup opened by the plugin's one shortcut: the favourite commands, type to filter, Enter to run. The last
 * entry opens the Easy Snippets tool window with all commands.
 */
class ShowSnippetsPopupAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val group = DefaultActionGroup()
        val favorites = SnippetStore.getInstance().all().filter { it.favorite }
        for (item in favorites) {
            val text = if (item.folder) "${item.name}  (${item.snippets().size} commands)" else item.label()
            group.add(simpleAction(StringUtil.shortenTextWithEllipsis(text, 110, 0), null) {
                SnippetRunner.getInstance().run(project, listOf(item))
            })
        }
        if (favorites.isEmpty()) {
            group.add(simpleAction("No favourites yet: add them in Easy Snippets", null) { showToolWindow(project) })
        }
        group.add(Separator.getInstance())
        group.add(simpleAction("Open Easy Snippets...", null) { showToolWindow(project) })
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Easy Snippets Favourites", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showCenteredInCurrentWindow(project)
    }
}

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
