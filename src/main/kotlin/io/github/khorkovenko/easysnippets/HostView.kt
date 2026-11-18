package io.github.khorkovenko.easysnippets

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.IdeFocusManager
import javax.swing.JComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.jetbrains.plugins.remotesdk.console.RunSshConsoleAction
import com.jetbrains.plugins.remotesdk.console.SshConfigConnector
import com.jetbrains.plugins.remotesdk.console.SshConsoleOptionsProvider
import com.jetbrains.plugins.remotesdk.console.SshTerminalRunnerFactory
import com.jetbrains.plugins.webDeployment.config.AccessType
import com.jetbrains.plugins.webDeployment.config.FileTransferConfig
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.config.WebServerConfig
import com.jetbrains.plugins.webDeployment.ui.WebServerToolWindowFactory
import com.jetbrains.plugins.webDeployment.ui.WebServerToolWindowPanel

private const val TERMINAL = "Terminal"
private const val REMOTE_HOST = WebServerToolWindowFactory.SERVER_BROWSER_TOOLWINDOW_ID

private fun HostAddress.matches(config: SshConfig): Boolean =
    config.host.equals(host, ignoreCase = true) && (port == null || config.port == port) &&
        (user == null || config.username.isNullOrEmpty() || config.username == user)

private fun HostAddress.matches(server: WebServerConfig): Boolean {
    val transfer = server.fileTransferConfig
    return transfer.accessType == AccessType.SFTP && host.equals(transfer.host, ignoreCase = true) &&
        (port == null || port == transfer.port)
}

private val unloadHookInstalled = AtomicBoolean()

private val deploymentAvailable: Boolean by lazy {
    val id = PluginId.getId("com.jetbrains.plugins.webDeployment")
    PluginManager.getInstance().findEnabledPlugin(id) != null
}

private val FILES_PANEL = Key.create<HostFilesPanel>("EasySnippets.filesPanel")
private val TERMINAL_COMPONENT = Key.create<JComponent>("EasySnippets.terminalComponent")

/**
 * Switches a terminal tab between its two views of one host. In an SSH tab: replaces the terminal with the host's
 * file tree, inside the same tab (adding an SFTP server for the host when there is none); the button then shows a
 * terminal icon and brings the terminal back. Invoked in the Remote Host tool window: goes to the host's SSH tab,
 * or starts an SSH session when no tab is open.
 */
class SwitchHostViewAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        // The file tree needs the FTP/SFTP Connectivity plugin; without it the button is not offered at all
        e.presentation.isEnabledAndVisible = project != null && deploymentAvailable
        if (project == null || !deploymentAvailable) return
        val showsFiles = selectedTerminalTab(project)?.getUserData(TERMINAL_COMPONENT) != null
        e.presentation.icon = if (showsFiles) AllIcons.Debugger.Console else AllIcons.Nodes.Folder
        e.presentation.text = if (showsFiles) "Easy Snippets: Show Terminal of Host" else "Easy Snippets: Show Files of Host"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val source = e.getData(PlatformDataKeys.TOOL_WINDOW)?.id ?: ToolWindowManager.getInstance(project).activeToolWindowId
        val tab = selectedTerminalTab(project)
        when {
            source == REMOTE_HOST -> showTerminal(project)
            tab != null && tab.getUserData(TERMINAL_COMPONENT) != null -> restoreTerminal(project, tab)
            else -> showFiles(project)
        }
    }

    private fun selectedTerminalTab(project: Project): Content? =
        toolWindow(project, TERMINAL)?.contentManagerIfCreated?.selectedContent

    private fun showFiles(project: Project) {
        val content = toolWindow(project, TERMINAL)?.contentManagerIfCreated?.selectedContent
        val title = content?.displayName
        val servers = GroupedServersConfigManager.getInstance(project)
        val configs = sshConfigs(project)
        val namedConfig = configs.firstOrNull { title in namesOf(it) }
        val address = if (namedConfig != null || content == null) null else addressOf(project, content)
        val sshConfig = namedConfig ?: address?.let { a -> configs.firstOrNull { a.matches(it) } }
        if (address == null && sshConfig == null) {
            notify(project, "Cannot tell which host this terminal tab is connected to. It works in Start SSH Session tabs, " +
                "and in tabs where you ran 'ssh user@host' for a host that has an SSH configuration " +
                "(Settings | Tools | SSH Configurations) or a server in Tools | Deployment.")
            return
        }
        // The server made from this very SSH configuration first; another server of the same host only as a
        // fallback, and never one that logs in as a different user
        val wantedUser = sshConfig?.username?.takeIf { it.isNotEmpty() } ?: address?.user
        val candidates = servers.flattenedServers.filter { isServerOf(it, sshConfig, address) }
        var server = candidates.firstOrNull { sshConfig != null && it.fileTransferConfig.serializedSshConfigId == sshConfig.id }
            ?: candidates.firstOrNull { candidate ->
                val serverUser = candidate.findSshConfig(project)?.username
                wantedUser == null || serverUser.isNullOrEmpty() || serverUser == wantedUser
            }
        if (server == null) {
            if (sshConfig == null) {
                notify(project, "No SSH configuration found for '$title'. Add one in Settings | Tools | SSH Configurations.")
                return
            }
            server = createServer(servers, sshConfig)
            notify(project, "Added the SFTP server '${server.getName()}' (Tools | Deployment | Configuration) for this host.",
                NotificationType.INFORMATION)
        }
        if (content == null) return
        var files = content.getUserData(FILES_PANEL)
        if (files == null || files.serverId != server.id) {
            files?.let { Disposer.dispose(it) }
            files = HostFilesPanel(project, server, content)
            content.putUserData(FILES_PANEL, files)
        }
        if (unloadHookInstalled.compareAndSet(false, true)) UnloadHooks.hooks += { restoreAllTabs() }
        content.putUserData(TERMINAL_COMPONENT, content.component)
        content.component = files
        IdeFocusManager.getInstance(project).requestFocus(files.focusTarget, true)
    }

    /** Puts the terminal back into a tab that shows the file tree. The tree stays connected for the next switch. */
    private fun restoreTerminal(project: Project, content: Content) {
        val terminal = content.getUserData(TERMINAL_COMPONENT) ?: return
        content.component = terminal
        content.putUserData(TERMINAL_COMPONENT, null)
        (content.preferredFocusableComponent ?: terminal).let { IdeFocusManager.getInstance(project).requestFocus(it, true) }
    }

    /** Before the plugin is unloaded: every tab gets its terminal back, or it would be stuck on a dead file tree. */
    private fun restoreAllTabs() {
        for (project in ProjectManager.getInstance().openProjects) {
            for (content in toolWindow(project, TERMINAL)?.contentManagerIfCreated?.contents.orEmpty()) {
                content.getUserData(TERMINAL_COMPONENT)?.let { content.component = it }
                content.putUserData(TERMINAL_COMPONENT, null)
                content.getUserData(FILES_PANEL)?.let { Disposer.dispose(it) }
                content.putUserData(FILES_PANEL, null)
            }
        }
    }

    private fun showTerminal(project: Project) {
        val server = toolWindow(project, REMOTE_HOST)?.let { panelOf(it) }?.selectedServer
        if (server == null) {
            notify(project, "Choose a server in Remote Host first.")
            return
        }
        val sshConfig = server.findSshConfig(project)
        val terminal = toolWindow(project, TERMINAL)
        if (terminal == null) {
            notify(project, "The Terminal tool window is not available in this project.")
            return
        }
        terminal.activate {
            val contents = terminal.contentManager
            val names = sshConfig?.let { namesOf(it) }.orEmpty()
            val tab = contents.contents.firstOrNull { content ->
                val address = addressOf(project, content)
                content.displayName in names || (address != null && (sshConfig?.let { address.matches(it) } ?: address.matches(server)))
            }
            when {
                tab != null -> {
                    contents.setSelectedContent(tab, true)
                    restoreTerminal(project, tab) // does nothing unless the tab shows the file tree
                }
                sshConfig != null -> RunSshConsoleAction.connectToSsh(project, SshConfigConnector(sshConfig), contents) { credentials ->
                    SshTerminalRunnerFactory.getInstance(project).createRunner(SshConsoleOptionsProvider.getInstance(project), credentials)
                }
                else -> notify(project, "'${server.getName()}' has no SSH configuration, so no SSH session can be started for it.")
            }
        }
    }

    /**
     * The host a terminal tab is connected to: from its title (Start SSH Session tabs), from the last ssh command
     * in its text (a local tab where ssh was run), or from the last ssh command a snippet sent to it. Only hosts
     * that have an SSH configuration or a deployment server count, so ordinary output is not mistaken for a host.
     */
    private fun addressOf(project: Project, content: Content): HostAddress? {
        val configs = sshConfigs(project)
        val servers = GroupedServersConfigManager.getInstance(project).flattenedServers
        fun known(address: HostAddress) = configs.any { address.matches(it) } || servers.any { address.matches(it) }
        HostAddress.fromTitle(content.displayName)?.takeIf { known(it) }?.let { return it }
        val text = readTerminalText(project, content)
        SshCommands.lastDestination(text) { known(it) }?.let { return it }
        // The connection was closed: the tab is local again, whatever a snippet sent to it earlier
        if (SshCommands.endsDisconnected(text)) return null
        return content.getUserData(SshCommands.SENT_TO_TAB)?.takeIf { known(it) }
    }

    private fun toolWindow(project: Project, id: String): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(id)

    private fun panelOf(remoteHost: ToolWindow): WebServerToolWindowPanel? =
        remoteHost.contentManagerIfCreated?.contents?.firstNotNullOfOrNull { it.component as? WebServerToolWindowPanel }

    private fun sshConfigs(project: Project): List<SshConfig> = SshConfigManager.getInstance(project).configs

    private fun namesOf(config: SshConfig): Set<String?> =
        setOf(config.name, config.presentableShortName, config.presentableFullName)

    private fun isServerOf(server: WebServerConfig, sshConfig: SshConfig?, address: HostAddress?): Boolean {
        val transfer = server.fileTransferConfig
        if (transfer.accessType != AccessType.SFTP) return false
        if (sshConfig != null && transfer.serializedSshConfigId == sshConfig.id) return true
        val host = sshConfig?.host ?: address?.host ?: return false
        val port = sshConfig?.port ?: address?.port
        return host.equals(transfer.host, ignoreCase = true) && (port == null || port == transfer.port)
    }

    private fun createServer(servers: GroupedServersConfigManager, sshConfig: SshConfig): WebServerConfig {
        val transfer = FileTransferConfig()
        transfer.accessType = AccessType.SFTP
        transfer.setSshConfig(sshConfig)
        transfer.rootFolder = "/"
        val server = WebServerConfig(WebServerConfig.getNextId())
        server.setName(sshConfig.host)
        server.fileTransferConfig = transfer
        servers.setUniqueName(server)
        servers.addServer(server)
        return server
    }

    private fun notify(project: Project, text: String, type: NotificationType = NotificationType.WARNING) {
        NotificationGroupManager.getInstance().getNotificationGroup("Easy Snippets")
            .createNotification("Easy Snippets", text, type).notify(project)
    }
}
