package mar.io.logic

import mar.io.gui.ExplorerGUI
import mar.io.isTrashSupported
import mar.io.persistence.ExplorerPersistentState
import mar.io.persistence.StorageManager
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
    val storage = StorageManager(Paths.get("files_explorer_persistence"))

    init {
        this.gui = ExplorerGUI(this)
        // TODO: why is this not in catch clause
        this.state = ExplorerState(this, Paths.get(getDefaultDir()))
        try {
            val readState = storage.read()
            loadPersistentState(readState)
        } catch (e: Exception) {
            println("INFO: Failed to read state file, using default state")
            updateFileList() // This is also called when loadPersistentState is successful
        }
        Runtime.getRuntime().addShutdownHook(Thread(::runOnShutdown))
    }

    fun enterOrExecute(path: Path) {
        val p = path.absolute().normalize()
        if (!p.exists())
            return
        if (p.isDirectory()) tryEnterDir(p)
        else openFileUnsafe(p)
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
    fun tryEnterDir(path: Path): Boolean =
        runCatching {
            val newDir = path.absolute().normalize()
            if (!newDir.exists())
                return@runCatching false
            if (currentDir().isSameFileAs(newDir))
                return@runCatching true
            if (newDir.isDirectory()) {
                state.currentDir = newDir
                state.directoriesAccessed.readAndWrite { it.notify(path) }
                return@runCatching true
            }
            return@runCatching false
        }.onSuccess {
            gui.clearFilter()
            updateFileList()
        }.getOrElse {
            it.printStackTrace()
            gui.showExceptionDialog(it)
            return@getOrElse false
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
     * @return true if the directory was created, false if the directory
     *    already exists
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
     * @param newSelection default is current dir. So if we just want to
     *    refresh the file list, we don't need to pass anything
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
        var favs = favorites()
        // TODO: show name dialog with validation for already existing names
        val duplicateFav = favs.find { it.name == newFav.name }
        if (duplicateFav != null) {
            println("DEBUG: New favorite $newFav has a duplicate (by name): $duplicateFav")
            gui.showFavoriteExistsDialog(newFav.name)
            return
        }
        println("DEBUG: Adding favorite $newFav")
        favs = favs.toMutableList()
        favs.add(newFav)
        state.favorites.setAndWrite(favs)
        // GUI will update itself
    }

    fun editFavoritesExternally() {
        try {
            // ensure the file exists
            storage.favorites.ensureExistence()
            openFileUnsafe(storage.favorites.path)
        } catch (e: Exception) {
            println("INFO: Could not open favorite file externally")
            e.printStackTrace()
            gui.showFavoriteFileExceptionDialog(e)
        }
    }

    fun currentDir() = state.currentDir
    fun fileList() = state.cachedFileList
    fun favorites(): List<ExplorerFavoriteEntry> = state.favorites.readAndGet()

    fun recentDirsAT(max: Long) = state.directoriesAccessed.readAndGet().sortedByAccessTime(max)
    fun recentDirsAC(max: Long) = state.directoriesAccessed.readAndGet().sortedByAccessCount(max)

    /** Set the current state and GUI state according to the PersistentState */
    protected fun loadPersistentState(pState: ExplorerPersistentState) {
        val newState = ExplorerState(this, pState.currentDir)
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
        storage.write(makePersistentState())
        // favorites are handled during runtime
    }
}