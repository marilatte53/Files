package mar.io

import mar.io.logic.ExplorerController
import javax.swing.JFrame
import javax.swing.WindowConstants

fun main() {
    val frame = JFrame()
    val contr = ExplorerController(frame)
    frame.setSize(900, 600)
    frame.setLocationRelativeTo(null)
    frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    frame.add(contr.gui.rootPanel)
    frame.isVisible = true
    contr.gui.focusFileList()
}