package org.example.gui

import org.example.action
import org.example.isTrashSupported
import org.example.logic.ExplorerController
import org.example.logic.ExplorerState
import org.example.put
import java.awt.*
import java.awt.event.*
import java.io.IOException
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
    protected var filter: String?
        set(value) {
            this.filterBar.text = value
        }
        get() = this.filterBar.text

    /** Stores the previous filter text in the time between a filter update and a file list update */
    var previousFilter: String? = null

    init {
        initAddressBar()
        initFileList()
        initFilterBar()
        rootPanel.layout = BorderLayout()
        val rootInputMap = rootPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
            "focusAddressBar"
        )
        rootPanel.actionMap.put("focusAddressBar") {
            addressBar.requestFocusInWindow()
            addressBar.selectAll()
        }
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
            "focusFilterBar"
        )
        rootPanel.actionMap.put("focusFilterBar") { filterBar.requestFocusInWindow() }
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
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openFile")
        fileList.actionMap.put("openFile") { controller.openFileOrEnterDir(selectedFile()) }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "leaveDir")
        fileList.actionMap.put("leaveDir") { controller.leaveCurrentDir() }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFilters")
        fileList.actionMap.put("dropFilters") {
            clearFilterBar()
            controller.updateFileList()
        }
        val dirIcon = UIManager.getIcon("FileView.directoryIcon")
        val fileIcon = UIManager.getIcon("FileView.fileIcon")
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
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteFile")
        fileList.actionMap.put("createDir") { userCreateDir() }
        fileList.actionMap.put("createFile") { userCreateFile() }
        fileList.actionMap.put("deleteFile") { userDeleteFile() }
        val ctxMenu = JPopupMenu("test")
        val iCreateDir = JMenuItem("New Directory")
        iCreateDir.addActionListener { userCreateDir() }
        ctxMenu.add(iCreateDir)
        fileList.componentPopupMenu = ctxMenu
//        uiFileList.inheritsPopupMenu = true
    }

    protected fun initFilterBar() {
        // TODO: maybe test this; just check the filter when updated and reset if needed
//        filterBar.document.addDocumentListener(object : DocumentListener {
//            override fun insertUpdate(e: DocumentEvent?) {
//                TODO("Not yet implemented")
//            }
//
//            override fun removeUpdate(e: DocumentEvent?) {
//                TODO("Not yet implemented")
//            }
//
//            override fun changedUpdate(e: DocumentEvent) {
//                e.getChange(e.document.defaultRootElement)
//                TODO("Not yet implemented")
//            }
//
//        })
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
                controller.updateFileList(null) // reload the file list, the rest is automatically handled by this
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
        // TODO: use document listener/filter?
        addressBar.addFocusListener(object : FocusAdapter() { // Pressing enter will also call this apparently
            override fun focusLost(e: FocusEvent?) {
                // On lost focus try to enter the specified directory
                if (!controller.tryEnterDir(addressBar.text)) {
                    addressBar.requestFocusInWindow()
                    controller.updateFileList()
                }
            }
        })
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

    @OptIn(ExperimentalPathApi::class)
    protected fun userDeleteFile() {
        // TODO
        val file = fileList.selectedValue ?: return
        try {
            if (isTrashSupported()) {
                if (Desktop.getDesktop().moveToTrash(file.toFile())) {
                    updateFileListAfterDeletion()
                } else {
                    JOptionPane.showMessageDialog(
                        null,
                        "Failed to move file to trash",
                        "File deletion failed",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            } else {
                val a = JOptionPane.showConfirmDialog(
                    null,
                    "Trash is not supported, delete file anyway?\n" +
                            "This means that the file will not be recoverable",
                    "Delete file?",
                    JOptionPane.YES_NO_OPTION
                )
                if (a == JOptionPane.YES_OPTION) {
                    file.deleteRecursively()
                    updateFileListAfterDeletion()
                }
            }
        } catch (e: IOException) {
            val msg = "Unkown error: ${e.message}"
            JOptionPane.showMessageDialog(null, msg, "Error deleting file", JOptionPane.ERROR_MESSAGE)
        }
    }

    fun updateAddressBar(currentDir: Path) {
        addressBar.text = currentDir.absolute().invariantSeparatorsPathString
    }

    // TODO
    fun updateFileListAfterDeletion() {
        val ind = fileList.selectedIndex
        updateFileList(controller.state)
        if (ind < fileList.model.size) fileList.selectedIndex = ind
        else fileList.selectedIndex = fileList.model.size - 1
    }

    fun selectedFile(): Path? = fileList.selectedValue

    fun makeFileListUsable(rawFiles: List<Path>): List<Path> {
        val usableFiles =
            // Filter files by primary filter
            rawFiles.stream().let { fileList ->
                // don't do unnecessary work
                if (filter.isNullOrEmpty()) fileList
                fileList.collect(Collectors.groupingBy { file ->
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
     * @param clearSelection when this is true, clear the selection and ignore newSelection
     */
    fun updateFileList(state: ExplorerState, trySelect: Path? = null, clearSelection: Boolean = false) {
        updateAddressBar(state.currentDir)
        var files = makeFileListUsable(state.cachedFileList)
        if (!filter.isNullOrEmpty() && files.isEmpty()) { // Is the current filter invalid?
            // -> try the previous filter
            this.filter = previousFilter
            // in case of file system changes, we need to validate the old filter again
            files = makeFileListUsable(state.cachedFileList)
            if (!filter.isNullOrEmpty() && files.isEmpty()) {// old filter is invalid too
                // -> clear the filter
                this.filter = null
                // and of course get the files again
                files = makeFileListUsable(state.cachedFileList)
            }
        }
        // clear the previous filter, since it has been 'used up'
        previousFilter = null
        val previousSelection = selectedFile()
        fileListModel.removeAllElements()
        fileListModel.addAll(files)
        val newSel =
            if (clearSelection) null
            else trySelect ?: previousSelection
        trySelectInFileList(newSel)
    }

    fun trySelectInFileList(path: Path?): Boolean {
        // Don't bother checking for null, this function will handle it. Will also fix selection in case it's invalid
        fileList.setSelectedValue(path, true)
        if (fileList.selectedValue != null) return true
        fileList.selectedIndex = 0
        return false
    }

    fun clearFilterBar() {
        filterBar.text = ""
    }

    /** Currently this is only used in Main */
    fun focusFileList() {
        fileList.requestFocusInWindow()
    }
}