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
    val FOCUS_FILE_LIST_ACTION = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) {
            uiFileList.requestFocusInWindow()
        }
    }
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
        uiAddressBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFocus")
        uiAddressBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "dropFocus")
        uiAddressBar.actionMap.put("dropFocus", FOCUS_FILE_LIST_ACTION)
        uiAddressBar.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                // TODO: bug: when this func is called but the directory stays the same, the selected element is not preserved
                // either re-select it or check if the directory is already open
                try {
                    val path = Paths.get(uiAddressBar.text)
                    if (path.exists() && path.isDirectory()) {
                        setCurrentDir(path)
                        return
                    }
                } catch (_: Exception) {
                }
                updateAddressBar()
                uiAddressBar.requestFocusInWindow()
            }
        })

        uiFileList.model = uiListModel
        uiFileList.font = Font("Calibri", Font.PLAIN, 15)
        uiFileList.selectedIndex = 0
        uiFileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enterDir")
        uiFileList.actionMap.put("enterDir", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val p = uiFileList.selectedValue ?: return
                if (!p.isDirectory())
                    return
                setCurrentDir(p)
            }
        })
        uiFileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "leaveDir")
        uiFileList.actionMap.put("leaveDir", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (currentDir.parent == null)
                    return

                val oldDir = currentDir
                setCurrentDir(currentDir.parent)
                uiFileList.setSelectedValue(oldDir, true)
            }
        })
        uiFileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dropFilters")
        uiFileList.actionMap.put("dropFilters", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                clearFilter()
                updateFileList()
            }
        })
        uiFileList.cellRenderer =
            object : DefaultListCellRenderer() {
                // TODO: better icons + more different icons
                val dirIcon = UIManager.getIcon("FileView.directoryIcon")
                val fileIcon = UIManager.getIcon("FileView.fileIcon")

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
                    if (path.isDirectory())
                        label.icon = dirIcon
                    else if (path.isRegularFile())
                        label.icon = fileIcon
                    return label
                }
            }
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
                if (Files.list(currentDir).filter(STARTS_WITH_SEARCH).findFirst().isPresent) {
                    searchBarPredicate = STARTS_WITH_SEARCH
                    updateFileList()
                    return
                }
                if (Files.list(currentDir).filter(CONTAINS_SEARCH).findFirst().isPresent) {
                    searchBarPredicate = CONTAINS_SEARCH
                    updateFileList()
                    return
                }
                uiSearchBar.text = oldText
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
        // TODO: keep selection
        uiSearchBar.actionMap.put("dropFocus", FOCUS_FILE_LIST_ACTION)
        uiSearchBar.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "redirectEnter")
        uiSearchBar.actionMap.put("redirectEnter", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                uiFileList.requestFocusInWindow()
                robot.keyPress(KeyEvent.VK_ENTER)
                robot.keyRelease(KeyEvent.VK_ENTER)
            }
        })
        uiRoot.layout = BorderLayout()
        val rootInputMap = uiRoot.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
            "focusAddressBar"
        )
        uiRoot.actionMap.put("focusAddressBar", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                uiAddressBar.requestFocusInWindow()
                uiAddressBar.selectAll()
            }
        })
        rootInputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
            "focusSearchBar"
        )
        uiRoot.actionMap.put("focusSearchBar", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                uiSearchBar.requestFocusInWindow()
            }
        })
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
        uiRoot.add(uiAddressBar, BorderLayout.NORTH)
        val uiFileScroller = JScrollPane(uiFileList)
        uiFileScroller.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        uiRoot.add(uiFileScroller, BorderLayout.CENTER)
        // TODO: add margin in rounded corners FUCK WINDOWS 11 or just change the font?
        uiRoot.add(uiSearchBar, BorderLayout.SOUTH)

        setCurrentDir(Paths.get(System.getProperty("user.home")))
    }

    protected fun updateAddressBar() {
        uiAddressBar.text = currentDir.absolute().invariantSeparatorsPathString
    }

    fun updateFileList() {
        updateAddressBar()

        uiListModel.removeAllElements()
        val files = Files.list(currentDir).sorted(fileListComp).let {
            if (searchBarPredicate == null) it else it.filter(searchBarPredicate)
        }.toList()
        uiListModel.addAll(files)
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