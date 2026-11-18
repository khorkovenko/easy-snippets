package io.github.khorkovenko.easysnippets

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.atomic.AtomicBoolean

private const val PLUGIN_ID = "io.github.khorkovenko.easysnippets"

/**
 * A shortcut of its own for a command or folder. Only the items the user asked a shortcut for ([SnippetItem.hotkey])
 * are registered as actions "Easy Snippets: <description>", so Settings | Keymap lists those and nothing else.
 */
object SnippetShortcuts {
    private const val PREFIX = "EasySnippets.Run."

    fun actionId(item: SnippetItem): String = PREFIX + item.id

    /** The shortcut assigned to [item] in the active keymap, or an empty string. */
    fun shortcutText(item: SnippetItem): String =
        if (item.hotkey) KeymapUtil.getFirstKeyboardShortcutText(actionId(item)) else ""

    fun hasShortcut(item: SnippetItem): Boolean = KeymapUtil.getActiveKeymapShortcuts(actionId(item)).shortcuts.isNotEmpty()

    @Synchronized
    fun sync() {
        val manager = ActionManager.getInstance()
        unregisterAll()
        for (item in SnippetStore.getInstance().all()) {
            // A shortcut assigned with an earlier version of the plugin is still in the keymap: keep it working
            if (!item.hotkey && hasShortcut(item)) item.hotkey = true
            if (item.hotkey) manager.registerAction(actionId(item), RunSnippetAction(item.id, item), PluginId.getId(PLUGIN_ID))
        }
    }

    @Synchronized
    fun unregisterAll() {
        val manager = ActionManager.getInstance()
        for (id in manager.getActionIdList(PREFIX)) manager.unregisterAction(id)
    }
}

/** Registers the per-command actions once, when the first project opens. */
class SnippetShortcutsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (registered.compareAndSet(false, true)) SnippetShortcuts.sync()
    }

    private companion object {
        val registered = AtomicBoolean()
    }
}

/** Removes the per-command actions when the plugin is unloaded without a restart. */
class SnippetShortcutsUnloader : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        SnippetShortcuts.unregisterAll()
        UnloadHooks.hooks.forEach { it() }
        UnloadHooks.hooks.clear()
    }
}

/** Runs one saved command, or all commands of a folder one after another. */
class RunSnippetAction(private val itemId: String, item: SnippetItem) : DumbAwareAction() {
    init {
        val text = if (item.folder) "Easy Snippets: Run Folder '${item.name}'" else "Easy Snippets: ${item.name.ifBlank { item.commandPreview() }}"
        templatePresentation.setText(StringUtil.shortenTextWithEllipsis(text, 90, 0), false)
        templatePresentation.description = StringUtil.shortenTextWithEllipsis(item.commandPreview(), 200, 0)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val item = SnippetStore.getInstance().find(itemId) ?: return
        SnippetRunner.getInstance().run(project, listOf(item))
    }
}
