package mar.io.persistence

import mar.io.logic.ExplorerController
import mar.io.logic.ExplorerFavoriteEntry
import mar.io.logic.ExplorerState
import mar.io.persistence.StorageManager.Companion.FILE_EXPLORER_STATE
import mar.io.persistence.StorageManager.Companion.KEY_CURRENT_DIR
import mar.io.persistence.StorageManager.Companion.KEY_SELECTED_FILE
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.*

class StorageManager(
    val storagePath: Path
) {
    companion object {
        const val FILE_EXPLORER_STATE = "explorer_state.txt"
        const val FILE_VERSION = "version.txt"

        const val KEY_CURRENT_DIR = "currentDir"
        const val KEY_SELECTED_FILE = "selectedFile"
    }

    val favorites = object : ResourceFile<List<ExplorerFavoriteEntry>>("favorites", "favorites.txt") {
        override fun getUpdatedResource(
            lines: List<String>,
            resource: List<ExplorerFavoriteEntry>
        ): List<ExplorerFavoriteEntry> {
            val map = LinkedHashMap<String, String?>()
            convertKeyValueLines(map, lines)
            val favs = map.filter { it.value != null }
                .map { ExplorerFavoriteEntry(it.key, Path.of(it.value!!)) }
            return favs.toMutableList()
        }

        override fun writeResource(writer: Writer, resource: List<ExplorerFavoriteEntry>): Boolean {
            for (favorite in resource)
                writer.appendLine(favorite.name + "=" + favorite.path.invariantSeparatorsPathString)
            return true
        }
    }

    val directoriesAccessed =
        object : ResourceFile<ExplorerState.DirectoriesAccessed>(
            "directoriesAccessed",
            "directories_accessed.txt"
        ) {
            override fun writeResource(
                writer: Writer,
                resource: ExplorerState.DirectoriesAccessed
            ): Boolean {
                val dirsAT = resource.sortedByAccessTime(100)
                for (dirPath in dirsAT) {
                    // should already be in the proper format
                    writer.appendLine(dirPath.invariantSeparatorsPathString)
                }
                return true
            }

            override fun getUpdatedResource(
                lines: List<String>,
                resource: ExplorerState.DirectoriesAccessed
            ): ExplorerState.DirectoriesAccessed? {
                resource.clearEntries()
                resource.setInitialEntries(lines.map { Paths.get(it) })
                return resource
            }
        }

    val stateFile: Path = storagePath.resolve(FILE_EXPLORER_STATE)

    fun read(): ExplorerPersistentState {
        // TODO: Maybe check the version file???
        println("DEBUG: Checking state file '${stateFile.invariantSeparatorsPathString}'")
        val stateMap = mutableMapOf<String, String?>()
        if (Files.exists(stateFile) && Files.isRegularFile(stateFile)) {
            println("DEBUG: State file found, proceeding to read")
            convertKeyValueLines(stateMap, Files.readAllLines(stateFile))
        } else println("INFO: State file does not exist or is not a regular file")
        val currentDir = Paths.get(stateMap[KEY_CURRENT_DIR] ?: ExplorerController.getDefaultDir())
        // In case this is not present, it will get fixed at the initial update call in Controller
        val selectedDir = stateMap[KEY_SELECTED_FILE]?.let { currentDir.resolve(it) }
        val state = ExplorerPersistentState(currentDir, selectedDir)
        return state
    }

    protected fun convertKeyValueLines(buffer: MutableMap<String, String?>, lines: List<String>) {
        var i = 0
        println("DEBUG: Converting ${lines.size} key-value lines")
        for (lineStr in lines) {
            val lineUsable = lineStr.trim()
            if (lineUsable.isEmpty()) {
                println("DEBUG: Line $i/${lines.size} is empty and will not be processed")
                continue
            }
            val lineSplit = lineUsable.split("=", limit = 2)
            val key = lineSplit[0]
            if (key.isBlank()) {
                println("DEBUG: Line $i/${lines.size} has an empty key (empty string before '=') and will be skipped")
                continue
            }
            val value = if (lineSplit.size < 2) {
                println("DEBUG: Line $i/${lines.size} has no value assigned (no '=' found), will be set to null")
                null
            } else {
                lineSplit[1]
            }
            println("DEBUG: Read state value '$key=$value'")
            buffer.put(key, value)
            i++
        }
    }

    protected fun ensureRegularFileExists(path: Path): Boolean {
        if (!path.exists()) {
            try {
                path.parent.createDirectories()
                path.createFile()
            } catch (e: Exception) {
                println(
                    "INFO: ${e::class.simpleName} Failed to create parent directories for file " +
                            "'(${path.invariantSeparatorsPathString})': ${e.message}"
                )
                return false
            }
        }
        if (!path.isRegularFile()) return false
        return true
    }

    // TODO: error handling, logging
    fun write(state: ExplorerPersistentState) {
        Files.createDirectories(storagePath)
        val versionFile = storagePath.resolve(FILE_VERSION)
        // TODO: Techically we should have a lock file but oh well. Only use it for single operations tho, so we can have multiple instances of the app
        if (!(Files.exists(versionFile) && Files.isRegularFile(versionFile))) versionFile.createFile()
        versionFile.writeText("1.0")
        if (!(Files.exists(stateFile) && Files.isRegularFile(stateFile))) stateFile.createFile()
        val sSelectedDir = state.selectedPath?.let {
            state.currentDir.relativize(it).invariantSeparatorsPathString
        }.orEmpty() // use orEmpty() otherwise 'null' will be stored as string
        Files.newBufferedWriter(stateFile).use {
            it.appendLine(
                KEY_CURRENT_DIR + "=" + state.currentDir.invariantSeparatorsPathString
            )
            it.appendLine("$KEY_SELECTED_FILE=$sSelectedDir")
        }
    }
    
    abstract inner class ResourceFile<R>(
        val name: String,
        val relativePathString: String
    ) {
        val path: Path = storagePath.resolve(relativePathString)

        protected abstract fun writeResource(writer: Writer, resource: R): Boolean
        protected abstract fun getUpdatedResource(lines: List<String>, resource: R): R?

        fun writeResourceToFile(resource: R): Boolean {
            if (!ensureExistence())
                return false
            return Files.newBufferedWriter(path).use { writeResource(it, resource) }
        }

        fun getLastModifiedTime(): Instant? {
            if (!path.isRegularFile())
                return null
            return path.getLastModifiedTime().toInstant()
        }

        fun readResourceFromFile(resource: R): R? {
            if (!path.exists() || !path.isRegularFile())
                return null
            return getUpdatedResource(Files.readAllLines(path), resource)
        }

        fun ensureExistence(): Boolean = ensureRegularFileExists(path)
    }
}