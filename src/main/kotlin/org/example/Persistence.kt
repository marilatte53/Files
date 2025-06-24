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
        const val KEY_SELECTED_FILE = "selectedFile"
    }

    val stateFile = storagePath.resolve("explorer_state.txt")

    fun read(): ExplorerState {
        // TODO: Maybe check the version file???
        val stateMap = mutableMapOf<String, String?>()
        println("DEBUG: Checking state file '${stateFile.invariantSeparatorsPathString}'")
        if (Files.exists(stateFile) && Files.isRegularFile(stateFile)) {
            println("DEBUG: State file found, proceeding to read")
            val lines = Files.readAllLines(stateFile)
            var i = 0
            println("DEBUG: Read state file, converting raw strings")
            for (lineStr in lines) {
                val lineUsable = lineStr.trim()
                if (lineUsable.isEmpty()) {
                    println("DEBUG: Line $i is empty and will not be processed")
                    continue
                }
                val lineSplit = lineUsable.split("=", limit = 2)
                val key = lineSplit[0]
                if (key.isBlank()) {
                    println("INFO: Line $i has an empty key (empty string before '=') and will be skipped")
                    continue
                }
                val value = if (lineSplit.size < 2) {
                    println("INFO: Line $i has no value assigned (no '=' found)")
                    null
                } else {
                    lineSplit[1]
                }
                println("DEBUG: Read state value '$key=$value'")
                stateMap.put(key, value)
                i++
            }
            println("Done reading state file")
        } else {
            println("INFO: State file does not exist or is not a regular file")
        }

        val currentDir = Paths.get(stateMap.get(KEY_CURRENT_DIR) ?: ExplorerController.getDefaultDir())
        // In case this is not present, it will get fixed at the initial update call in Controller
        val selectedDir = stateMap.get(KEY_SELECTED_FILE)?.let { currentDir.resolve(it) }
        return ExplorerState(currentDir, selectedDir)
    }

    // TODO: error handling, logging
    fun write(state: ExplorerState) {
        Files.createDirectories(storagePath)
        val versionFile = storagePath.resolve("version.txt")
        // TODO: Techically we should have a lock file but oh well. Only use it for single operations tho, so we can have multiple instances of the app
        if (!(Files.exists(versionFile) && Files.isRegularFile(versionFile))) versionFile.createFile()
        versionFile.writeText("1.0")
        if (!(Files.exists(stateFile) && Files.isRegularFile(stateFile))) stateFile.createFile()
        val sSelectedDir = state.selectedDir?.let {
            state.currentDir.relativize(it).invariantSeparatorsPathString
        }.orEmpty() // use orEmpty() otherwise 'null' will be stored as string
        Files.newBufferedWriter(stateFile).use {
            it.appendLine(
                KEY_CURRENT_DIR + "=" + state.currentDir.normalize().toAbsolutePath().invariantSeparatorsPathString
            )
            it.appendLine(KEY_SELECTED_FILE + "=" + sSelectedDir)
        }
    }
}