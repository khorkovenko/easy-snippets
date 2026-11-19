package io.github.khorkovenko.easysnippets

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.awt.Point
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

private val LOG = logger<LogWatcherPanel>()

private fun hostLabel(config: SshConfig): String =
    config.name?.takeIf { it.isNotBlank() } ?: "${config.username}@${config.host}:${config.port}"

/**
 * The "Log Watcher" tab. Type what to follow into the Log field (a file path, a command that views a file such as
 * "less /path", or any command that prints a log), or take it from a saved command with the snippets button; pick
 * a host from the IDE's SSH configurations and press Watch. Each log is followed live in a tab of its own.
 * The list under the Log field holds only the logs saved here with the + button.
 */
class LogWatcherPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, LogWatcherTab {
    private val store = SnippetStore.getInstance()
    private val logs = ComboBox<Any>()
    private val hosts = ComboBox<SshConfig>()
    private val sudo = JBCheckBox("sudo", store.logSudo)
    private val sessions = JBTabbedPane()
    private val cards = CardLayout()
    private val center = JPanel(cards)

    init {
        logs.isEditable = true
        logs.toolTipText = "A file path (/var/log/messages), a command that views a file (less /path), any command that prints a log (journalctl -f), or a page address (localhost:3000) for its browser console"
        // Choosing an entry brings back its sudo setting (saved logs) and the host it was last watched on
        logs.addActionListener {
            when (val chosen = logs.selectedItem) {
                is SavedLog -> {
                    sudo.isSelected = chosen.sudo
                    store.logHosts[chosen.target.trim()]?.let { selectHost(it) }
                }
                is String -> store.logHosts[chosen.trim()]?.let { selectHost(it) }
            }
        }
        hosts.renderer = object : ColoredListCellRenderer<SshConfig>() {
            override fun customizeCellRenderer(list: JList<out SshConfig>, value: SshConfig?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value != null) append(hostLabel(value))
            }
        }
        hosts.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = reloadHosts()
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
        })
        sudo.toolTipText = "Read with sudo (needs sudo without a password prompt on the host)"
        sudo.addActionListener { store.logSudo = sudo.isSelected }
        val watch = JButton("Watch")
        watch.addActionListener { startSelected() }

        val logButtons = DefaultActionGroup(
            button("Take from Snippets...", "Choose one of your saved commands; it is put into the Log field", EasySnippetsIcons.Plugin) { chooseSnippet() },
            Separator.getInstance(),
            button("Save Log", "Save what is in the Log field under a name, so it stays in this list", AllIcons.General.Add) { addLog() },
            button("Edit Saved Log", "Change the selected saved log", AllIcons.Actions.Edit, { selectedSaved() != null }) { editLog() },
            button("Remove from the List", "Remove the selected saved log or history entry from this list", AllIcons.General.Remove,
                { selectedSaved() != null || fieldText() in store.logHistory }) { removeLog() },
        )
        val logBar = ActionManager.getInstance().createActionToolbar("EasySnippetsLogSources", logButtons, true)
        logBar.targetComponent = logs
        val logRow = JPanel(BorderLayout(JBUI.scale(4), 0))
        logRow.add(logs, BorderLayout.CENTER)
        logRow.add(logBar.component, BorderLayout.EAST)

        val hostButtons = DefaultActionGroup(
            button("SSH Configurations...", "Add, change or remove hosts in the IDE's SSH configurations", AllIcons.General.Settings) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "SSH Configurations")
                reloadHosts()
            },
        )
        val hostBar = ActionManager.getInstance().createActionToolbar("EasySnippetsLogHosts", hostButtons, true)
        hostBar.targetComponent = hosts
        val hostRow = JPanel(BorderLayout(JBUI.scale(4), 0))
        hostRow.add(hosts, BorderLayout.CENTER)
        val right = JPanel(BorderLayout(JBUI.scale(6), 0))
        right.add(hostBar.component, BorderLayout.WEST)
        right.add(sudo, BorderLayout.CENTER)
        right.add(watch, BorderLayout.EAST)
        hostRow.add(right, BorderLayout.EAST)
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Log:", logRow)
            .addLabeledComponent("Host:", hostRow)
            .panel
        form.border = JBUI.Borders.empty(6, 8)

        center.add(JBPanelWithEmptyText().withEmptyText("Enter a log file, a command or a page address such as localhost:3000, then press Watch"), EMPTY)
        center.add(sessions, TABS)
        add(form, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)

        reloadLogs(null)
        reloadHosts()
        store.lastLogHost.takeIf { it.isNotEmpty() }?.let { selectHost(it) }
    }

    override fun dispose() {
        for (index in 0 until sessions.tabCount) (sessions.getComponentAt(index) as? LogSessionPanel)?.let { Disposer.dispose(it) }
    }

    private fun button(text: String, description: String, icon: Icon, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    /** What is typed or chosen in the Log field. */
    private fun fieldText(): String = when (val item = logs.editor.item) {
        is SavedLog -> item.target
        else -> item?.toString()?.trim().orEmpty()
    }

    /** The saved log the field shows, if it shows one unchanged. */
    private fun selectedSaved(): SavedLog? {
        val item = logs.editor.item
        if (item is SavedLog) return item
        val text = item?.toString()?.trim().orEmpty()
        return store.savedLogs.firstOrNull { it.toString() == text }
    }

    /** The drop-down: the logs saved with +, then what was watched recently (newest first). */
    private fun listItems(): Array<Any> {
        val savedTargets = store.savedLogs.map { it.target.trim() }.toSet()
        return (store.savedLogs + store.logHistory.filter { it !in savedTargets }).toTypedArray()
    }

    private fun reloadLogs(select: SavedLog?) {
        val typed = fieldText()
        logs.model = DefaultComboBoxModel(listItems())
        if (select != null) logs.selectedItem = select else setField(typed)
    }

    /** Puts what was just watched at the top of the history. */
    private fun remember(text: String) {
        val entry = text.trim()
        store.logHistory.remove(entry)
        store.logHistory.add(0, entry)
        while (store.logHistory.size > HISTORY_SIZE) store.logHistory.removeAt(store.logHistory.size - 1)
        reloadLogs(selectedSaved())
    }

    private fun setField(text: String) {
        logs.selectedItem = null
        logs.editor.item = text
    }

    private fun reloadHosts() {
        val selectedId = (hosts.selectedItem as? SshConfig)?.id
        val configs = SshConfigManager.getInstance(project).configs.sortedBy { hostLabel(it).lowercase() }
        hosts.model = DefaultComboBoxModel(configs.toTypedArray())
        if (selectedId != null) selectHost(selectedId)
    }

    private fun selectHost(id: String): Boolean {
        val config = (0 until hosts.itemCount).map { hosts.getItemAt(it) }.firstOrNull { it.id == id } ?: return false
        hosts.selectedItem = config
        return true
    }

    /** A popup with every saved command; the chosen one is put into the Log field. */
    private fun chooseSnippet() {
        val snippets = store.all().filter { !it.folder && it.steps().isNotEmpty() }
        if (snippets.isEmpty()) {
            notify("There are no saved commands yet. Add some in the Snippets tab.")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(snippets)
            .setTitle("Take from Snippets")
            .setRenderer(object : ColoredListCellRenderer<SnippetItem>() {
                override fun customizeCellRenderer(list: JList<out SnippetItem>, value: SnippetItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    if (value == null) return
                    icon = EasySnippetsIcons.Command
                    append(value.name)
                    append("  " + StringUtil.shortenTextWithEllipsis(value.commandPreview(), 90, 0), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { it.label() }
            .setItemChosenCallback { useCommand(it.steps().first()) }
            .createPopup()
            .showUnderneathOf(logs)
    }

    /** Puts a command into the Log field; returns true when the host it was last watched on is selected again. */
    private fun useCommand(command: String): Boolean {
        setField(command)
        if (LogTarget.startsWithSudo(command)) sudo.isSelected = true
        return store.logHosts[command.trim()]?.let { selectHost(it) } ?: false
    }

    override fun watch(command: String) {
        reloadHosts()
        if (useCommand(command)) startSelected() else hosts.requestFocusInWindow()
    }

    private fun addLog() {
        val dialog = SavedLogDialog(project, "Save Log", "", fieldText(), sudo.isSelected)
        if (!dialog.showAndGet()) return
        val saved = SavedLog().also { dialog.applyTo(it) }
        store.savedLogs.add(saved)
        reloadLogs(saved)
    }

    private fun editLog() {
        val saved = selectedSaved() ?: return
        val dialog = SavedLogDialog(project, "Edit Saved Log", saved.name, saved.target, saved.sudo)
        if (!dialog.showAndGet()) return
        dialog.applyTo(saved)
        reloadLogs(saved)
    }

    private fun removeLog() {
        val saved = selectedSaved()
        if (saved != null) {
            if (Messages.showYesNoDialog(project, "Remove '$saved' from the list?", "Easy Snippets", Messages.getQuestionIcon()) != Messages.YES) return
            store.savedLogs.remove(saved)
        } else if (!store.logHistory.remove(fieldText())) {
            return // neither a saved log nor a history entry
        }
        logs.model = DefaultComboBoxModel(listItems())
        setField("")
    }

    private fun startSelected() {
        val text = fieldText()
        // A page address (localhost:3000) is followed in a browser on this computer; the host is not used for it
        val pageUrl = BrowserLogFeed.urlOf(text)
        if (pageUrl != null) {
            remember(text)
            val session = LogSessionPanel(project, "browser console of $pageUrl", null, false, { BrowserLogFeed(pageUrl) }) { closeSession(it) }
            addSession("browser: " + pageUrl.substringAfter("://"), "Browser console of $pageUrl", session)
            return
        }
        val target = LogTarget.parse(text, sudo.isSelected)
        val config = hosts.selectedItem as? SshConfig
        if (target == null) {
            notify("Enter what to follow: a file path such as /var/log/messages, a command that prints a log, or a page address such as localhost:3000.")
            return
        }
        if (config == null) {
            notify("Choose a host. The list shows the IDE's SSH configurations; the gear button opens them.")
            return
        }
        store.logHosts[text.trim()] = config.id
        store.lastLogHost = config.id
        remember(text)
        val credentials = config.copyToCredentials()
        val host = hostLabel(config)
        val download = target.file?.let { scpDownloadCommand(credentials.userName, config.host, config.port, it, recursive = false) }
        val session = LogSessionPanel(project, "${target.description} on $host", download, target.file != null,
            { wholeFile -> SshLogFeed(project, credentials, host, target, wholeFile) }) { closeSession(it) }
        addSession("${config.host}: ${target.title}" + (if (target.sudo) " (sudo)" else ""), "$host  ${target.description}", session)
    }

    private fun addSession(title: String, tooltip: String, session: LogSessionPanel) {
        sessions.addTab(title, session)
        sessions.setTabComponentAt(sessions.tabCount - 1, tabHeader(title, tooltip, session))
        sessions.selectedComponent = session
        cards.show(center, TABS)
    }

    private fun closeSession(session: LogSessionPanel) {
        val index = sessions.indexOfComponent(session)
        if (index >= 0) sessions.removeTabAt(index)
        Disposer.dispose(session)
        if (sessions.tabCount == 0) cards.show(center, EMPTY)
    }

    /** The tab's title with a close button; a middle click on the title closes the tab too. */
    private fun tabHeader(title: String, tooltip: String, session: LogSessionPanel): JComponent {
        val label = JBLabel(title)
        label.toolTipText = tooltip
        val closeButton = InplaceButton(IconButton("Close Session", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { closeSession(session) }
        val header = JPanel(BorderLayout(JBUI.scale(6), 0))
        header.isOpaque = false
        header.add(label, BorderLayout.CENTER)
        header.add(closeButton, BorderLayout.EAST)
        // A custom tab header takes the mouse clicks, so selecting the tab has to be passed on by hand
        val clicks = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON2) closeSession(session) else sessions.selectedComponent = session
            }
        }
        label.addMouseListener(clicks)
        header.addMouseListener(clicks)
        return header
    }

    private fun notify(text: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Easy Snippets")
            .createNotification("Easy Snippets", text, NotificationType.WARNING).notify(project)
    }

    private companion object {
        const val EMPTY = "empty"
        const val TABS = "tabs"
        const val HISTORY_SIZE = 20
    }
}

/** Name, target and sudo of a saved log. */
private class SavedLogDialog(project: Project, dialogTitle: String, name: String, target: String, sudo: Boolean) : DialogWrapper(project) {
    private val nameField = JBTextField(name, 36)
    private val targetField = JBTextField(target, 36)
    private val sudoBox = JBCheckBox("Read with sudo", sudo)

    init {
        title = dialogTitle
        init()
    }

    fun applyTo(log: SavedLog) {
        log.target = targetField.text.trim()
        log.name = nameField.text.trim().ifEmpty { LogTarget.parse(log.target, false)?.title.orEmpty() }
        log.sudo = sudoBox.isSelected
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("File or command:", targetField)
        .addComponent(sudoBox)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = if (targetField.text.isEmpty()) targetField else nameField

    override fun doValidate(): ValidationInfo? =
        if (targetField.text.isBlank()) ValidationInfo("Enter a file path or a command", targetField) else null
}

private enum class LogLevel { ERROR, WARNING, DEBUG, NORMAL }

/** A piece of a line and how to paint it; positions are relative to the start of the line. */
private class Span(val from: Int, val to: Int, val attributes: TextAttributes, val layer: Int)

/**
 * The colours of a log view, taken from the editor colour scheme so they fit light and dark themes. A whole line
 * is coloured by its level; on top of that the timestamp is dimmed, the program name and the level word are bold,
 * and words that tell of success are green.
 */
private class LogPalette(editor: EditorEx) {
    private val scheme = editor.colorsScheme

    private fun color(key: TextAttributesKey): Color? = scheme.getAttributes(key)?.foregroundColor
    private fun plain(color: Color?) = TextAttributes(color, null, null, null, Font.PLAIN)
    private fun bold(color: Color?) = TextAttributes(color, null, null, null, Font.BOLD)

    private val errorColor = ConsoleViewContentType.LOG_ERROR_OUTPUT.attributes.foregroundColor
    private val warningColor = ConsoleViewContentType.LOG_WARNING_OUTPUT.attributes.foregroundColor
    private val debugColor = ConsoleViewContentType.LOG_DEBUG_OUTPUT.attributes.foregroundColor
    private val successColor = color(DefaultLanguageHighlighterColors.STRING)

    val error = plain(errorColor)
    val warning = plain(warningColor)
    val debug = plain(debugColor)
    val errorWord = bold(errorColor)
    val warningWord = bold(warningColor)
    val debugWord = bold(debugColor)
    val infoWord = bold(successColor)
    val timestamp = plain(color(DefaultLanguageHighlighterColors.LINE_COMMENT))
    val program = bold(color(DefaultLanguageHighlighterColors.KEYWORD))
    val success = plain(successColor)

    /** How the IDE itself marks search results, in the text and on the scrollbar. */
    val match: TextAttributes = scheme.getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES) ?: TextAttributes()
    val matchStripe: Color = match.errorStripeColor ?: match.backgroundColor ?: JBColor.YELLOW
}

/**
 * One followed log, shown in a read-only editor, so text can be selected and copied as usual.
 *
 * Two views. Live: the last lines of the file (or the output of a command, or a browser console), growing as new
 * lines arrive. Whole file: a snapshot of the complete file as it is at that moment; nothing is added to it until
 * the switch is turned off again, which goes back to the live view.
 */
private class LogSessionPanel(
    private val project: Project,
    /** What is followed and where, for the status line. */
    private val shown: String,
    /** An scp command for the followed file, when it is a file on a host. */
    private val downloadCommand: String?,
    private val canLoadWholeFile: Boolean,
    private val newFeed: (wholeFile: Boolean) -> LogFeed,
    private val close: (LogSessionPanel) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    // Created like the IDE's own console document: it may be changed on the UI thread without a write action
    // (EditorFactory.createDocument(boolean) would be the call for this, but 2025.3 does not have it on the interface)
    private val document: Document = DocumentImpl("", true).also { UndoUtil.disableUndoFor(it) }
    private val editor = EditorFactory.getInstance().createViewer(document, project, EditorKind.CONSOLE) as EditorEx
    private val palette = LogPalette(editor)
    private val pending = ConcurrentLinkedQueue<String>()
    private val pendingChars = AtomicLong()
    private val flusher = Timer(FLUSH_MS) { flush() }
    private val search = SearchTextField(false)
    private val matchCount = JBLabel()
    private val status = JBLabel()
    private val searchDelay = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile private var feed: LogFeed? = null
    @Volatile private var disposed = false
    @Volatile private var generation = 0
    @Volatile private var searchGeneration = 0
    private var follow = true
    private var wholeFile = false
    private var lastLevel = LogLevel.NORMAL

    /** Whole-file view: the lines that have their colours. Only what is scrolled into view gets coloured. */
    private val paintedLines = BitSet()

    /** The search results, in the order they appear in the text. */
    private val matches = ArrayList<RangeHighlighter>()
    private var matchText = ""

    init {
        (document as? DocumentEx)?.setCyclicBufferSize(MAX_CHARS)
        editor.settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isCaretRowShown = false
            isRightMarginShown = false
            additionalLinesCount = 0
            isUseSoftWraps = false
        }
        // The marks of the search results on the scrollbar need the stripe that a viewer hides by default
        (editor.markupModel as? EditorMarkupModel)?.isErrorStripeVisible = true
        editor.scrollingModel.addVisibleAreaListener({ paintVisibleLines() }, this)

        val actions = DefaultActionGroup(
            toggle("Scroll to the End", "Keep showing the newest lines as they arrive", AllIcons.RunConfigurations.Scroll_down, { follow }) {
                follow = it
                if (it) scrollToEnd()
            },
            toggle("Soft-Wrap", "Wrap long lines", AllIcons.Actions.ToggleSoftWrap, { editor.settings.isUseSoftWraps }) {
                editor.settings.isUseSoftWraps = it
            },
            Separator.getInstance(),
            action("Copy Download Command", "Copy an scp command; paste it in a local terminal to save the whole log file into that terminal's folder",
                AllIcons.Actions.Download, { downloadCommand != null }) {
                copy(downloadCommand.orEmpty())
                notify("Download command copied. Paste it in a local terminal: the log file is saved into the folder that terminal is in.")
            },
            action("Copy All", "Copy everything shown here", AllIcons.Actions.Copy) { copy(document.text) },
            toggle("Show the Whole File", "On: the complete file as it is now, without new lines being added. " +
                "Off: the last lines, followed live", AllIcons.Actions.ShowAsTree, { wholeFile }, { canLoadWholeFile }) {
                wholeFile = it
                start()
            },
            action("Clear", "Empty this view; the file on the host is not touched", AllIcons.Actions.GC) { clear() },
            Separator.getInstance(),
            action("Close Session", "Stop following this log and close its tab", AllIcons.Actions.Suspend) { close(this) },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("EasySnippetsLogSession", actions, true)
        toolbar.targetComponent = editor.component

        val find = DefaultActionGroup(
            action("Previous Match", "Go to the previous occurrence", AllIcons.Actions.PreviousOccurence) { goToMatch(forward = false) },
            action("Next Match", "Go to the next occurrence", AllIcons.Actions.NextOccurence) { goToMatch(forward = true) },
        )
        val findBar = ActionManager.getInstance().createActionToolbar("EasySnippetsLogSearch", find, true)
        findBar.targetComponent = search
        search.textEditor.emptyText.text = "Search in the log"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchDelay.cancelAllRequests()
                searchDelay.addRequest({ searchAll(jumpToFirst = true) }, SEARCH_DELAY_MS)
            }
        })
        search.textEditor.addActionListener { goToMatch(forward = true) }
        matchCount.border = JBUI.Borders.empty(0, 6)
        matchCount.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND

        val findRow = JPanel(BorderLayout())
        findRow.add(search, BorderLayout.CENTER)
        val findRight = JPanel(BorderLayout())
        findRight.add(matchCount, BorderLayout.WEST)
        findRight.add(findBar.component, BorderLayout.EAST)
        findRow.add(findRight, BorderLayout.EAST)
        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.WEST)
        top.add(findRow, BorderLayout.CENTER)
        status.border = JBUI.Borders.empty(2, 8)
        status.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        add(top, BorderLayout.NORTH)
        add(editor.component, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        flusher.start()
        start()
    }

    override fun dispose() {
        disposed = true
        flusher.stop()
        feed?.stop()
        EditorFactory.getInstance().releaseEditor(editor)
    }

    private fun action(text: String, description: String, icon: Icon, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
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

    /** (Re)starts with a new feed, in the view the Whole File switch asks for. */
    private fun start() {
        val current = ++generation
        val whole = wholeFile
        feed?.stop()
        pending.clear()
        pendingChars.set(0)
        clear()
        status.text = "Connecting ..."
        val started = newFeed(whole)
        feed = started
        ApplicationManager.getApplication().executeOnPooledThread {
            var ending = if (whole) "The whole file is shown as it was when it was read; new lines are not added. Turn the switch off to follow it live." else "The session has ended."
            try {
                started.run(
                    onStatus = { setStatus(current, it) },
                    onText = { text ->
                        if (current == generation) {
                            // Do not read a large file faster than it can be shown
                            while (pendingChars.get() > MAX_PENDING_CHARS && current == generation && !disposed) Thread.sleep(50)
                            pending.add(text)
                            pendingChars.addAndGet(text.length.toLong())
                        }
                    },
                )
            } catch (e: Exception) {
                if (!disposed && current == generation) {
                    LOG.info("Log session for $shown failed", e)
                    ending = "The session has ended: ${e.message}"
                }
            }
            val hint = (started as? SshLogFeed)?.hint?.takeIf { it.isNotEmpty() }?.let { "  $it" }.orEmpty()
            setStatus(current, "$ending$hint  $shown")
        }
    }

    private fun setStatus(forGeneration: Int, text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && forGeneration == generation) status.text = text
        }
    }

    private fun clear() {
        lastLevel = LogLevel.NORMAL
        paintedLines.clear()
        matches.clear()
        editor.markupModel.removeAllHighlighters()
        document.setText("")
        showMatchCount()
    }

    /** Runs on the EDT a few times a second: moves the text that arrived since the last run into the editor. */
    private fun flush() {
        if (disposed || pending.isEmpty()) return
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        // The live view colours lines as they arrive; a big backlog gets line colours only. The whole-file view
        // colours nothing here: its lines are coloured when they are scrolled into view.
        val eager = !wholeFile
        val detailed = eager && pending.size <= DETAILED_LINES_PER_FLUSH
        while (text.length < MAX_CHARS_PER_FLUSH) {
            val piece = pending.poll() ?: break
            pendingChars.addAndGet(-piece.length.toLong())
            if (eager) {
                // In the live view every piece is one or more whole lines
                var lineStart = 0
                while (lineStart < piece.length) {
                    val lineEnd = piece.indexOf('\n', lineStart).let { if (it < 0) piece.length else it }
                    paint(piece.substring(lineStart, lineEnd), text.length + lineStart, detailed, spans)
                    lineStart = lineEnd + 1
                }
            }
            text.append(piece)
        }
        val lengthBefore = document.textLength
        document.insertString(lengthBefore, text)
        val start = document.textLength - text.length
        val trimmed = document.textLength != lengthBefore + text.length
        if (trimmed && wholeFile) {
            // The oldest text was dropped to stay within the size limit: line numbers moved, colour lines afresh
            paintedLines.clear()
            val keep = matches.toSet()
            editor.markupModel.allHighlighters.filter { it !in keep }.forEach { editor.markupModel.removeHighlighter(it) }
        }
        if (start >= 0) {
            val markup = editor.markupModel
            for (span in spans) {
                markup.addRangeHighlighter(start + span.from, start + span.to, span.layer, span.attributes, HighlighterTargetArea.EXACT_RANGE)
            }
            if (matchText.isNotEmpty()) addMatches(findAll(document.immutableCharSequence, matchText, maxOf(0, start - matchText.length + 1)))
        }
        if (wholeFile) paintVisibleLines() else if (follow) scrollToEnd()
    }

    /** Whole-file view: colours the lines that are on screen (and a little around them) and are not coloured yet. */
    private fun paintVisibleLines() {
        if (!wholeFile || disposed || document.lineCount == 0) return
        val area = editor.scrollingModel.visibleArea
        val first = maxOf(0, editor.xyToLogicalPosition(Point(0, area.y)).line - PAINT_MARGIN_LINES)
        val last = minOf(document.lineCount - 1, editor.xyToLogicalPosition(Point(0, area.y + area.height)).line + PAINT_MARGIN_LINES)
        var line = paintedLines.nextClearBit(first)
        if (line > last) return
        val chars = document.immutableCharSequence
        val markup = editor.markupModel
        val spans = ArrayList<Span>()
        // A stack trace takes the colour of the line it belongs to, which may be above what is being coloured
        lastLevel = levelBefore(line, chars)
        var previous = line - 1
        while (line <= last) {
            if (line != previous + 1) lastLevel = levelBefore(line, chars)
            val from = document.getLineStartOffset(line)
            spans.clear()
            paint(chars.subSequence(from, document.getLineEndOffset(line)).toString(), from, true, spans)
            for (span in spans) markup.addRangeHighlighter(span.from, span.to, span.layer, span.attributes, HighlighterTargetArea.EXACT_RANGE)
            paintedLines.set(line)
            previous = line
            line = paintedLines.nextClearBit(line + 1)
        }
    }

    /** The level a continuation line at [line] inherits: that of the nearest line above that is not a continuation. */
    private fun levelBefore(line: Int, chars: CharSequence): LogLevel {
        var above = line - 1
        var steps = 0
        while (above >= 0 && steps < LOOK_BACK_LINES) {
            val text = chars.subSequence(document.getLineStartOffset(above), document.getLineEndOffset(above)).toString()
            if (!CONTINUATION.containsMatchIn(text)) {
                lastLevel = LogLevel.NORMAL
                return levelOf(text)
            }
            above--
            steps++
        }
        return LogLevel.NORMAL
    }

    /** Adds the coloured pieces of [line], which starts at [offset]. */
    private fun paint(line: String, offset: Int, detailed: Boolean, spans: MutableList<Span>) {
        val level = levelOf(line)
        val lineAttributes = when (level) {
            LogLevel.ERROR -> palette.error
            LogLevel.WARNING -> palette.warning
            LogLevel.DEBUG -> palette.debug
            LogLevel.NORMAL -> null
        }
        if (lineAttributes != null) spans += Span(offset, offset + line.length, lineAttributes, HighlighterLayer.SYNTAX)
        if (!detailed) return
        val top = HighlighterLayer.SYNTAX + 1
        var rest = 0
        TIMESTAMP.find(line)?.let { stamp ->
            spans += Span(offset + stamp.range.first, offset + stamp.range.last + 1, palette.timestamp, top)
            rest = stamp.range.last + 1
            // syslog: "<time> <host> <program>[<pid>]:"
            SYSLOG_PROGRAM.find(line, rest)?.takeIf { it.range.first == rest }?.let { tag ->
                val program = tag.groups[1]!!.range
                spans += Span(offset + program.first, offset + program.last + 1, palette.program, top)
            }
        }
        LEVEL_WORD.find(line, rest)?.let { word ->
            val attributes = when (word.value) {
                "WARN", "WARNING" -> palette.warningWord
                "INFO", "NOTICE" -> palette.infoWord
                "DEBUG", "TRACE", "FINE", "FINER", "FINEST" -> palette.debugWord
                else -> palette.errorWord
            }
            spans += Span(offset + word.range.first, offset + word.range.last + 1, attributes, top)
        }
        if (level == LogLevel.NORMAL) {
            for (good in SUCCESS.findAll(line, rest)) {
                spans += Span(offset + good.range.first, offset + good.range.last + 1, palette.success, top)
            }
        }
    }

    private fun levelOf(line: String): LogLevel {
        val level = when {
            CONTINUATION.containsMatchIn(line) -> lastLevel
            ERROR.containsMatchIn(line) -> LogLevel.ERROR
            WARNING.containsMatchIn(line) -> LogLevel.WARNING
            DEBUG.containsMatchIn(line) -> LogLevel.DEBUG
            else -> LogLevel.NORMAL
        }
        lastLevel = level
        return level
    }

    private fun scrollToEnd() {
        editor.caretModel.moveToOffset(document.textLength)
        editor.scrollingModel.scrollTo(LogicalPosition(maxOf(0, document.lineCount - 1), 0), ScrollType.MAKE_VISIBLE)
    }

    /** Offsets of the occurrences of [text] from [from] on, ignoring case; at most [MAX_MATCHES]. */
    private fun findAll(chars: CharSequence, text: String, from: Int): List<Int> {
        val found = ArrayList<Int>()
        var index = StringUtil.indexOfIgnoreCase(chars, text, from)
        while (index >= 0 && found.size < MAX_MATCHES) {
            found += index
            index = StringUtil.indexOfIgnoreCase(chars, text, index + text.length)
        }
        return found
    }

    /**
     * Marks every occurrence of the search text, in the text and on the scrollbar, like the IDE's own search.
     * The text is searched in the background, so typing stays smooth in a large log.
     */
    private fun searchAll(jumpToFirst: Boolean) {
        val text = search.text
        val current = ++searchGeneration
        matches.forEach { editor.markupModel.removeHighlighter(it) }
        matches.clear()
        matchText = text
        showMatchCount()
        if (text.isEmpty()) return
        val chars = document.immutableCharSequence
        val stamp = document.modificationStamp
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = findAll(chars, text, 0)
            ApplicationManager.getApplication().invokeLater {
                if (disposed || current != searchGeneration) return@invokeLater
                if (stamp != document.modificationStamp) {
                    searchAll(jumpToFirst) // the log grew meanwhile: the offsets are stale, search again
                    return@invokeLater
                }
                addMatches(found)
                if (jumpToFirst) goToMatch(forward = true, fromCaret = false)
            }
        }
    }

    private fun addMatches(offsets: List<Int>) {
        val markup = editor.markupModel
        val last = matches.lastOrNull()?.takeIf { it.isValid }?.startOffset ?: -1
        for (offset in offsets) {
            if (offset <= last || matches.size >= MAX_MATCHES || offset + matchText.length > document.textLength) continue
            val highlighter = markup.addRangeHighlighter(offset, offset + matchText.length, HighlighterLayer.SELECTION - 1,
                palette.match, HighlighterTargetArea.EXACT_RANGE)
            highlighter.setErrorStripeMarkColor(palette.matchStripe)
            matches += highlighter
        }
        showMatchCount()
    }

    private fun showMatchCount(position: Int? = null) {
        matches.removeAll { !it.isValid }
        matchCount.text = when {
            matchText.isEmpty() -> ""
            matches.isEmpty() -> "0 results"
            else -> (position?.let { "${it + 1}/" }.orEmpty()) + matches.size + (if (matches.size >= MAX_MATCHES) "+" else "")
        }
    }

    /** Selects the next or previous search result relative to the caret, wrapping around at the ends. */
    private fun goToMatch(forward: Boolean, fromCaret: Boolean = true) {
        if (search.text != matchText) {
            searchDelay.cancelAllRequests()
            searchAll(jumpToFirst = true)
            return
        }
        matches.removeAll { !it.isValid }
        if (matches.isEmpty()) return
        val selection = editor.selectionModel
        val caret = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
        val index = when {
            !fromCaret -> matches.indexOfFirst { it.startOffset >= caret }.takeIf { it >= 0 } ?: 0
            forward -> matches.indexOfFirst { it.startOffset > caret }.takeIf { it >= 0 } ?: 0
            else -> matches.indexOfLast { it.startOffset < caret }.takeIf { it >= 0 } ?: matches.lastIndex
        }
        val match = matches[index]
        follow = false // otherwise the next new line would scroll the match away
        editor.caretModel.moveToOffset(match.startOffset)
        selection.setSelection(match.startOffset, match.endOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        showMatchCount(index)
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun notify(text: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Easy Snippets")
            .createNotification("Easy Snippets", text, NotificationType.INFORMATION).notify(project)
    }

    private companion object {
        const val FLUSH_MS = 200
        const val SEARCH_DELAY_MS = 250
        const val DETAILED_LINES_PER_FLUSH = 8_000
        const val MAX_CHARS_PER_FLUSH = 8_000_000
        const val MAX_PENDING_CHARS = 32_000_000L
        const val MAX_CHARS = 60_000_000
        const val MAX_MATCHES = 20_000
        const val PAINT_MARGIN_LINES = 60
        const val LOOK_BACK_LINES = 60

        // Levels as programs write them, and plain words system logs use for trouble
        val ERROR = Regex("""\b(?:ERROR|SEVERE|FATAL|CRITICAL|CRIT|EMERG|ALERT|ERR)\b|\b[A-Za-z.]*(?:Exception|Error)\b""" +
            """|(?i)\b(?:error|errors|failed|failure|fatal|denied|refused|panic|segfault|cannot|unable to|not found|killed)\b""")
        val WARNING = Regex("""(?i)\b(?:warn|warning|warnings|deprecated|timeout|timed out|retry|retrying)\b""")
        val DEBUG = Regex("""\b(?:DEBUG|TRACE|FINE|FINER|FINEST)\b""")
        val CONTINUATION = Regex("""^\s+(?:at |\.\.\. \d+ (?:more|common frames))|^Caused by: """)

        // "Nov 18 15:09:07", "2026-11-19 00:01:02,123", "2026-11-19T00:01:02.123+02:00", "[19/Nov/2026:00:01:02 +0200]", "00:01:02.123"
        val TIMESTAMP = Regex("""^\[?(?:[A-Z][a-z]{2}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}|\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?|\d{2}/[A-Z][a-z]{2}/\d{4}:\d{2}:\d{2}:\d{2}(?: [+-]\d{4})?|\d{2}:\d{2}:\d{2}(?:[.,]\d+)?)\]?""")
        val SYSLOG_PROGRAM = Regex("""\s+\S+\s+([\w./@-]+)(?:\[\d+\])?:""")
        val LEVEL_WORD = Regex("""\b(?:ERROR|SEVERE|FATAL|CRITICAL|WARN|WARNING|INFO|NOTICE|DEBUG|TRACE|FINE|FINER|FINEST)\b""")
        val SUCCESS = Regex("""(?i)\b(?:started|finished|succeeded|successfully|success|completed|reached target|listening|ready|ok)\b""")
    }
}
