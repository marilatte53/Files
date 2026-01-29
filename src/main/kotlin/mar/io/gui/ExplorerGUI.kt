package mar.io.gui

import mar.io.action
import mar.io.logic.ExplorerController
import mar.io.logic.FileListPasteOperation
import mar.io.put
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.io.File
import java.nio.file.Path
import java.util.function.Predicate
import java.util.stream.Collectors
import javax.swing.*
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import kotlin.io.path.*

class ExplorerGUI(
    val controller: ExplorerController
) {
    companion object {
        val dirIcon: Icon? = UIManager.getIcon("FileView.directoryIcon")
        val fileIcon: Icon? = UIManager.getIcon("FileView.fileIcon")
    }

    val STARTS_WITH_FILTER =
        Predicate<Path> { it.name.startsWith(filterBar.text, ignoreCase = true) }
    val CONTAINS_FILTER = Predicate<Path> { it.name.contains(filterBar.text, ignoreCase = true) }
    val ACTION_FOCUS_FILE_LIST = action { fileList.requestFocusInWindow() }
    protected val robot = Robot()

    protected var fileListComp = FileComparators.FILE_DIR.then(FileComparators.UNDERSCORE_FIRST)
        .then(Comparator.naturalOrder())

    val rootPanel: JPanel = JPanel()
    val fileList: JList<Path> = JList() // TODO: use table instead and add detail columns
    val fileListModel: DefaultListModel<Path> = DefaultListModel()
    val addressBar = JTextField()

    /** The text field at the bottom, used to filter files */
    val filterBar = JTextField()

    /** a little hack */
    var filter: String?
        set(value) {
            this.filterBar.text = value
        }
        get() = this.filterBar.text

    fun clearFilter() {
        this.filterBar.text = ""
    }

    /** Stores the previous filter text in the time between a filter update and a file list update */
    var previousFilter: String? = null

    init {
        initFileList()
        initAddressBar()
        initFilterBar()
        rootPanel.layout = BorderLayout()
        val rootInputMap = rootPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        // Key events that happen in any child of the rootPanel can be handled here #keybind
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
            "focusAddressBar"
        )
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
            "focusFilterBar"
        )
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK),
            "openFavorites"
        )
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
            "openRecents"
        )
        rootPanel.actionMap.put("focusAddressBar") {
            addressBar.requestFocusInWindow()
            addressBar.selectAll()
        }
        rootPanel.actionMap.put("focusFilterBar") { filterBar.requestFocusInWindow() }
        rootPanel.actionMap.put("openFavorites") { showFavoritesPopup(it) }
        rootPanel.actionMap.put("openRecents") { showRecentsPopup(it) }
        rootPanel.add(addressBar, BorderLayout.NORTH)
        val uiFileScroller = JScrollPane(fileList)
        uiFileScroller.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        rootPanel.add(uiFileScroller, BorderLayout.CENTER)
        // TODO: change the font?
        rootPanel.add(filterBar, BorderLayout.SOUTH)
    }

    protected fun initFileList() {
        fileList.font = Font("Calibri", Font.PLAIN, 15)
        fileList.model = fileListModel
        fileList.selectedIndex = 0
        // Enter directory or open file with default application
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enterOrExecute")
        fileList.actionMap.put("enterOrExecute") { selectedPath()?.let(controller::enterOrExecute) }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "leaveDir")
        fileList.actionMap.put("leaveDir") { controller.tryLeaveCurrentDir() }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFilters")
        fileList.actionMap.put("dropFilters") {
            clearFilter()
            controller.updateFileList()
        }
        fileList.cellRenderer =
            object : DefaultListCellRenderer() {
                // TODO: better icons + more different icons
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    path: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val label = super.getListCellRendererComponent(
                        list,
                        (path as Path).fileName,
                        index,
                        isSelected,
                        cellHasFocus
                    ) as JLabel
                    when {
                        path.isDirectory() -> label.icon = dirIcon
                        path.isRegularFile() -> label.icon = fileIcon
                        // TODO: link
                    }
                    return label
                }
            }
        fileList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.modifiersEx != 0 || !e.keyChar.isLetter())
                    return
                filterBar.requestFocusInWindow()
                // I, robot
                robot.keyPress(e.extendedKeyCode)
                robot.keyRelease(e.extendedKeyCode)
            }
        })
        fileList.inputMap.put(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK
            ), "createDir"
        )
        fileList.inputMap.put(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ), "createFile"
        )
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deletePath")
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "cutSelection")
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copySelection")
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste")
        fileList.actionMap.put("createDir") { userCreateDir() }
        fileList.actionMap.put("createFile") { userCreateFile() }
        fileList.actionMap.put("deletePath") { userDeletePath() }
        fileList.actionMap.put("cutSelection") { userCutOrCopy(true) }
        fileList.actionMap.put("copySelection") { userCutOrCopy(false) }
        fileList.actionMap.put("paste") { userPaste() }
        val ctxMenu = JPopupMenu("test")
        val iCreateDir = JMenuItem("New Directory")
        iCreateDir.addActionListener { userCreateDir() }
        ctxMenu.add(iCreateDir)
        fileList.componentPopupMenu = ctxMenu
