package io.github.khorkovenko.easysnippets;

import com.intellij.openapi.project.Project;
import com.intellij.remote.RemoteCredentials;
import com.intellij.ssh.RemoteCredentialsUtil;
import com.intellij.ssh.SshException;
import com.intellij.ssh.channels.SftpChannel;

/**
 * Opens SFTP channels and runs commands on a host. Written in Java on purpose: from Kotlin these calls compile to
 * synthetic "$default" methods whose signatures differ between IDE versions, while the plain overloads used here
 * are the same in all of them.
 */
final class SshConnections {
    private SshConnections() {
    }

    static SftpChannel openSftp(RemoteCredentials credentials, Project project) throws SshException {
        return RemoteCredentialsUtil.connectionBuilder(credentials, project).openSftpChannel();
    }

    /** Starts {@code command} in the host's shell; its output is read from the returned process. */
    static Process exec(RemoteCredentials credentials, Project project, String command) throws Exception {
        return exec(credentials, project, command, false);
    }

    /**
     * Like {@link #exec(RemoteCredentials, Project, String)}, optionally with a terminal for the command. Hosts whose
     * sudo is configured with "requiretty" refuse to run sudo without one. With a terminal, lines end in CR LF.
     */
    static Process exec(RemoteCredentials credentials, Project project, String command, boolean terminal) throws Exception {
        return RemoteCredentialsUtil.connectionBuilder(credentials, project).execBuilder(command).withAllocatePty(terminal).execute();
    }
}
