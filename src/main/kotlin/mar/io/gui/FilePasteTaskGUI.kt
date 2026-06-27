package mar.io.gui

import mar.io.logic.FilePasteHandler
import mar.io.logic.FilePasteTask
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import kotlin.io.path.absolutePathString

class FilePasteTaskGUI(frame: JFrame, task: FilePasteTask) {
    private val rootPanel: JPanel
    private val collisionPanel: JPanel
    private val collisionTxt: JTextArea
    private val errorComponentIndex: Int
    private val dialog: JDialog

    init {
        dialog = JDialog(frame)
        dialog.title = "File Paste Task"
        dialog.modalityType = Dialog.ModalityType.APPLICATION_MODAL
        // I am not sure what this refers to
        // TODO: use something to cancel the operation when the user clicks X or ALT+F4
//        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent?) {
                println("deactivated")
            }

            override fun windowClosed(e: WindowEvent?) {
                println("closed")
            }

            override fun windowClosing(e: WindowEvent?) {
                println("closING")
            }
        })
        this.rootPanel = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.border = BorderFactory.createEmptyBorder(10, 5, 5, 10)
        }
        // collision
        this.collisionTxt = JTextArea().also {
            it.isEditable = false
            it.lineWrap = true
            it.border = BorderFactory.createLineBorder(Color.BLACK)
            it.font = it.font.deriveFont(15F)
            it.preferredSize = Dimension(500, 150)
        }
        collisionPanel = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.border = BorderFactory.createEmptyBorder(5, 0, 0, 0)
            it.alignmentX = Component.LEFT_ALIGNMENT
            val createBtn: (String, FilePasteHandler.CollisionMode) -> JButton = create@{ name, mode ->
                return@create JButton(name).also { btn ->
                    btn.addActionListener { task.resumeAfterCollision(mode) }
                    btn.maximumSize = Dimension(Integer.MAX_VALUE, btn.maximumSize.height)
                    btn.alignmentX = Component.CENTER_ALIGNMENT
                }
            }
            it.add(collisionTxt)
            it.add(createBtn("Retry", FilePasteHandler.CollisionMode.RETRY))
            it.add(createBtn("Create Sibling", FilePasteHandler.CollisionMode.CREATE_SIBLING))
            it.add(createBtn("Skip", FilePasteHandler.CollisionMode.MARK_RESOLVED))
        }
        // general error (I assume this means, we should check for or set a general error somewhere around here)
        val cancelBtn = JButton("Cancel").also {
            it.addActionListener { task.cancel() }
        }
        rootPanel.add(JLabel("Pasting ${task.handler.fileCount()} file(s) -> '${task.handler.destinationDir.absolutePathString()}'.").also {
            it.font = it.font.deriveFont(15F)
        })
        rootPanel.add(JLabel("Cut Mode: ${task.handler.shouldDeleteSrc}").also { it.font = it.font.deriveFont(15F) })
        this.errorComponentIndex = rootPanel.components.size
        rootPanel.add(cancelBtn)
        rootPanel.components.forEach { if (it is JComponent) it.alignmentX = Component.LEFT_ALIGNMENT }

        dialog.add(rootPanel)
        dialog.setLocationRelativeTo(null)
        dialog.pack()
    }

    fun setCollisionError(op: FilePasteHandler.PasteOperation) = SwingUtilities.invokeLater {
        collisionTxt.text = """
            File Collision detected:
            src: ${op.srcFile.absolutePathString()}
            dst: ${op.actualTarget.absolutePathString()}
        """.trimIndent()
        // TODO: think about & eventually introduce options to handle multiple collisions at a time.
        // Either set the mode for all collisions or make the option dependinng on collision type
        rootPanel.add(collisionPanel, errorComponentIndex)
        rootPanel.revalidate()
        rootPanel.repaint()
        dialog.pack()
        dialog.isVisible = true
    }

    fun setGeneralError(op: FilePasteHandler.PasteOperation) {
        dialog.isVisible = true
    }

    fun removeError() = SwingUtilities.invokeLater {
        // TODO: think of something (what should this even do?)
    }

    fun destroy() = SwingUtilities.invokeLater {
        dialog.dispose()
    }
}