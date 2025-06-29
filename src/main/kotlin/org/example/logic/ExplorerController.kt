package org.example.logic

import org.example.gui.ExplorerGUI
import org.example.isTrashSupported
import org.example.persistence.ExplorerPersistentState
import org.example.persistence.Persistence
import java.awt.Desktop
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

class ExplorerController {
    companion object {
        fun getDefaultDir(): String = System.getProperty("user.dir")
    }

    protected var state: ExplorerState
    var gui: ExplorerGUI
        protected set
    val persistence = Persistence(Paths.get("files_explorer_persistence"))

    init {
        this.gui = ExplorerGUI(this)
        // Set default state first, so when reading from file fails, we already have a default
        this.state = ExplorerState(Paths.get(getDefaultDir()))
        try {
            val readState = persistence.read()
            loadPersistentState(readState)
        } catch (e: Exception) {
            updateFileList() // This is also called when loadPersistent state is successful
            println("INFO: Failed to read state file, using default state")
        }
        Runtime.getRuntime().addShutdownHook(Thread(::runOnShutdown))
    }

    fun openFileOrEnterDir(path: Path?) {
        val p = path?.absolute() ?: return
        if (p.isDirectory()) {
            state.currentDir = p
            updateFileList()
        } else {
            if (!Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().edit(p.toFile())
            } else {
                ProcessBuilder("cmd.exe", "/C", "start", p.absolutePathString()).start()
            }
        }
    }

    /**
     * Try to enter newDir, which can be anywhere in the file system
     *
     * @return true if we could enter, false otherwise
     */
    fun tryEnterDir(path: String): Boolean {
        try {
            val newDir = Paths.get(path).absolute().normalize()
            if (newDir.exists() && newDir.isDirectory()) {
                state.currentDir = newDir
                updateFileList()
                return true
            }
        } catch (_: Exception) {
        }
        // TODO: More information? The dir probably didn't exist or the path is terrbily malformed
        return false
    }

    @OptIn(ExperimentalPathApi::class)
    fun tryDeletePath(path: Path? = null, trySelect: Path? = null) {
        if (path == null) return
        try {
            if (isTrashSupported()) {
                if (!Desktop.getDesktop().moveToTrash(path.toFile())) {
                    gui.showDeletionFailedDialog(path)
                    return
                }
            } else {
                if (!gui.confirmTrashNotSupportedDialog())
                    return
                try {
                    path.deleteRecursively()
                } catch (e: IOException) {
                    gui.showDeletionFailedDialog(path)
                    return
                }
            }
            updateFileList(trySelect)
        } catch (e: Exception) {
            gui.showExceptionDialog(e)
        }
    }

    fun tryLeaveCurrentDir() {
        if (state.currentDir.parent == null) return // In case the current dir is a drive 
        val oldDir = state.currentDir
        state.currentDir = state.currentDir.parent
        updateFileList(oldDir)
    }

    /**
     * Create new dir
     *
     * @return true if the directory was created, false if the directory already exists
     * @throws
     */
    fun tryCreateDir(dirName: String) {
        val newDir = state.currentDir.resolve(dirName)
        // The try-catch will handle it and we give additional feedback to the user automatically
//        if (newDir.isDirectory() && newDir.exists()) return false
        try {
            newDir.createDirectory()
            updateFileList(newDir)
        } catch (e: Exception) {
            println("INFO: Failed to create dir. ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    fun tryCreateFile(fileName: String) {
        val newFile = state.currentDir.resolve(fileName)
        try {
            newFile.createFile()
            updateFileList(newFile)
        } catch (e: Exception) {
            println("INFO: Failed to create dir. ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * Reload the file list from disk and adjust the GUI accordingly
     *
     * @param newSelection default is current dir. So if we just want to refresh the file list, we don't need to pass
     *    anything
     */
    fun updateFileList(newSelection: Path? = null) {
        val files =
            try {
                Files.list(state.currentDir)
            } catch (e: Exception) {
                println("INFO: ${e::class.simpleName} Cannot list files in currentDir ('${state.currentDir}'): ${e.message}")
                val fallbackStr = getDefaultDir()
                println("INFO: Trying fallback dir ('$fallbackStr')")
                try {
                    val fallback = Paths.get(fallbackStr)
                    state.currentDir = fallback
                    val tmp = Files.list(fallback)
                    println("INFO: Using fallback dir now")
                    tmp
                } catch (e: Exception) {
                    println("FATAL: Fallback failed. Check the fallback directory.")
                    return
                }
            }
        // Do we actually need this? Leave it for now
        state.cachedFileList = files.toList()
        gui.updateFileList(newSelection)
    }

    fun currentDir() = state.currentDir
    fun fileList() = state.cachedFileList

    /** Set the current state and GUI state according to the PersistentState */
    protected fun loadPersistentState(pState: ExplorerPersistentState) {
        val newState = ExplorerState(pState.currentDir)
        this.state = newState
        // This will update the GUI file list as well as selection
        updateFileList()
        // Adjust selection, in case this doesn't work, we already have a default selection
        gui.trySelectInFileList(pState.selectedPath)
    }

    protected fun makePersistentState(): ExplorerPersistentState {
        return ExplorerPersistentState(state.currentDir, gui.selectedPath())
    }

    protected fun runOnShutdown() {
        persistence.write(makePersistentState())
    }
}