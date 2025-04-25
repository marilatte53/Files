package org.example

import java.awt.Component
import java.awt.Desktop
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.AbstractAction
import javax.swing.ActionMap

fun action(run: (e: ActionEvent) -> Unit): AbstractAction =
    object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) = run(e!!)
    }

fun ActionMap.put(key: String, run: (ActionEvent) -> Unit) {
    this.put(key, action(run))
}

fun isTrashSupported(): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)