//        uiFileList.inheritsPopupMenu = true
    }

    protected fun initFilterBar() {
        (filterBar.document as AbstractDocument).documentFilter = object : DocumentFilter() {
            override fun replace(
                fb: FilterBypass?,
                offset: Int,
                length: Int,
                text: String?,
                attrs: AttributeSet?
            ) {
                // Store the old filter in case the new one doesn't find anything
                this@ExplorerGUI.previousFilter = filterBar.text
                super.replace(fb, offset, length, text, attrs) // actually change the filter text
                controller.updateFileList() // reload the file list, the rest is automatically handled by this
            }

            override fun remove(fb: FilterBypass?, offset: Int, length: Int) {
                this@ExplorerGUI.previousFilter = filterBar.text
                super.remove(fb, offset, length)
                controller.updateFileList()
            }
        }
        filterBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFocus")
        filterBar.actionMap.put("dropFocus", ACTION_FOCUS_FILE_LIST)
        // make navigation keys work even when in filter bar
        filterBar.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (val kc = e.extendedKeyCode) {
                    KeyEvent.VK_DOWN, KeyEvent.VK_UP, KeyEvent.VK_ENTER -> {
                        fileList.dispatchEvent(e)
                        // if enter, redirect the focus as well
                        if (kc == KeyEvent.VK_ENTER)
                            fileList.requestFocusInWindow()
                    }
                }
            }
        })
    }

    protected fun initAddressBar() {
        addressBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFocus")
        addressBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "dropFocus")
        addressBar.actionMap.put("dropFocus", ACTION_FOCUS_FILE_LIST)
        addressBar.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                confirmAddressBar()
            }
        })
    }

    protected fun showFavoritesPopup(e: ActionEvent) {
        val c = e.source
        if (c !is Component)
            return
        val favoritesMenu = JPopupMenu("Favorites")
        val favs = controller.favorites()
        var i = 0
        for (fav in favs) {
            val favItem = JMenuItem()
            favItem.action = action { controller.tryEnterDir(fav.path) }
            favItem.text = fav.name
            favItem.icon = dirIcon
            if (i < 9) favItem.accelerator = KeyStroke.getKeyStroke('1' + i++)
            favoritesMenu.add(favItem)
        }
        favoritesMenu.add(JSeparator())
        // add current dir A
        val addCurrItem = JMenuItem()
        addCurrItem.action = action { controller.addCurrentDirFavorite() }
        addCurrItem.text = "Add current dir"
        addCurrItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_A, 0)
        favoritesMenu.add(addCurrItem)
        // edit favorites E
        val editFavsItem = JMenuItem()
        editFavsItem.action = action { controller.editFavoritesExternally() }
        editFavsItem.text = "Edit favorites"
        editFavsItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_E, 0)
        favoritesMenu.add(editFavsItem)
        favoritesMenu.show(c, 0, 0)
    }

    private val recentsAC_accelerators = arrayOf('Q', 'W', 'E', 'R', 'T')
    private fun showRecentsPopup(e: ActionEvent) {
        val c = e.source
        if (c !is Component)
            return
        val recentsMenu = JPopupMenu("Recent Directories")
        val recentsByAT = controller.recentDirsAT(5)
        val recentsByAC = controller.recentDirsAC(5)
        if (recentsByAT.isEmpty() && recentsByAC.isEmpty())
            return
        var i = 0
        // at
        for (path in recentsByAT) {
            val recentEntry = JMenuItem()
            recentEntry.action = action { controller.tryEnterDir(path) }
            recentEntry.text = path.name
            recentEntry.icon = dirIcon
            recentEntry.accelerator = KeyStroke.getKeyStroke('1' + i++)
            recentsMenu.add(recentEntry)
        }
        i = 0
        recentsMenu.add(JSeparator())
        // ac
        for (path in recentsByAC) {
            val recentEntry = JMenuItem()
            recentEntry.action = action { controller.tryEnterDir(path) }
            recentEntry.text = path.name
            recentEntry.icon = dirIcon
            recentEntry.accelerator = KeyStroke.getKeyStroke(recentsAC_accelerators[i++].lowercaseChar())
            recentsMenu.add(recentEntry)
        }
        // reset button?
        recentsMenu.show(c, 0, 0)
    }

    /** The address bar has changed and now we need to update the file list accordingly */
    protected fun confirmAddressBar() {
        controller.tryEnterDir(Path.of(addressBar.text))
        fileList.requestFocusInWindow()
    }

    protected fun userCreateDir() {
        val dirName =
            JOptionPane.showInputDialog(null, "Directory name:", "Create Directory", JOptionPane.QUESTION_MESSAGE)
                ?: return
        try {
            controller.tryCreateDir(dirName)
        } catch (e: Exception) {
            val msg = "${e::class.simpleName}: ${e.message}"
            JOptionPane.showMessageDialog(null, msg, "Failed to create directory", JOptionPane.ERROR_MESSAGE)
        }
    }

    protected fun userCreateFile() {
        val result =
            JOptionPane.showInputDialog(null, "File name:", "Create File", JOptionPane.QUESTION_MESSAGE)
                ?: return
        try {
            controller.tryCreateFile(result)
        } catch (e: Exception) {
            val msg = "${e::class.simpleName}: ${e.message}"
            JOptionPane.showMessageDialog(null, msg, "Failed to create file", JOptionPane.ERROR_MESSAGE)
        }
    }

    protected fun userDeletePath() {
        // TODO: only skip confirmation when shift is pressed or smth
        val fileList = makeFileListUsable(controller.fileList()).toMutableList()
        val selectedPath = selectedPath() ?: return
        val deletedIndex: Int = fileList.indexOf(selectedPath)
        if (deletedIndex < 0 || !fileList.remove(selectedPath)) {
            println("FATAL: Trying to delete '${selectedPath.invariantSeparatorsPathString}', but path is not in file list!")
            // since the file list has deynced, we need to fix the selection
            trySelectInFileList(null)
            return
        }
        controller.tryDeleteFileEntry(selectedPath)
        // fix the selection in case of desync or deleting the last file in the list
        if (fileList.isEmpty())
            trySelectInFileList(null)
        else if (deletedIndex == 0)
            trySelectInFileList(fileList[0])
        else if (deletedIndex >= fileList.size)
            trySelectInFileList(fileList[fileList.size - 1])
        else
            trySelectInFileList(fileList[deletedIndex - 1])
    }

    protected fun userCutOrCopy(cut: Boolean) {
        if (fileList.selectedValuesList.isNullOrEmpty())
            return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            CutOrCopyFileListTransferable(fileList.selectedValuesList, cut),
            null
        )
    }

    protected fun userPaste() {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        val flavors = cb.availableDataFlavors
        val op: FileListPasteOperation = runCatching {
            if (flavors.contains(CutOrCopyFileListTransferable.CUT_FILE_LIST_FLAVOR)) {
                @Suppress("UNCHECKED_CAST")
                val pathList: List<Path> = cb.getData(CutOrCopyFileListTransferable.CUT_FILE_LIST_FLAVOR) as List<Path>
                return@runCatching controller.startFilePasteOperation(pathList, true)
            } else if (flavors.contains(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val pathList: List<Path> = (cb.getData(DataFlavor.javaFileListFlavor) as List<File>).map { it.toPath() }
                return@runCatching controller.startFilePasteOperation(pathList, false)
            }
            return
        }.onFailure {
            // TODO: error handling
            return
        }.getOrThrow()
        controller.updateFileList()
        // TODO: handle errors and collisions
        // TODO: select the pasted files in the list after
//        if (!targets.values.isEmpty())
//            updateFileList(targets.values.find { true })
    }

    fun showDeletionFailedDialog(path: Path) {
        JOptionPane.showMessageDialog(
            null,
            "Failed to delete '${path.invariantSeparatorsPathString}'",
            "Failed to delete",
            JOptionPane.ERROR_MESSAGE
        )
    }

    fun showFavoriteFileExceptionDialog(e: Exception) {
        val msg = "Could not ensure that favorites file exists"
        JOptionPane.showMessageDialog(null, msg, "Exception occured", JOptionPane.ERROR_MESSAGE)
    }

    fun showExceptionDialog(t: Throwable) {
        val msg = "${t::class.simpleName}: ${t.message}"
        JOptionPane.showMessageDialog(null, msg, "Exception occured", JOptionPane.ERROR_MESSAGE)
    }

    fun confirmTrashNotSupportedDialog(): Boolean {
        return JOptionPane.showConfirmDialog(
            null,
            "Trash is not supported, delete file anyway?\nThis means that the file will not be recoverable",
            "Delete file?",
            JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION
    }

    /** set the text in the address bar */
    fun setAddress(currentDir: Path) {
        addressBar.text = currentDir.absolute().invariantSeparatorsPathString
    }

    fun selectedPath(): Path? = fileList.selectedValue

    fun makeFileListUsable(rawFiles: List<Path>): List<Path> {
        val usableFiles =
            // Filter files by primary filter
            rawFiles.stream().let { fileList ->
                // don't do unnecessary work
                if (filter.isNullOrEmpty()) fileList.toList()
                else fileList.collect(Collectors.groupingBy { file ->
                    if (STARTS_WITH_FILTER.test(file)) 0
                    else if (CONTAINS_FILTER.test(file)) return@groupingBy 1
                    else -1 // this represents files that are filtered out by the search
                })!!.filter { it.key >= 0 }.entries.sortedBy { it.key }.flatMap { it.value }
            }.sortedWith(fileListComp)
        return usableFiles
    }

    /**
     * @param trySelect try to select this value after updating the list. If null, try to preserve the previous
     *    selection
     * @param clearSelection when this is true, clear the selection and ignore [trySelect]
     */
    fun updateFileList(trySelect: Path? = null, clearSelection: Boolean = false) {
        setAddress(controller.currentDir())
        var files = makeFileListUsable(controller.fileList())
        if (!filter.isNullOrEmpty() && files.isEmpty()) { // Is the current filter invalid?
            // -> try the previous filter
            this.filter = previousFilter
            // in case of file system changes, we need to validate the old filter again
            files = makeFileListUsable(controller.fileList())
            if (!filter.isNullOrEmpty() && files.isEmpty()) {// old filter is invalid too
                clearFilter()
                // and of course get the files again
                files = makeFileListUsable(controller.fileList())
            }
        }
        // clear the previous filter, since it has been 'used up'
        previousFilter = null
        val previousSelection = selectedPath()
        fileListModel.removeAllElements()
        fileListModel.addAll(files)
        val newSel =
            if (clearSelection) null
            else trySelect ?: previousSelection
        trySelectInFileList(newSel)
    }

    /** When path is null, the first file will be selected */
    fun trySelectInFileList(path: Path?): Boolean {
        fileList.setSelectedValue(path, true)
        if (fileList.selectedValue != null) return true
        fileList.selectedIndex = 0
        return false
    }

    /** Currently this is only used in Main */
    fun focusFileList() {
        fileList.requestFocusInWindow()
    }

    fun showFavoriteExistsDialog(name: String) {
        JOptionPane.showMessageDialog(
            null,
            "A favorite with the name '$name' already exists",
            "Favorite exists",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
}