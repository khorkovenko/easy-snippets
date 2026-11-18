plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.khorkovenko.easysnippets"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // The IDE the plugin is compiled against and launched with by `runIde`
        intellijIdea("2025.3")
        // Classic terminal API and the reworked terminal API (TerminalView, shell integration)
        bundledPlugin("org.jetbrains.plugins.terminal")
        // Optional: Remote Host file tree and SSH sessions (IDEs with the FTP/SFTP Connectivity plugin)
        bundledPlugin("com.jetbrains.plugins.webDeployment")
        bundledPlugin("org.jetbrains.plugins.remote-run")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"          // 2025.3+: first version with the reworked terminal API
            untilBuild = provider { null } // no upper bound
        }
        changeNotes = """
            <p><b>1.0.0</b>: first release. Terminal commands in folders or at the top level, a shortcut popup,
            a shortcut per command, grouping of selected commands into a folder and sequential runs.</p>
        """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides { recommended() }
        // The host file tree opens its SFTP channel with RemoteCredentialsUtil.connectionBuilder, which the IDE marks
        // as internal API and offers no public replacement for. Fail on real incompatibilities only.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
    }
}
