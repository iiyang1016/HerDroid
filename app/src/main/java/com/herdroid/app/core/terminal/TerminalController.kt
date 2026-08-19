package com.herdroid.app.core.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TerminalController(private val workDir: File) {
    suspend fun execute(command: String): TerminalResult = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext TerminalResult("", 0)
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            TerminalResult(output.trimEnd(), process.waitFor())
        }.getOrElse { TerminalResult(it.message ?: "Command failed", -1) }
    }
}

data class TerminalResult(val output: String, val exitCode: Int)
