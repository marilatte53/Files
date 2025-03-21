package org.example

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.Robot
import java.awt.event.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Predicate
import javax.swing.*
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import kotlin.io.path.*


class FileView {
    val STARTS_WITH_SEARCH =
        Predicate<Path> { it.name.startsWith(uiSearchBar.text, ignoreCase = true) }
    val CONTAINS_SEARCH = Predicate<Path> { it.name.contains(uiSearchBar.text, ignoreCase = true) }
    val FOCUS_FILE_LIST_ACTION = action { uiFileList.requestFocusInWindow() }
    protected val robot = Robot()

    protected var fileListComp = FileComparators.FILE_DIR.then(FileComparators.UNDERSCORE_FIRST)
        .then(Comparator.naturalOrder())
    lateinit var currentDir: Path
        protected set
    protected var searchBarPredicate: Predicate<Path>? = null

    val uiRoot: JPanel = JPanel()
    val uiFileList: JList<Path> = JList() // TODO: use table instead and add detail columns
    val uiListModel: DefaultListModel<Path> = DefaultListModel()
    val uiAddressBar = JTextField()
    val uiSearchBar = JTextField()

    init {
        initAddressBar()
        initFileList()
        initSearchBar()
        uiRoot.layout = BorderLayout()
        val rootInputMap = uiRoot.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
            "focusAddressBar"
        )
        uiRoot.actionMap.put("focusAddressBar") {
            uiAddressBar.requestFocusInWindow()
            uiAddressBar.selectAll()
        }
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
            "focusSearchBar"
        )
        uiRoot.actionMap.put("focusSearchBar") { uiSearchBar.requestFocusInWindow() }
        uiRoot.add(uiAddressBar, BorderLayout.NORTH)
        val uiFileScroller = JScrollPane(uiFileList)
        uiFileScroller.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        uiRoot.add(uiFileScroller, BorderLayout.CENTER)
        // TODO: change the font?
        uiRoot.add(uiSearchBar, BorderLayout.SOUTH)

        setCurrentDir(Paths.get(System.getProperty("user.home")))
    }

    protected fun initFileList() {
        uiFileList.model = uiListModel
        uiFileList.font = Font("Calibri", Font.PLAIN, 15)
        uiFileList.selectedIndex = 0
        uiFileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enterDir")
        uiFileList.actionMap.put("enterDir") {
            val p = uiFileList.selectedValue ?: return@put
            if (!p.isDirectory()) return@put
            setCurrentDir(p)
        }
        uiFileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "leaveDir")
        uiFileList.actionMap.put("leaveDir") {
            if (currentDir.parent == null) return@put
            val oldDir = currentDir
            setCurrentDir(currentDir.parent)
            uiFileList.setSelectedValue(oldDir, true)
        }
        uiFileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFilters")
        uiFileList.actionMap.put("dropFilters") {
            clearFilter()
            updateFileList()
        }
        val dirIcon = UIManager.getIcon("FileView.directoryIcon")
        val fileIcon = UIManager.getIcon("FileView.fileIcon")
        uiFileList.cellRenderer =
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
        uiFileList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.modifiersEx != 0 || !e.keyChar.isLetter())
                    return
                uiSearchBar.requestFocusInWindow()
                // I, robot
                robot.keyPress(e.extendedKeyCode)
                robot.keyRelease(e.extendedKeyCode)
            }
        })
        uiFileList.inputMap.put(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            ), "createDir"
        )
        uiFileList.actionMap.put("createDir") { userCreateDir() }
        val ctxMenu = JPopupMenu("test")
        val iCreateDir = JMenuItem("New Directory")
        iCreateDir.addActionListener { userCreateDir() }
        ctxMenu.add(iCreateDir)
        uiFileList.componentPopupMenu = ctxMenu
