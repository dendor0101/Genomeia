package io.github.some_example_name.lwjgl3.mcp

import io.github.some_example_name.lwjgl3.Lwjgl3Launcher
import java.nio.file.Path

object McpSetup {
    @JvmStatic fun printConfig() {
        val jar = Path.of(Lwjgl3Launcher::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath()
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        fun quote(s: String) = if (windows) "'" + s.replace("'", "''") + "'" else "'" + s.replace("'", "'\"'\"'") + "'"
        val command = quote(java.toString()) + " -jar " + quote(jar.toString()) + " --mcp"
        println(if (windows) "PowerShell" else "Shell")
        println("codex mcp add genomeia -- $command")
        println("claude mcp add --scope user --transport stdio genomeia -- $command")
        println("\nClaude Desktop (mcpServers):")
        println(obj("mcpServers" to obj("genomeia" to obj("command" to java.toString(), "args" to listOf("-jar", jar.toString(), "--mcp")))))
    }
}
