package org.example

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*


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