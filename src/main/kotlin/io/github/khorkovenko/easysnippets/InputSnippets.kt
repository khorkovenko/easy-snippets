package io.github.khorkovenko.easysnippets

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.awt.Component
import java.awt.Window
import java.awt.datatransfer.StringSelection
import javax.swing.JList
import javax.swing.JRootPane
import javax.swing.SwingUtilities

/**
 * The button on the toolbar of a text input, such as the prompt of an AI chat: a popup of the commands, favourites
 * first, and the chosen one is typed into that input at the caret. Nothing is sent; the user presses Enter.
 */
class InsertSnippetIntoInputAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Found now: once the popup is open, the focus and the data context belong to it
        val editor = inputEditor(e)
        val entries = popupEntries()
        if (entries.isEmpty()) {
            showToolWindow(project)
            return
        }
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
            .setTitle(if (editor != null) "Insert Snippet" else "Copy Snippet")
            .setRenderer(object : ColoredListCellRenderer<Entry>() {
                override fun customizeCellRenderer(list: JList<out Entry>, value: Entry?, index: Int,
                                                   selected: Boolean, hasFocus: Boolean) {
                    if (value == null) return
                    renderSnippet(this, value.item, reserveStarSpace = true)
                    if (value.folderPath.isNotEmpty()) append("  ${value.folderPath}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { "${it.item.label()} ${it.folderPath}" }
            .setItemChosenCallback { insert(project, editor, it.item) }
            .createPopup()
        showNearInvoker(popup, e)
    }

    private class Entry(val item: SnippetItem, val folderPath: String)

    /** Favourite commands first, then every other command, each with the folders it is in. */
    private fun popupEntries(): List<Entry> {
        val all = ArrayList<Entry>()
        fun walk(list: List<SnippetItem>, path: String) {
            for (item in list) {
                if (item.folder) walk(item.children, if (path.isEmpty()) item.name else "$path / ${item.name}")
                else all += Entry(item, path)
            }
        }
        walk(SnippetStore.getInstance().items, "")
        val (favourites, others) = all.partition { it.item.favorite }
        return favourites + others
    }

    private fun insert(project: Project, editor: Editor?, item: SnippetItem) {
        val text = item.steps().joinToString("\n")
        if (text.isEmpty()) return
        if (editor == null || editor.isDisposed) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            NotificationGroupManager.getInstance().getNotificationGroup("Easy Snippets")
                .createNotification("Easy Snippets", "Command copied. Paste it into the input.", NotificationType.INFORMATION)
                .notify(project)
            return
        }
        WriteCommandAction.runWriteCommandAction(editor.project ?: project, "Insert Snippet", null, {
            val caret = editor.caretModel.primaryCaret
            val start = if (caret.hasSelection()) caret.selectionStart else caret.offset
            val end = if (caret.hasSelection()) caret.selectionEnd else caret.offset
            editor.document.replaceString(start, end, text)
            caret.removeSelection()
            caret.moveToOffset(start + text.length)
        })
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    /**
     * The editable editor this toolbar belongs to: the one in the data context, or else the nearest editor around the
     * button. The search stops after a few levels, so it never reaches the code editors of the IDE window.
     */
    private fun inputEditor(e: AnActionEvent): Editor? {
        e.getData(CommonDataKeys.EDITOR)?.takeIf { it.isUsable() }?.let { return it }
        val start: Component = e.inputEvent?.component ?: e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: return null
        val editors = EditorFactory.getInstance().allEditors.filter { it.isUsable() && it.contentComponent.isShowing }
        var container: Component? = start
        var level = 0
        while (container != null && container !is Window && container !is JRootPane && level++ < MAX_LEVELS) {
            val inside = editors.filter { SwingUtilities.isDescendingFrom(it.contentComponent, container) }
            if (inside.isNotEmpty()) return inside.firstOrNull { it.contentComponent.hasFocus() } ?: inside.first()
            container = container.parent
        }
        return null
    }

    private fun Editor.isUsable() = !isDisposed && !isViewer && document.isWritable

    private companion object {
        const val MAX_LEVELS = 8
    }
}
