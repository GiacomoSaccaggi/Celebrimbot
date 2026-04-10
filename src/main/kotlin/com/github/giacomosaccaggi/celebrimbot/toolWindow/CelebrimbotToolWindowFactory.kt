package com.github.giacomosaccaggi.celebrimbot.toolWindow

import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotAgentOrchestrator
import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotEmbeddedEngine
import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotLlmService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

class CelebrimbotToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatToolWindow = CelebrimbotChatToolWindow(project)
        val content = ContentFactory.getInstance().createContent(chatToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)

        project.messageBus.connect().subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    val tw = toolWindowManager.getToolWindow("Celebrimbot")
                    if (tw != null && !tw.isVisible) {
                        CelebrimbotEmbeddedEngine.getInstance(project).unloadModel()
                    }
                }
            }
        )
    }

    class CelebrimbotChatToolWindow(private val project: Project) {

        private val isDark get() = !JBColor.isBright()

        // Colors that adapt to IDE theme
        private val bgColor get() = UIUtil.getPanelBackground()
        private val userBubbleColor get() = if (isDark) JBColor(0x2D5A8E, 0x2D5A8E) else JBColor(0xD6E8FF, 0xD6E8FF)
        private val botBubbleColor get() = if (isDark) JBColor(0x3C3F41, 0x3C3F41) else JBColor(0xF0F0F0, 0xF0F0F0)
        private val systemBubbleColor get() = if (isDark) JBColor(0x2B2B2B, 0x2B2B2B) else JBColor(0xE8E8E8, 0xE8E8E8)
        private val textColor get() = UIUtil.getLabelForeground()
        private val mutedColor get() = JBColor(0x888888, 0x888888)
        private val accentColor get() = JBColor(0x4A9EFF, 0x4A9EFF)
        private val inputBgColor get() = UIUtil.getTextFieldBackground()
        private val borderColor get() = JBColor(0x555555, 0xCCCCCC)

        private val messagesPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgColor
            border = JBUI.Borders.empty(8, 8, 4, 8)
        }

        private val scrollPane = JBScrollPane(messagesPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            background = bgColor
            viewport.background = bgColor
        }

        private val inputArea = object : JTextArea() {
            private val placeholder = "Message Celebrimbot..."
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (text.isEmpty()) {
                    val g2 = g as Graphics2D
                    g2.color = mutedColor
                    g2.font = font.deriveFont(Font.ITALIC)
                    g2.drawString(placeholder, insets.left + 2, insets.top + g2.fontMetrics.ascent)
                }
            }
        }.apply {
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont().deriveFont(13f)
            background = inputBgColor
            foreground = textColor
            caretColor = textColor
            border = JBUI.Borders.empty(8, 10)
            rows = 3
        }

        private val sendButton = object : JButton("Send ↵") {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isEnabled) accentColor else mutedColor
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
                g2.color = Color.WHITE
                g2.font = font.deriveFont(Font.BOLD, 12f)
                val fm = g2.fontMetrics
                val x = (width - fm.stringWidth(text)) / 2
                val y = (height + fm.ascent - fm.descent) / 2
                g2.drawString(text, x, y)
                g2.dispose()
            }
        }.apply {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = Dimension(80, 36)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { sendMessage() }
        }

        private var projectSkeleton: String = ""
        private val conversationHistory = mutableListOf<Pair<String, String>>()
        private val fullLog = mutableListOf<String>()

        init {
            ReadAction.nonBlocking<String> {
                CelebrimbotLlmService.getInstance(project).buildProjectSkeleton(project)
            }.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState()) {
                projectSkeleton = it
            }.submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())

            // Send on Ctrl+Enter / Cmd+Enter
            inputArea.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && (e.isControlDown || e.isMetaDown)) {
                        e.consume()
                        sendMessage()
                    }
                }
            })
        }

        fun getContent(): JPanel {
            val root = JPanel(BorderLayout())
            root.background = bgColor

            // ── Header ──────────────────────────────────────────────────────
            val header = JPanel(BorderLayout()).apply {
                background = if (isDark) JBColor(0x2B2B2B, 0x2B2B2B) else JBColor(0xF7F7F7, 0xF7F7F7)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                    JBUI.Borders.empty(8, 12)
                )
            }
            val titleLabel = JLabel("✦ Celebrimbot").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 14f)
                foreground = accentColor
            }
            val subtitleLabel = JLabel("Autonomous AI Coding Agent").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f)
                foreground = mutedColor
            }
            val titleBox = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(titleLabel)
                add(subtitleLabel)
            }
            header.add(titleBox, BorderLayout.WEST)

            val copyButton = JButton("Copy").apply {
                isContentAreaFilled = false
                isBorderPainted = false
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = mutedColor
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    val text = fullLog.joinToString("\n")
                    val selection = java.awt.datatransfer.StringSelection(text)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                }
            }
            val clearButton = JButton("Clear").apply {
                isContentAreaFilled = false
                isBorderPainted = false
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = mutedColor
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    messagesPanel.removeAll()
                    messagesPanel.revalidate()
                    messagesPanel.repaint()
                    conversationHistory.clear()
                    fullLog.clear()
                }
            }
            val headerButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(copyButton)
                add(clearButton)
            }
            header.add(headerButtons, BorderLayout.EAST)
            root.add(header, BorderLayout.NORTH)

            // ── Chat area ────────────────────────────────────────────────────
            root.add(scrollPane, BorderLayout.CENTER)

            // ── Input area ───────────────────────────────────────────────────
            val inputWrapper = JPanel(BorderLayout()).apply {
                background = if (isDark) JBColor(0x2B2B2B, 0x2B2B2B) else JBColor(0xF7F7F7, 0xF7F7F7)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
                    JBUI.Borders.empty(8)
                )
            }

            val inputBorder = JPanel(BorderLayout()).apply {
                background = inputBgColor
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1, true),
                    BorderFactory.createEmptyBorder()
                )
                add(JBScrollPane(inputArea).apply {
                    border = BorderFactory.createEmptyBorder()
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }, BorderLayout.CENTER)
            }

            val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 4)).apply {
                isOpaque = false
                val hint = JLabel("⌘↵ to send").apply {
                    font = UIUtil.getLabelFont().deriveFont(10f)
                    foreground = mutedColor
                }
                add(hint)
                add(Box.createHorizontalStrut(8))
                add(sendButton)
            }

            inputWrapper.add(inputBorder, BorderLayout.CENTER)
            inputWrapper.add(buttonRow, BorderLayout.SOUTH)
            root.add(inputWrapper, BorderLayout.SOUTH)

            return root
        }

        private fun sendMessage() {
            val text = inputArea.text.trim()
            if (text.isEmpty()) return

            addUserBubble(text)
            fullLog.add("You: $text")
            inputArea.text = ""
            scrollToBottom()

            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val selectedText = editor?.selectionModel?.selectedText
            val fileName = editor?.virtualFile?.name

            val prompt = if (!selectedText.isNullOrBlank() && fileName != null)
                "System Context: working in $fileName.\nSelected code:\n$selectedText\n\nUser: $text"
            else text

            conversationHistory.add("User" to text)
            var botResponse = ""
            CelebrimbotAgentOrchestrator.getInstance(project).executePlan(prompt, projectSkeleton, conversationHistory.toList()) { progress ->
                ApplicationManager.getApplication().invokeLater {
                    addBotBubble(progress)
                    scrollToBottom()
                    val plain = progress.replace(Regex("<[^>]+>"), "").trim()
                    if (plain.isNotEmpty()) fullLog.add(plain)
                    if (!progress.startsWith("<i>[") && !progress.startsWith("<i>⚙") &&
                        !progress.startsWith("<i>🧠") && !progress.startsWith("<i>📄")) {
                        botResponse = plain
                        if (botResponse.isNotEmpty()) conversationHistory.add("Celebrimbot" to botResponse)
                    }
                }
            }
        }

        private fun addUserBubble(text: String) {
            val bubble = createBubble(
                header = "You",
                body = escapeHtml(text),
                bubbleBg = userBubbleColor,
                align = FlowLayout.RIGHT,
                headerColor = accentColor
            )
            messagesPanel.add(bubble)
            messagesPanel.add(Box.createVerticalStrut(4))
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }

        private fun addBotBubble(html: String) {
            val isSystem = html.startsWith("<i>[") || html.startsWith("<i>⚙") || html.startsWith("<i>🧠") || html.startsWith("<i>📄")
            val bg = if (isSystem) systemBubbleColor else botBubbleColor
            val header = if (isSystem) "" else "Celebrimbot"

            val bubble = createBubble(
                header = header,
                body = html,
                bubbleBg = bg,
                align = FlowLayout.LEFT,
                headerColor = if (isSystem) mutedColor else JBColor(0x6AAF6A, 0x6AAF6A),
                compact = isSystem
            )
            messagesPanel.add(bubble)
            messagesPanel.add(Box.createVerticalStrut(if (isSystem) 2 else 4))
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }

        private fun createBubble(
            header: String,
            body: String,
            bubbleBg: Color,
            align: Int,
            headerColor: Color,
            compact: Boolean = false
        ): JPanel {
            val maxBubbleWidth = 480
            val pane = object : JTextPane() {
                override fun getPreferredSize(): Dimension {
                    // Force wrap at maxBubbleWidth by setting size before asking for preferred height
                    val w = maxBubbleWidth - 20
                    setSize(w, Short.MAX_VALUE.toInt())
                    return Dimension(w, super.getPreferredSize().height)
                }
            }.apply {
                contentType = "text/html"
                editorKit = HTMLEditorKit()
                isEditable = false
                isOpaque = false
                val fontSize = if (compact) 11 else 13
                val css = "body { font-family: ${UIUtil.getLabelFont().family}; font-size: ${fontSize}pt; color: ${colorToHex(textColor)}; word-wrap: break-word; } " +
                          "pre { white-space: pre-wrap; word-wrap: break-word; font-family: monospace; font-size: ${fontSize - 1}pt; margin: 0; } " +
                          "i { color: ${colorToHex(mutedColor)}; } " +
                          "b { color: ${colorToHex(textColor)}; } " +
                          "code { font-family: monospace; } "
                (editorKit as HTMLEditorKit).styleSheet.addRule(css)
                text = "<html><body>$body</body></html>"
            }

            val bubble = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = bubbleBg
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f))
                    g2.dispose()
                    super.paintComponent(g)
                }
            }.apply {
                isOpaque = false
                maximumSize = Dimension(maxBubbleWidth, Int.MAX_VALUE)
                border = JBUI.Borders.empty(if (compact) 4 else 8, 10)
                if (header.isNotEmpty()) {
                    val headerLabel = JLabel(header).apply {
                        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f)
                        foreground = headerColor
                        border = JBUI.Borders.emptyBottom(3)
                    }
                    add(headerLabel, BorderLayout.NORTH)
                }
                add(pane, BorderLayout.CENTER)
            }

            val row = JPanel(FlowLayout(align, 0, 0)).apply {
                isOpaque = false
                add(bubble)
            }
            return row
        }

        private fun scrollToBottom() {
            SwingUtilities.invokeLater {
                val bar = scrollPane.verticalScrollBar
                bar.value = bar.maximum
            }
        }

        private fun escapeHtml(text: String) = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        private fun colorToHex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }
}
