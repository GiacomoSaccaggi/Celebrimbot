package com.github.giacomosaccaggi.celebrimbot.toolWindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

class ThinkingIndicator(private val messagesPanel: JPanel) {

    private val frames = arrayOf("⚙️ Thinking", "⚙️ Thinking.", "⚙️ Thinking..", "⚙️ Thinking...")
    private var frameIndex = 0
    private var showing = false

    private val label = JLabel(frames[0]).apply {
        font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, 12f)
        foreground = JBColor(0x888888, 0x888888)
    }

    private val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        isOpaque = false
        add(label)
    }

    private val timer = Timer(300) {
        frameIndex = (frameIndex + 1) % frames.size
        label.text = frames[frameIndex]
    }

    fun show() {
        if (showing) return
        showing = true
        frameIndex = 0
        label.text = frames[0]
        messagesPanel.add(panel)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        timer.start()
    }

    fun hide() {
        if (!showing) return
        showing = false
        timer.stop()
        messagesPanel.remove(panel)
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    fun isVisible(): Boolean = showing
}
