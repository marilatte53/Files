package org.example

import javax.swing.JFrame
import javax.swing.WindowConstants


fun main() {
    val view = FileView()

    val frame = JFrame()
    frame.setSize(900, 600)
    frame.setLocationRelativeTo(null)
    frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    frame.add(view.uiRoot)
    frame.isVisible = true
    view.focus()
}