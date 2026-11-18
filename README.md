# Easy Snippets

An IntelliJ Platform plugin for saved terminal commands. It works in IntelliJ IDEA, PyCharm, WebStorm and the other JetBrains IDEs, version 2025.3 or later, with the classic terminal, the new (reworked) terminal and SSH session tabs.

## Features

- **Commands are stored as "description - command"**, in folders (nested too) or at the top level without any folder.
- **Favourites popup:** press **Ctrl+Alt+Shift+E** (the only default shortcut; change it in Settings | Keymap, action "Easy Snippets: Favourites...") to see your favourite commands. Type to filter, Enter to run. The last entry opens the Easy Snippets tool window. Mark favourites with the star button in the tool window. The same popup is on the Terminal tool window's title bar and in the Tools menu.
- **A shortcut for a command:** select a command or folder and click Assign Shortcut. It becomes an action "Easy Snippets: <description>" and the keymap dialog opens on it. Only commands that have a shortcut are listed in Settings | Keymap; closing the dialog without a shortcut removes the action again.
- **Group into folder:** select several commands in the tool window and click Group into Folder. Move to Folder moves a selection into another folder or back to the top level.
- **Run several commands one after another:**
  - select several commands or a folder in the tool window and press Enter
  - **Create Chained Command**: select several commands and click the button to save them as one command (one line per step), which can be a favourite too
  - a command with several lines runs its lines in order
- **Paste only:** a command can be typed into the prompt without Enter, so you can finish it by hand.
- **Import and export as JSON:** export everything or the selection, import to add or to replace.
- **Terminal ⇄ files of a host:** in a terminal tab connected to a host over SSH (a Start SSH Session tab, or a local tab where you ran `ssh user@host`) press the folder button on the Terminal title bar (no default shortcut; assign one in Settings | Keymap to "Easy Snippets: Show Files of Host"). The tab shows the host's file tree instead of the terminal; each folder is listed with one request, so big folders open quickly. Double-click a file to edit it in the IDE's remote-file editor. The search field looks for names in all sub-folders of the selected folder on the host (it runs `find` there and shows up to 500 matches as a tree); the two switches beside it turn deep search off (then it only filters the folders you have opened) or turn searching off altogether. In every mode a full path such as `/var/log` + Enter jumps to that path. **Copy Download Command** copies an `scp` command for the selection: paste it in a local terminal and the file is saved into that terminal's folder. The button turns into a terminal icon; press it to get the terminal back (the session keeps running meanwhile). If the host has no SFTP server in Tools | Deployment yet, one is added from its SSH configuration. Needs an IDE with the FTP/SFTP Connectivity plugin (IntelliJ IDEA Ultimate, PyCharm Pro, WebStorm, PhpStorm...).
- **Insert or copy:** the "Copy Instead of Insert" switch on the tool window toolbar decides what Run does. Off: the command is typed into the terminal. On: it is copied to the clipboard and you paste it yourself.
- **Adaptive tool window:** docked left or right, the toolbar is on top and the preview is under the tree; docked at the bottom, the toolbar is on the left and the preview is beside the tree.

Open it from **View | Tool Windows | Easy Snippets**.

### How sequences wait for the previous command

- New terminal with shell integration (local bash, zsh, fish, PowerShell): the plugin waits for the shell to report that the command has finished, then sends the next one. With "Stop When a Command Fails" on, a non-zero exit code skips the rest.
- SSH sessions (also `ssh` typed in a local tab), the classic terminal, shells without integration, and any sequence that opens a shell before its last command (`sudo su`, `ssh host`, `exit`...): the plugin sends a command, watches the terminal and sends the next one only when a prompt is back. While a command runs or asks for input (a `read`, a password, a y/n question), nothing is sent, so what you type goes to that command; the sequence continues when the prompt returns. A prompt is recognised when the cursor line ends with `$`, `#`, `>` or `%`, or looks like the prompt the sequence started at. If your prompt is not recognised the sequence waits; cancel it in the status bar. "Stop When a Command Fails" does not apply in this mode.

Commands go to the selected tab of the Terminal tool window. If there is no tab, a new one is opened.

### JSON format

```json
{
  "version": 1,
  "snippets": [
    { "description": "List files", "command": "ls -la" },
    { "folder": "Git", "items": [
      { "description": "Status", "command": "git status" },
      { "description": "Commit", "command": "git commit -m \"\"", "pasteOnly": true }
    ] }
  ]
}
```

## Build and run

Requirements: nothing to install beforehand. The Gradle wrapper downloads Gradle, and JDK 21 is downloaded automatically.

```
./gradlew runIde        # starts a sandbox IDE with the plugin installed
./gradlew buildPlugin   # builds the installable zip into build/distributions/
./gradlew verifyPlugin  # checks compatibility with recent IDE versions
```

To install a build in your own IDE: Settings | Plugins | ⚙ | Install Plugin from Disk…, then pick the zip.

## Project layout

```
build.gradle.kts                         IntelliJ Platform Gradle Plugin config (target IDE, signing, publishing)
src/main/resources/META-INF/plugin.xml   plugin ID, description, tool window, actions, default shortcut
src/main/kotlin/io/github/khorkovenko/easysnippets/
    Snippets.kt          the command / folder model and its storage (easySnippets.xml in the IDE config)
    SnippetRunner.kt     sends commands to the classic or the new terminal, runs sequences
    SnippetActions.kt    the favourites popup opened by the shortcut
    SnippetShortcuts.kt  per-command actions for commands that were given a shortcut
    SnippetsPanel.kt     the tool window: tree, toolbar, preview, adaptive layout
    SnippetDialogs.kt    Add / Edit Command dialog
    SnippetTransfer.kt   JSON import and export
    HostView.kt          finds the host of a terminal tab and switches the tab between terminal and file tree
    HostFilesPanel.kt    the remote file tree shown inside the terminal tab
    SshCommands.kt       reads the host from a typed ssh command
```
