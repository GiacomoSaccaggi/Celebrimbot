package com.github.giacomosaccaggi.celebrimbot.toolWindow

import com.intellij.ui.components.JBScrollPane
import javax.swing.JComponent
import javax.swing.SwingUtilities

class SmartScrollPane(view: JComponent) : JBScrollPane(view) {

    fun isAtBottom(): Boolean {
        val sb = verticalScrollBar
        return sb.value + sb.visibleAmount >= sb.maximum - SCROLL_THRESHOLD
    }

    fun scrollToBottomIfAtEnd() {
        if (isAtBottom()) {
            SwingUtilities.invokeLater { verticalScrollBar.value = verticalScrollBar.maximum }
        }
    }

    companion object {
        private const val SCROLL_THRESHOLD = 50
    }
}
