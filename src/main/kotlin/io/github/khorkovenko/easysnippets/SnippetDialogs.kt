package io.github.khorkovenko.easysnippets

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/** Add or edit one command: description, command lines, paste-only flag. */
class SnippetDialog(project: Project, item: SnippetItem?, dialogTitle: String? = null) : DialogWrapper(project) {
    private val description = JBTextField(item?.name.orEmpty(), 40)
    private val command = JBTextArea(item?.command.orEmpty(), 6, 40)
    private val pasteOnly = JBCheckBox("Only paste into the terminal, don't press Enter", item?.pasteOnly ?: false)

    init {
        title = dialogTitle ?: if (item == null) "Add Command" else "Edit Command"
        command.font = Font(Font.MONOSPACED, Font.PLAIN, command.font.size)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hint = JBLabel("One command per line. Several lines run one after another.")
        hint.foreground = UIUtil.getContextHelpForeground()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Description:", description)
            .addLabeledComponentFillVertically("Command:", ScrollPaneFactory.createScrollPane(command))
            .addComponentToRightColumn(hint)
            .addComponent(pasteOnly)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = description

    override fun doValidate(): ValidationInfo? =
        if (command.text.isBlank()) ValidationInfo("Enter a command", command) else null

    fun createItem(): SnippetItem = SnippetItem().also { applyTo(it) }

    fun applyTo(item: SnippetItem) {
        val lines = command.text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        item.command = lines.joinToString("\n")
        item.name = description.text.trim().ifEmpty { lines.first().trim() }
        item.pasteOnly = pasteOnly.isSelected
    }
}
