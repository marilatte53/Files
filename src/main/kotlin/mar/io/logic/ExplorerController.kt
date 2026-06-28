package mar.io.logic

import com.sun.jna.platform.FileUtils
import mar.io.gui.ExplorerGUI
import mar.io.persistence.ExplorerPersistentState
import mar.io.persistence.StorageManager
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.swing.JFrame
import kotlin.io.path.*

class ExplorerController(
    val frame: JFrame
) {
    companion object {
        fun getFallbackPathStr(): String = System.getProperty("user.home")
        fun getFallbackPath(): Path = Paths.get(getFallbackPathStr()).toRealPath()
    }

    protected var state: ExplorerState
    var gui: ExplorerGUI
        protected set
    val storage = StorageManager(Paths.get("files_explorer_persistence"))

    init {
        this.gui = ExplorerGUI(this)
        try {
            val readState = storage.read()
            this.state = ExplorerState(this, readState.currentDir)
            gui.trySelectFile(readState.selectedPath)
        } catch (e: Exception) {
            println("INFO: Failed to read state file, using default state")
            this.state = ExplorerState(this, getFallbackPath())
        }
        reloadFileList(true, true)
        Runtime.getRuntime().addShutdownHook(Thread(::runOnShutdown))
    }

    fun enterDirOrExecuteFile(path: Path) {
        val p = path.absolute().normalize()
        if (!p.exists())
            return
        if (p.isDirectory()) tryEnterDir(p)
        else openFileUnsafe(p)
    }

    protected fun openFileUnsafe(p: Path) {
        if (!Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.EDIT)) {
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
            val newDir = path.toRealPath()
            if (!newDir.exists())
                return@runCatching false
            if (currentDir().isSameFileAs(newDir))
                return@runCatching true
            if (!newDir.isDirectory())
                return@runCatching false
            state.currentDir = newDir
            state.directoriesAccessed.readAndWrite { it.notify(path) }
            return@runCatching true
        }.onSuccess {
            if (!it)
                return@onSuccess
            gui.clearFilter()
            reloadFileList(true, false)
            gui.trySelectIndex(0)
        }.getOrElse {
            it.printStackTrace()
            gui.showExceptionDialog(it)
            return false
        }

    //    @OptIn(ExperimentalPathApi::class)
    fun tryDeleteFileEntry(path: Path? = null) {
        if (path == null) return
        // Using the (awt) Desktop::moveToTrash function here seems to breifly block all user input.
        // Using an extra thread does not fix this, neither does using a coroutine.
        // My solution to this is implementing Java Native Library, which adds it's own recycle bin feature that works on Windows 10
        val fileUtils = FileUtils.getInstance()
        if (!fileUtils.hasTrash()) {
            gui.showTrashNotSupportedDialog()
            return
        }
        // TODO: When deleting a file, the user input is not blocked, but the file stays for a bit until the operation is complete
        // For now: Move the selection down by 1
        // Long-term: Show deletion process in the list and move the selection down by 1.
        // Will have to use another thread, but that is required anyway for larger file deletions.
        try {
            fileUtils.moveToTrash(path.toFile())
            if (path.exists()) {
                gui.showDeletionFailedDialog(path)
                return
            }
            reloadFileList(true, true)
        } catch (e: Exception) {
            gui.showDeletionFailedDialog(path)
        }
    }

    fun tryLeaveCurrentDir() {
        if (state.currentDir.parent == null) return // In case the current dir is a drive 
        val oldDir = state.currentDir
        state.currentDir = state.currentDir.parent
        reloadFileList(true, false)
        gui.trySelectFile(oldDir)
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
            reloadFileList(true, false)
            gui.trySelectFile(newDir)
        } catch (e: Exception) {
            println("INFO: Failed to create dir. ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    fun tryCreateFile(fileName: String) {
        val newFile = state.currentDir.resolve(fileName)
        try {
            newFile.createFile()
            reloadFileList(true, false)
            gui.trySelectFile(newFile)
        } catch (e: Exception) {
            println("INFO: Failed to create dir. ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    fun addCurrentDirFavorite() {
        val curDir = currentDir()
        val newFav = ExplorerFavoriteEntry(curDir.name, curDir)
        var favs = favorites()
        // TODO: prompt the user when they create a new entry to let them set the name directly
        // also allow the creation of duplicate entries.
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

    /**
     * Starts a [FilePasteHandler] as a background operation at first. If it takes to long, it will block the main frame
     * and show a progress bar. The user can then minimize it to continue in the background again. If there are any
     * errors, the blocking frame will pop up again and prompt the user on how to proceed.
     *
     * @param srcFileList The files that should be pasted
     * @param deleteSourceFiles If true, will try to delete the source files after copying them to their new location.
     * This is used equivalent to a cut operation.
     */
    @OptIn(ExperimentalPathApi::class)
    fun startFilePasteTask(srcFileList: List<Path>, deleteSourceFiles: Boolean) {
        val mode: FilePasteHandler.CollisionMode =
            if (srcFileList.size == 1) FilePasteHandler.CollisionMode.CREATE_SIBLING else FilePasteHandler.CollisionMode.RETRY
        val op = FilePasteHandler(srcFileList, currentDir(), deleteSourceFiles, mode)
        val c = FilePasteTask(this, op)
        c.start()
    }

    fun currentDir() = state.currentDir
    fun fileList() = state.cachedFileList
    fun favorites(): List<ExplorerFavoriteEntry> = state.favorites.readAndGet()

    fun recentDirsAT(max: Long) = state.directoriesAccessed.readAndGet().sortedByAccessTime(max)
    fun recentDirsAC(max: Long) = state.directoriesAccessed.readAndGet().sortedByAccessCount(max)

    /**
     * Reloads the file list of the current directory from disk.
     *
     * @param updateGui If true, also calls [ExplorerGUI.updateFileList]
     * @param tryMaintainSelection Passed to the gui update, only used when [updateGui] is true
     */
    fun reloadFileList(updateGui: Boolean = true, tryMaintainSelection: Boolean = true) {
        val files =
            try {
                Files.list(state.currentDir)
            } catch (e: Exception) {
                println("INFO: ${e::class.simpleName} Cannot list files in currentDir ('${state.currentDir}'): ${e.message}")
                val fallbackPath = getFallbackPath()
                println("INFO: Trying fallback dir ('${fallbackPath.pathString}')")
                try {
                    state.currentDir = fallbackPath
                    println("INFO: Using fallback dir now")
                    Files.list(fallbackPath)
                } catch (e: Exception) {
                    println("FATAL: Fallback failed. Check the fallback directory.")
                    return
                }
            }
        state.cachedFileList = files.toList()
        if (!updateGui)
            return
        gui.updateFileList(tryMaintainSelection)
    }

    protected fun makePersistentState(): ExplorerPersistentState {
        return ExplorerPersistentState(state.currentDir, gui.selectedPath())
    }

    protected fun runOnShutdown() {
        storage.write(makePersistentState())
        // favorites are handled during runtime
    }
}