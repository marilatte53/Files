package org.example.gui

import org.example.action
import org.example.logic.ExplorerController
import org.example.put
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.Robot
import java.awt.event.*
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
        val dirIcon = UIManager.getIcon("FileView.directoryIcon")
        val fileIcon = UIManager.getIcon("FileView.fileIcon")
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
    protected var filter: String?
        set(value) {
            this.filterBar.text = value
        }
        get() = this.filterBar.text

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
        rootPanel.actionMap.put("focusAddressBar") {
            addressBar.requestFocusInWindow()
            addressBar.selectAll()
        }
        rootPanel.actionMap.put("focusFilterBar") { filterBar.requestFocusInWindow() }
        rootPanel.actionMap.put("openFavorites") { showFavoritesPopup(it) }
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
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openPath")
        fileList.actionMap.put("openPath") { controller.openFileOrEnterDir(selectedPath()) }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "leaveDir")
        fileList.actionMap.put("leaveDir") { controller.tryLeaveCurrentDir() }
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFilters")
        fileList.actionMap.put("dropFilters") {
            this.filter = null
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
        fileList.actionMap.put("createDir") { userCreateDir() }
        fileList.actionMap.put("createFile") { userCreateFile() }
        fileList.actionMap.put("deletePath") { userDeletePath() }
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
        favoritesMenu.show(c, 0, 0)
    }

    /** The address bar has changed and now we need to update the file list accordingly */
    protected fun confirmAddressBar() {
        if (!controller.tryEnterDir(Path.of(addressBar.text))) controller.updateFileList()
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
        // try to select the entry after the deleted one. If there is none, select the one before
        // In the future it will be possible to delete multiple selected files. So we already implement this here:
        val fileList = makeFileListUsable(controller.fileList()).toMutableList()
        val selectedPath = selectedPath() ?: return
        var selectionIndex: Int = fileList.indexOf(selectedPath)
        if (selectionIndex < 0 || !fileList.remove(selectedPath)) { // should never happen since the selection has to be in the list
            println("FATAL: Trying to delete '${selectedPath.invariantSeparatorsPathString}', but path is not in file list!")
            return
        }
        if (selectionIndex >= fileList.size) // check if the new index is valid
            selectionIndex = fileList.size - 1
        controller.tryDeletePath(selectedPath, fileList[selectionIndex])
    }

    fun showDeletionFailedDialog(path: Path) {
        JOptionPane.showMessageDialog(
            null,
            "Failed to delete '${path.invariantSeparatorsPathString}'",
            "Failed to delete",
            JOptionPane.ERROR_MESSAGE
        )
    }

    fun showExceptionDialog(e: Exception) {
        val msg = "${e::class.simpleName}: ${e.message}"
        JOptionPane.showMessageDialog(null, msg, "Failed to create file", JOptionPane.ERROR_MESSAGE)
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
     * @param clearSelection when this is true, clear the selection and ignore newSelection
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
                // -> clear the filter
                this.filter = null
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

    fun trySelectInFileList(path: Path?): Boolean {
        // Don't bother checking for null, setSelectedValue will handle it
        fileList.setSelectedValue(path, true)
        if (fileList.selectedValue != null) return true
        fileList.selectedIndex = 0
        return false
    }

    /** Currently this is only used in Main */
    fun focusFileList() {
        fileList.requestFocusInWindow()
    }
}