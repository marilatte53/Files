package mar.io.gui

import mar.io.action
import mar.io.logic.ExplorerController
import mar.io.put
import java.awt.*
import java.awt.event.*
import java.nio.file.Path
import java.util.function.Predicate
import java.util.stream.Collectors
import javax.swing.*
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import kotlin.io.path.*
import kotlin.math.min

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
        fileList.actionMap.put("enterOrExecute") { selectedPath()?.let(controller::enterDirOrExecuteFile) }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "leaveDir")
        fileList.actionMap.put("leaveDir") { controller.tryLeaveCurrentDir() }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFilters")
        fileList.actionMap.put("dropFilters") {
            clearFilter()
            controller.reloadFileList()
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
//        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "test")
        fileList.actionMap.put("createDir") { userCreateDir() }
        fileList.actionMap.put("createFile") { userCreateFile() }
        fileList.actionMap.put("deletePath") { userDeletePath() }
        fileList.actionMap.put("cutSelection") { userCutOrCopy(true) }
        fileList.actionMap.put("copySelection") { userCutOrCopy(false) }
        fileList.actionMap.put("paste") { userPaste() }
//        fileList.actionMap.put("test") { this.fileList.selectedIndex++ }
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
                val f = filterBar.text
                super.replace(fb, offset, length, text, attrs) // actually change the filter text
                adjustFilter(f)
                controller.reloadFileList()
            }

            override fun remove(fb: FilterBypass?, offset: Int, length: Int) {
                val f = filterBar.text
                super.remove(fb, offset, length)
                adjustFilter(f)
                controller.reloadFileList()
            }

            fun adjustFilter(previousFilter: String) {
                controller.reloadFileList(false)
                val files = controller.fileList()
                // uses filter getter from ExplorerGui class
                if (getEffectiveFileList(files).isEmpty() && !filter.isNullOrEmpty() && !files.isEmpty()) {
                    // filtered file list is empty (but unfiltered file list is not) -> don't accept the new filter
                    this@ExplorerGUI.filter = previousFilter
                }
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
        val currentFiles = getEffectiveFileList(controller.fileList()).toMutableList()
        val selectedPath = selectedPath() ?: return
        val deletedIndex: Int = currentFiles.indexOf(selectedPath)
        if (deletedIndex < 0 || !currentFiles.remove(selectedPath)) {
            println("FATAL: Trying to delete '${selectedPath.invariantSeparatorsPathString}', but path is not in file list!")
            return
        }
        try {
            controller.tryDeleteFileEntry(selectedPath)
            if (currentFiles.isEmpty())
                this.fileList.selectedIndex = 0
            if (deletedIndex >= currentFiles.size)
                this.fileList.selectedIndex = currentFiles.size - 1
            else
                this.fileList.selectedIndex = deletedIndex
        } catch (e: Exception) {
            showExceptionDialog(e)
        }
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
        CutOrCopyFileListTransferable.getOrNull(cb)?.let { t ->
            runCatching {
                // TODO: review this process; show progress bar, or make that information otherwise accessible
                controller.startFilePasteTask(t.copiedPaths, t.isCutOperation)
            }.onFailure {
                showErrorDialog("File Paste Init", "Failed to initialize file paste operation:", it)
            }
        } ?: return
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

    fun showErrorDialog(title: String, message: String, t: Throwable) {
        JOptionPane.showMessageDialog(controller.frame, "$title\n$t", title, JOptionPane.ERROR_MESSAGE)
    }

    fun showExceptionDialog(t: Throwable) {
        val msg = "${t::class.simpleName}: ${t.message}"
        JOptionPane.showMessageDialog(null, msg, "Exception occured", JOptionPane.ERROR_MESSAGE)
    }

    fun showTrashNotSupportedDialog() {
        return JOptionPane.showMessageDialog(
            null,
            "Trash is not supported. Do not contact the developer about this :)",
            "Delete file?",
            JOptionPane.ERROR_MESSAGE
        )
    }

    /** set the text in the address bar */
    fun setAddress(currentDir: Path) {
        addressBar.text = currentDir.absolute().invariantSeparatorsPathString
    }

    fun selectedPath(): Path? = fileList.selectedValue

    fun getEffectiveFileList(rawFiles: List<Path>): List<Path> {
        val usableFiles =
            // Filter files by primary filter
            rawFiles.stream().let { fileList ->
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
     * @param tryMaintainSelection Tries to select the same list entry as before. If not possible, tries to select the
     * same index, capped at the maximum list size.
     */
    fun updateFileList(tryMaintainSelection: Boolean = true) {
        setAddress(controller.currentDir())
        val files = controller.fileList()
        val effectiveFiles = getEffectiveFileList(files)
        val previousSelection = selectedPath()
        val previousIndex = this.fileList.selectedIndex
        fileListModel.removeAllElements()
        if (effectiveFiles.isEmpty())
            return
        fileListModel.addAll(effectiveFiles)
        if (!tryMaintainSelection)
            return
        if (!trySelectFile(previousSelection)) {
            this.fileList.selectedIndex = min(previousIndex, fileListModel.size())
        }
    }

    /** When path is null, the first file will be selected */
    fun trySelectFile(path: Path?): Boolean {
        fileList.setSelectedValue(path, true)
        if (fileList.selectedValue != null) return true
        trySelectIndex(0)
        return false
    }

    fun trySelectFiles(paths: List<Path>): Boolean {
        val indices = paths.mapNotNull {
            val ind = fileListModel.indexOf(it)
            if (ind == -1)
                return@mapNotNull null
            return@mapNotNull ind
        }.toIntArray()
        if (indices.isEmpty()) {
            trySelectIndex(0)
            return false
        }
        fileList.selectedIndices = indices
        return true
    }

    fun trySelectIndex(index: Int) {
        fileList.selectedIndex = index
        fileList.ensureIndexIsVisible(index)
    }

    /** Currently this is only used in Main */
    fun requestFocus() {
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