//        uiFileList.inheritsPopupMenu = true
    }

    protected fun initSearchBar() {
        (uiSearchBar.document as AbstractDocument).documentFilter = object : DocumentFilter() {
            override fun replace(
                fb: FilterBypass?,
                offset: Int,
                length: Int,
                text: String?,
                attrs: AttributeSet?
            ) {
                val oldText = uiSearchBar.text
                super.replace(fb, offset, length, text, attrs)
                // TODO: display all but sort startswith first OR only use contains-search 
                val pred = simulateSearch()
                if (pred == null) {
                    uiSearchBar.text = oldText
                    return
                }
                searchBarPredicate = pred
                updateFileList()
            }

            override fun remove(fb: FilterBypass?, offset: Int, length: Int) {
                super.remove(fb, offset, length)
                if (Files.list(currentDir).filter(STARTS_WITH_SEARCH).findFirst().isPresent)
                    searchBarPredicate = STARTS_WITH_SEARCH
                else searchBarPredicate = CONTAINS_SEARCH
                updateFileList()
            }
        }
        uiSearchBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFocus")
        uiSearchBar.actionMap.put("dropFocus", FOCUS_FILE_LIST_ACTION)
        // make navigation keys work even when in search bar
        uiSearchBar.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (val kc = e.extendedKeyCode) {
                    KeyEvent.VK_DOWN, KeyEvent.VK_UP, KeyEvent.VK_ENTER -> {
                        uiFileList.dispatchEvent(e)
                        // if enter, redirect the focus as well
                        if (kc == KeyEvent.VK_ENTER)
                            uiFileList.requestFocusInWindow()
                    }
                }
            }
        })
    }

    protected fun initAddressBar() {
        uiAddressBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFocus")
        uiAddressBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "dropFocus")
        uiAddressBar.actionMap.put("dropFocus", FOCUS_FILE_LIST_ACTION)
        // TODO: use document listener/filter?
        uiAddressBar.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                try {
                    val path = Paths.get(uiAddressBar.text).absolute().normalize()
                    if (path.exists() && path.isDirectory()) {
                        if (!path.isSameFileAs(currentDir)) {
                            setCurrentDir(path)
                        }
                        updateAddressBar()
                        return
                    }
                } catch (_: Exception) {
                }
                uiAddressBar.requestFocusInWindow()
                updateAddressBar()
            }
        })
    }

    protected fun userCreateDir() {
        val result =
            JOptionPane.showInputDialog(null, "Directory name:", "Create Directory", JOptionPane.QUESTION_MESSAGE)
        val newFile = currentDir.resolve(result)
        if (newFile.isDirectory() && newFile.exists()) {
            JOptionPane.showMessageDialog(
                null,
                "Directory already exists",
                "Directory exists",
                JOptionPane.WARNING_MESSAGE
            )
        }
    }

    protected fun simulateSearch(): Predicate<Path>? {
        if (Files.list(currentDir).filter(STARTS_WITH_SEARCH).findFirst().isPresent)
            return STARTS_WITH_SEARCH
        if (Files.list(currentDir).filter(CONTAINS_SEARCH).findFirst().isPresent)
            return CONTAINS_SEARCH
        return null
    }

    protected fun updateAddressBar() {
        uiAddressBar.text = currentDir.absolute().invariantSeparatorsPathString
    }

    fun updateFileList() {
        updateAddressBar()

        val oldSel = uiFileList.selectedValue
        uiListModel.removeAllElements()
        val files = Files.list(currentDir).sorted(fileListComp).let {
            if (searchBarPredicate == null) it else it.filter(searchBarPredicate)
        }.toList()
        uiListModel.addAll(files)
        uiFileList.setSelectedValue(oldSel, true)
        if (uiFileList.selectedValue == null)
            uiFileList.selectedIndex = 0
    }

    fun clearFilter() {
        uiSearchBar.text = ""
        searchBarPredicate = null
    }

    fun setCurrentDir(path: Path, update: Boolean = true) {
        this.currentDir = path
        clearFilter()
        if (update)
            updateFileList()
    }

    fun focus() {
        uiFileList.requestFocusInWindow()
    }
}