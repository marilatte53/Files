package org.example

import org.example.logic.ExplorerController
import javax.swing.JFrame
import javax.swing.WindowConstants

fun main() {
    val contr = ExplorerController()
    
    val frame = JFrame()
    frame.setSize(900, 600)
    frame.setLocationRelativeTo(null)
    frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    frame.add(contr.gui.rootPanel)
    frame.isVisible = true
    contr.gui.focusFileList()
}