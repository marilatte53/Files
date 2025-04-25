package org.example

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createFile
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText

class Persistence(
    val storagePath: Path
) {
    companion object {
        const val KEY_CURRENT_DIR = "currentDir"
    }

    val stateFile = storagePath.resolve("explorer_state.txt")

    fun read(): ExplorerState {
        var curDir: String? = null
        if (Files.exists(stateFile) && Files.isRegularFile(stateFile)) {
            val lines = Files.readAllLines(stateFile)
            for (lineRaw in lines) {
                val line = lineRaw.trim().split("=", limit = 2)
                if (line.size < 2) throw Exception("Invalid state file")
                if (line[0] == KEY_CURRENT_DIR)
                    curDir = line[1]
            }
        }
        return ExplorerState(Paths.get(curDir ?: System.getProperty("user.home")))
    }

    // TODO: error handling
    fun write(state: ExplorerState) {
        // Get portable storage path
        Files.createDirectories(storagePath)
        val versionFile = storagePath.resolve("version.txt")
        if (!(Files.exists(versionFile) && Files.isRegularFile(versionFile))) versionFile.createFile()
        versionFile.writeText("1.0")
        if (!(Files.exists(stateFile) && Files.isRegularFile(stateFile))) stateFile.createFile()
        Files.newBufferedWriter(stateFile).use {
            it.write("currentDir=" + state.currentDir.normalize().toAbsolutePath().invariantSeparatorsPathString)
        }
    }
}