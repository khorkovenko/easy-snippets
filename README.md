# Easy Snippets

Saved terminal commands for JetBrains IDEs, plus two tools for working with remote hosts: a file tree inside the terminal tab and a live log watcher.

Keep the commands you type again and again in one place, run them with a click or a shortcut, chain them into sequences that wait for each step to finish, and follow a log file on a server without leaving the IDE.

## Requirements

| | |
|---|---|
| IDE | IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, GoLand, Rider and other JetBrains IDEs, **2025.3 or later** |
| Terminal | Classic and new (reworked) terminal, local shells and SSH sessions |
| Host files and Log Watcher | An IDE with SSH support and the bundled **FTP/SFTP Connectivity** plugin (IntelliJ IDEA Ultimate, PyCharm Professional, WebStorm, PhpStorm and the like). In other IDEs these two features are hidden; everything else works |
| Browser console logs | Chrome, Edge or Chromium installed on the same computer |

## Install

The plugin is not on the JetBrains Marketplace yet. Build the zip (see [Build](#build)) and install it with **Settings | Plugins | ⚙ | Install Plugin from Disk…**

Open the tool window from **View | Tool Windows | Easy Snippets**. It has two tabs: **Snippets** and **Log Watcher**.

## Quick start

1. In the Snippets tab press **+** and enter a description and a command, for example `List files` and `ls -la`.
2. Select it and press **Enter** (or double-click). The command is typed into the selected tab of the Terminal tool window and run. If no terminal tab is open, one is opened.
3. Select the command and click the **star** to make it a favourite.
4. Press **Ctrl+Alt+Shift+E** anywhere in the IDE: a popup lists your favourites. Type to filter, Enter to run.

## Snippets

### Organising commands

- Every command is a **description** and a **command**, shown as `description - command`.
- Commands live in **folders** (nested as deep as you like) or at the top level.
- **Group into Folder** puts the selected commands into a new folder. **Move to Folder** moves a selection elsewhere or back to the top level.
- **Import / Export as JSON**: export everything or only the selection; import adds to what you have or replaces it.

### Running commands

| You want to | Do this |
|---|---|
| Run one command | Select it and press Enter, or double-click |
| Run several in order | Select several commands, or a folder, and press Enter |
| Save a sequence as one command | Select the commands and click **Create Chained Command** |
| Write a sequence by hand | Put one command per line in the command field |
| Only type a command, without Enter | Tick **Only paste into the terminal** when editing it |
| Copy instead of running | Turn on **Copy Instead of Insert** on the toolbar; Run then copies the command to the clipboard |

### Favourites and shortcuts

- **Favourites popup:** Ctrl+Alt+Shift+E is the plugin's only default shortcut. Change it in **Settings | Keymap**, action "Easy Snippets: Favourites...". The same popup is on the Terminal tool window's title bar and in the Tools menu.
- **A shortcut for one command:** select a command or folder and click **Assign Shortcut**. Only commands you give a shortcut to appear in Settings | Keymap, so the list stays short.

### How a sequence waits for each step

A sequence sends the next command only when the previous one has finished. How it knows depends on the terminal:

- **Local shell in the new terminal** (bash, zsh, fish, PowerShell with shell integration): the shell reports the end of each command and its exit code. With **Stop When a Command Fails** on, a failed command skips the rest.
- **SSH sessions, the classic terminal, and sequences that open another shell** (`sudo su`, `ssh host`, `docker exec`, `exit` and similar): the plugin watches the screen and continues when a prompt is back. While a command is running or asking for input (a password, a `read`, a yes/no question) nothing is sent, so what you type goes to that command.

A prompt is recognised when the cursor line ends with `$`, `#`, `>` or `%`, or looks like the prompt the sequence started at. If your prompt is not recognised, the sequence waits; cancel it from the status bar. Stop When a Command Fails does not apply in this mode, because a remote shell does not report exit codes.

## Host files in the terminal tab

In a terminal tab that is connected to a host over SSH, click the **folder button** on the Terminal title bar. The tab shows the host's file tree instead of the terminal; the button turns into a terminal icon that brings the terminal back. The SSH session keeps running meanwhile.

- Works in **Start SSH Session** tabs and in local tabs where you ran `ssh user@host`, as long as the host has an entry in **Settings | Tools | SSH Configurations**.
- **Open and edit:** double-click a file. It opens in the IDE's remote-file editor; upload your changes from the banner at the top of the editor.
- **Download:** **Copy Download Command** copies an `scp` command for the selection. Paste it in a local terminal and the file is saved into that terminal's folder.
- **Search:** by default the field searches all sub-folders of the selected folder on the host (it runs `find` there, up to 500 results). Two switches beside it change that: one limits the search to folders you have already opened, the other turns searching off. In every mode, a full path such as `/var/log` followed by Enter jumps to that path.
- If the host has no SFTP server in **Tools | Deployment** yet, the plugin adds one from its SSH configuration and tells you.

The tree reads the host as the user of the SSH configuration, without sudo, so root-only files cannot be opened there.

## Log Watcher

The second tab follows logs live and colours them. Enter what to follow in the **Log** field, pick a **Host**, press **Watch**. Each log opens in a tab of its own.

### What the Log field accepts

| You enter | What is followed |
|---|---|
| A file path, `/var/log/messages` | That file on the chosen host |
| A viewer command, `less /path/app.log` | The file it names |
| Any command that prints a log, `journalctl -f`, `docker logs -f app` | The output of that command on the host |
| A page address, `localhost:3000` | The browser console of that page, on your computer |

- The **S** button at the end of the field lists your saved commands and puts the chosen one into the field.
- **+**, pencil and **−** save, edit and remove logs of your own. Below them the drop-down keeps the last 20 things you watched; choosing an entry also selects the host it was last watched on.
- **Hosts** are the IDE's SSH configurations; the gear opens them. The plugin opens its own SSH connection, so no terminal tab is needed.
- **sudo** is ticked by default, for files only root can read. It needs sudo on the host to work without a password prompt.
- The **Watch Log** button on the Snippets toolbar sends the selected command to the Log field and starts watching if its host is already known.

### In each log tab

- **Colours:** errors red, warnings yellow, debug grey; timestamps dimmed; program names and level words bold; words such as "started" and "successfully" green. A stack trace keeps the colour of its error.
- **Search:** every occurrence is highlighted in the text and marked on the scrollbar, with a counter. Enter and the arrows move between results.
- **Show the Whole File** is a switch. Off: the last 5000 lines, growing live. On: the complete file as it is at that moment, with no new lines added until you turn the switch off.
- **Copy Download Command** (an `scp` command for the file), **Copy All**, **Scroll to the End**, **Soft-Wrap**, **Clear**, **Close Session**. Text can be selected and copied as in any editor.

A log view keeps roughly the last 60 MB of text; older lines are dropped as new ones arrive.

### Browser console

For a page address the plugin reads console messages, uncaught exceptions and browser log entries through the browser's debugging connection.

- A browser started the normal way does not accept such connections, so the plugin starts Chrome or Edge **with a separate, clean profile** and opens the page there. Your everyday browser and its logins are not touched; use the page in the new window.
- It uses debugging port **9339**. If a browser is already listening there, the plugin attaches to it instead of starting another.
- Reload the page to see what it logs while loading.

## JSON format

```json
{
  "version": 1,
  "snippets": [
    { "description": "List files", "command": "ls -la", "favorite": true },
    { "folder": "Git", "items": [
      { "description": "Status", "command": "git status" },
      { "description": "Commit", "command": "git commit -m \"\"", "pasteOnly": true }
    ] }
  ]
}
```

A command with several lines in `command` is a sequence. Exported files contain your commands as plain text, including anything secret you put in them.

## Limitations

- The host file tree, its search and the Log Watcher use parts of the IDE's SSH support that JetBrains marks as internal or experimental. They work in the versions listed above but may need an update after a new IDE release.
- Sequences over SSH rely on recognising the prompt; an unusual prompt makes the sequence wait instead of continuing.
- Shortcuts assigned to single commands are stored in the IDE keymap by command. Commands imported from JSON are new commands and need their shortcuts assigned again.

## Build

Nothing needs to be installed beforehand: the Gradle wrapper downloads Gradle, and JDK 21 is downloaded automatically.

```
./gradlew buildPlugin   # builds the installable zip into build/distributions/
./gradlew runIde        # starts a sandbox IDE with the plugin installed
./gradlew verifyPlugin  # checks compatibility with recent IDE versions
```

## Project layout

```
build.gradle.kts                         IntelliJ Platform Gradle Plugin config (target IDE, signing, publishing)
src/main/resources/META-INF/plugin.xml   plugin ID, description, tool window, actions, default shortcut
src/main/kotlin/io/github/khorkovenko/easysnippets/
    Snippets.kt          the command / folder model and its storage (easySnippets.xml in the IDE config)
    SnippetsPanel.kt     the Snippets tab: tree, toolbar, preview
    SnippetDialogs.kt    Add / Edit Command dialog
    SnippetActions.kt    the favourites popup opened by the shortcut
    SnippetShortcuts.kt  per-command actions for commands that were given a shortcut
    SnippetRunner.kt     sends commands to the classic or the new terminal, runs sequences
    SnippetTransfer.kt   JSON import and export
    LogWatcher.kt        the Log Watcher tab and its log views
    LogFeeds.kt          where log lines come from: a command over SSH, or a browser console
    LogSources.kt        saved logs, what the Log field means, shared helpers
    HostView.kt          finds the host of a terminal tab and switches the tab between terminal and file tree
    HostFilesPanel.kt    the remote file tree shown inside the terminal tab
    SshCommands.kt       reads the host from a typed ssh command
src/main/java/.../SshConnections.java    opens SFTP channels and runs commands on a host
```

## License

See [LICENSE](LICENSE).
