package org.example.gui

import org.example.action
import org.example.isTrashSupported
import org.example.logic.ExplorerController
import org.example.logic.ExplorerState
import org.example.put
import java.awt.*
import java.awt.event.*
import java.io.IOException
import java.nio.file.Files
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
        Predicate<Path> { it.name.startsWith(searchBar.text, ignoreCase = true) }
    val CONTAINS_FILTER = Predicate<Path> { it.name.contains(searchBar.text, ignoreCase = true) }
    val ACTION_FOCUS_FILE_LIST = action { fileList.requestFocusInWindow() }
    protected val robot = Robot()

    protected var fileListComp = FileComparators.FILE_DIR.then(FileComparators.UNDERSCORE_FIRST)
        .then(Comparator.naturalOrder())
    protected var searchBarPredicate: Predicate<Path>? = null

    val rootPanel: JPanel = JPanel()
    val fileList: JList<Path> = JList() // TODO: use table instead and add detail columns
    val fileListModel: DefaultListModel<Path> = DefaultListModel()
    val addressBar = JTextField()

    /**
     * The bar at the bottom, used to search for files
     */
    val searchBar = JTextField()

    init {
        initAddressBar()
        initFileList()
        initSearchBar()
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
            "focusSearchBar"
        )
        rootPanel.actionMap.put("focusSearchBar") { searchBar.requestFocusInWindow() }
        rootPanel.add(addressBar, BorderLayout.NORTH)
        val uiFileScroller = JScrollPane(fileList)
        uiFileScroller.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        rootPanel.add(uiFileScroller, BorderLayout.CENTER)
        // TODO: change the font?
        rootPanel.add(searchBar, BorderLayout.SOUTH)
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
            clearSearchBar()
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
                searchBar.requestFocusInWindow()
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

    protected fun initSearchBar() {
        (searchBar.document as AbstractDocument).documentFilter = object : DocumentFilter() {
            override fun replace(
                fb: FilterBypass,
                offset: Int,
                length: Int,
                text: String?,
                attrs: AttributeSet?
            ) {
                val oldText = searchBar.text
                super.replace(fb, offset, length, text, attrs)
                /*
                 Prevent an edge case where the search bar is set to empty while the directory is empty.
                 This would lead to the filter text being reset to the old value
                 */
                if (searchBar.text.isEmpty())
                    return
                controller.updateFileList()
                // TODO: they should be sorted, so still simulate the search but extract logic from updateFileList to do so
                val searchedFiles = Files.list(controller.currentDir())
                    .collect(Collectors.partitioningBy { STARTS_WITH_FILTER.test(it) })
                    .also { list -> list[false] = list[false]?.filter { file -> CONTAINS_FILTER.test(file) } }
                if (pred == null) { // If the search doesn't find any files, reset it
                    super.replace(fb, 0, fb.document.length, oldText, null)
                    return
                }
            }

            override fun remove(fb: FilterBypass?, offset: Int, length: Int) {
                super.remove(fb, offset, length)
                val pred = simulateSearch(controller.currentDir())
                if (pred == null) // If the search doesn't find any files, clear the filter
                    super.remove(fb, 0, fb!!.document.length)
                else
                    searchBarPredicate = pred
                controller.updateFileList()
            }
        }
        searchBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFocus")
        searchBar.actionMap.put("dropFocus", ACTION_FOCUS_FILE_LIST)
        // make navigation keys work even when in search bar
        searchBar.addKeyListener(object : KeyAdapter() {
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
        if (ind < fileList.model.size)
            fileList.selectedIndex = ind
        else fileList.selectedIndex = fileList.model.size - 1
    }

    fun selectedFile(): Path? = fileList.selectedValue

    fun updateFileList(state: ExplorerState, newSelection: Path? = null) {
        /*
         Clear the filter first for consistent behaviour: 
         When the new directory is empty, the simulated search won't find any files,
         so the search bar logic would reset the filter
         */
        clearSearchBar()
        fileListModel.removeAllElements()

        updateAddressBar(state.currentDir)
        // TODO: extract this and use it for search logic
        val filesUsable = state.cachedFileList.stream().sorted(fileListComp).let {
            if (searchBarPredicate == null) it else it.filter(searchBarPredicate)
        }.toList()
        fileListModel.addAll(filesUsable)
        trySelectInFileList(newSelection)
    }

    fun trySelectInFileList(path: Path?): Boolean {
        // Don't bother checking for null, this function will handle it. Will also fix selection in case it's invalid
        fileList.setSelectedValue(path, true)
        if (fileList.selectedValue != null) return true
        fileList.selectedIndex = 0
        return false
    }

    fun clearSearchBar() {
        searchBar.text = ""
        searchBarPredicate = null
    }

    /**
     * Currently this is only used in Main
     */
    fun focusFileList() {
        fileList.requestFocusInWindow()
    }
}