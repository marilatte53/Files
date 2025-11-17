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
        fun getDefaultDir(): String = System.getProperty("user.home")
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
            println("INFO: Failed to read state file, using default state")
            updateFileList() // This is also called when loadPersistentState is successful
        }
        Runtime.getRuntime().addShutdownHook(Thread(::runOnShutdown))
    }

    fun openFileOrEnterDir(path: Path?) {
        val p = path?.absolute() ?: return
        if (!p.exists())
            return
        if (p.isDirectory()) {
            state.currentDir = p
            updateFileList()
            state.directoriesAccessed.notify(path)
        } else openFileUnsafe(p)
    }

    protected fun openFileUnsafe(p: Path) {
        if (!Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().edit(p.toFile())
        } else {
            ProcessBuilder("cmd.exe", "/C", "start", p.absolutePathString()).start()
        }
    }

    /**
     * Try to enter newDir, which can be anywhere in the file system
     *
     * @return true if we could enter, false otherwise
     */
    fun tryEnterDir(path: Path): Boolean {
        try {
            val newDir = path.absolute().normalize()
            if (newDir.exists() && newDir.isDirectory()) {
                state.currentDir = newDir
                updateFileList()
                state.directoriesAccessed.notify(path)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            gui.showExceptionDialog(e)
        }
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
                    e.printStackTrace()
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
                    val fallback = Paths.get(fallbackStr).absolute().normalize()
                    state.currentDir = fallback
                    val tmp = Files.list(fallback)
                    println("INFO: Using fallback dir now")
                    tmp
                } catch (e: Exception) {
                    println("FATAL: Fallback failed. Check the fallback directory.")
                    return
                }
            }
        state.cachedFileList = files.toList()
        gui.updateFileList(newSelection)
    }

    fun addCurrentDirFavorite() {
        val curDir = currentDir()
        val newFav = ExplorerFavoriteEntry(curDir.name, curDir)
        val favs = favorites()
        if (favs.find { it.name == newFav.name } != null) {
            gui.showFavoriteExistsDialog(newFav.name)
            return
        }
        favs.add(newFav)
        println("DEBUG: Adding favorite $newFav and updating file from cache")
        try {
            persistence.ensureFavoritesFile()
            persistence.writeFavorites(favs)
            println("DEBUG: Updated favorites")
        } catch (e: Exception) {
            e.printStackTrace()
            println("DEBUG: Failed to update favorites")
        }
        // GUI will update itself
    }

    fun editFavoritesExternally() {
        // ensure the file exists
        val file = persistence.favoritesFile()
        try {
            persistence.ensureFavoritesFile()
            openFileUnsafe(file)
        } catch (e: Exception) {
            println("INFO: Could not ensure that favorites file exists")
            e.printStackTrace()
            gui.showFavoriteFileExceptionDialog(e)
        }
    }

    fun currentDir() = state.currentDir
    fun fileList() = state.cachedFileList
    fun favorites(): MutableList<ExplorerFavoriteEntry> {
        // read last changed time
        persistence.ensureFavoritesFile()
        val timeModified = persistence.favoritesFile().getLastModifiedTime().toInstant()
        val timeCached = state.favoriteCacheTime
        if (timeCached != null && !timeCached.isBefore(timeModified)) {
            println("DEBUG: Favorite cache is updated, no need to reload")
            return state.favoriteCache
        }
        println("DEBUG: Favorite cache is outdated, reloading now")
        val newFavs = persistence.loadFavorites()
        if (newFavs == null) {
            println("DEBUG: Favorites could not be updated, using outdated cache for now")
            return state.favoriteCache
        }
        println("DEBUG: Favorite cache updated")
        state.favoriteCache = newFavs
        state.favoriteCacheTime = timeModified
        return state.favoriteCache
    }
    
    fun recentDirsAT(max: Long) = state.directoriesAccessed.sortedByAccessTime(max)
    fun recentDirsAC(max: Long) = state.directoriesAccessed.sortedByAccessCount(max)

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
        // favorites are handled during runtime
    }
}