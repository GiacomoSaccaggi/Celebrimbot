<p align="center">
  <img src="celebrimbot.png" alt="Celebrimbot" width="480"/>
</p>

<p align="center">
  <img src="https://github.com/GiacomoSaccaggi/Celebrimbot/workflows/Build/badge.svg" alt="Build"/>
  <a href="https://plugins.jetbrains.com/plugin/MARKETPLACE_ID"><img src="https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg" alt="Version"/></a>
  <a href="https://plugins.jetbrains.com/plugin/MARKETPLACE_ID"><img src="https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg" alt="Downloads"/></a>
</p>

<p align="center"><em>An autonomous AI coding agent embedded directly into your JetBrains IDE.</em></p>

<!-- Plugin description -->
Celebrimbot is an IntelliJ Platform plugin that brings a full multi-agent AI system into your IDE. It can read your project files, write and modify code, execute terminal commands, search the web, inspect git history, and hold natural conversations — all from a single chat panel anchored to your IDE window.

Unlike simple autocomplete tools, Celebrimbot operates as an **agentic loop**: it routes requests intelligently, executes tasks locally when possible, escalates to cloud planning only when needed, and retries failures automatically — without leaving your editor.
<!-- Plugin description end -->

---

## How It Works

Celebrimbot uses a six-layer architecture — each layer named after a character from Tolkien's legendarium:

```
User Message
     |
     v
+-------------+
|   Gandalf   |  <- Router: Local Qwen + conversation history
+-------------+
     |                    |                          |
   CHAT              EASY_TASK                 COMPLEX_TASK
     |                    |                          |
  Galadriel          Aragorn (Local)           Elrond (Local)
  (chat reply)       multi-task planner        enriches context
                          |                          |
               Samwise / Frodo /            Celebrimbor (Cloud)
               Legolas & Gimli              master planner
               execute tasks                     |
                          |              Samwise / Frodo /
                       Bilbo (Local)     Legolas & Gimli
                       session summary   execute tasks
                                                     |
                                              Bilbo (Local)
                                              session summary
```

### The Fellowship

| Character | Role | Where |
|-----------|------|-------|
| **Gandalf** | Router: decides CHAT / EASY_TASK / COMPLEX_TASK | Local Qwen |
| **Galadriel** | Conversational AI: answers in English with Tolkienian flair, addresses user as "Mellow" | Local Qwen |
| **Aragorn** | Easy-task planner: breaks request into atomic steps, assigns workers | Local Qwen |
| **Elrond** | Complex pre-planner: enriches the full request with history, relevant files, and technical notes | Local Qwen |
| **Celebrimbor** | Master planner: receives Elrond's brief and produces precise atomic tasks | Cloud (Alibaba / Gemini) |
| **Samwise** | Precise worker: executes mechanical tasks faithfully (delete, terminal, scan, git) | Local Qwen |
| **Frodo** | Adventurous worker: handles all `write_code` tasks, fills gaps with hobbit-sense | Local Qwen |
| **Legolas & Gimli** | Expert worker duo: called only for complex algorithms, large refactors, or tasks Frodo failed | Cloud (Alibaba / Gemini) |
| **Bilbo** | Chronicler: writes a concise session summary, addresses user as "Mellow" | Local Qwen |

### Router Logic (Gandalf)

| Decision | Meaning | Pipeline |
|----------|---------|---------|
| `CHAT` | Greeting, question, explanation | Galadriel |
| `EASY_TASK` | Single self-contained action | Aragorn -> Samwise -> Bilbo |
| `COMPLEX_TASK` | Multi-step, edit existing files, or repeated request | Elrond -> Celebrimbor -> Samwise -> Bilbo |

Gandalf receives the full conversation history. If the same request has been asked before without success, it automatically escalates to `COMPLEX_TASK`.

### AI Provider Priority

| Character | Role | 1st Choice | 2nd Choice | 3rd Choice |
|-----------|------|-----------|-----------|-----------|
| **Gandalf** | Router | Local Qwen | Heuristic fallback | — |
| **Galadriel** | Chat | Local Qwen | Alibaba Cloud | Gemini |
| **Aragorn** | Easy-task planner | Local Qwen | — | — |
| **Elrond** | Complex pre-planner | Local Qwen | — | — |
| **Celebrimbor** | Master planner | Alibaba Cloud | Gemini | Local Qwen |
| **Samwise** | Mechanical worker | Local Qwen | Alibaba Cloud | Gemini |
| **Frodo** | Code worker (write_code) | Local Qwen | Alibaba Cloud | — |
| **Legolas & Gimli** | Expert code worker | Alibaba Cloud | Gemini | Local Qwen |
| **Bilbo** | Summarizer | Local Qwen | — | — |

The local model runs entirely on your machine via [java-llama.cpp](https://github.com/kherud/java-llama.cpp) — no internet required for most operations.

---

## Features

- **Conversational AI** — natural chat with full project context awareness
- **Autonomous code editing** — reads files, applies changes directly in the editor
- **File management** — create, edit, delete files via natural language
- **Terminal execution** — runs shell commands from within the IDE
- **Web search** — searches DuckDuckGo and fetches pages, no API key required
- **Project scanning** — list files, grep across the codebase, find by name, file stats
- **Git integration** — status, log, diff, blame, branch — all from chat
- **Multi-agent loop** — Gandalf routes → Aragorn/Elrond plan → Celebrimbor refines → Samwise executes → Bilbo summarizes
- **Smart three-way routing** — CHAT / EASY_TASK / COMPLEX_TASK with history awareness (Gandalf)
- **Offline-first** — embedded Qwen 2.5 Coder 1.5B runs locally with no API calls
- **Multi-provider fallback** — Alibaba Cloud (Qwen Plus) → Google Gemini → local model
- **Secure credential storage** — API keys stored via IntelliJ PasswordSafe, never in plain text
- **Per-project settings** — each project can use a different provider and model
- **Standalone CLI** — `celebrimbot forge / scan / serve` for use outside the IDE
- **HTTP bridge** — embedded Ktor server for remote invocation from other tools

---

## Available Actions

| Category | Action | Description |
|----------|--------|-------------|
| File | `read_psi` | Read file content |
| File | `write_code` | Create or overwrite a file |
| File | `delete_file` | Delete a file |
| Scan | `list_files` | List project files, optionally filtered by path/extension |
| Scan | `grep_files` | Regex search across all files |
| Scan | `find_file` | Find files by name fragment |
| Scan | `file_stats` | Line count and size of a file |
| Git | `git_status` | Working tree status |
| Git | `git_log` | Recent commit history |
| Git | `git_diff` | Uncommitted changes |
| Git | `git_blame` | Per-line authorship |
| Git | `git_branch` | Current branch |
| Web | `web_search` | DuckDuckGo search |
| Web | `fetch_page` | Fetch and read a URL |
| Terminal | `run_terminal` | Execute a shell command |

---

## Requirements

- IntelliJ IDEA 2025.2+ (or any JetBrains IDE based on platform 252+)
- Java 21+
- ~1.2 GB disk space for the local model (downloaded automatically on first use)
- Optional: Alibaba Cloud API key for cloud-powered planning
- Optional: Google Gemini API key as secondary fallback

---

## Installation

**From JetBrains Marketplace:**

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search `Celebrimbot` → <kbd>Install</kbd>

**Manually:**

Download the [latest release](https://github.com/GiacomoSaccaggi/Celebrimbot/releases/latest) and install via:

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk...</kbd>

---

## Configuration

Open <kbd>Settings</kbd> → <kbd>Tools</kbd> → <kbd>Celebrimbot</kbd>

| Field | Description |
|-------|-------------|
| **Provider** | `Local API`, `Google Gemini`, or `Alibaba Qwen Cloud` |
| **Base URL** | API endpoint (pre-filled per provider) |
| **Model Name** | e.g. `qwen-plus`, `gemini-1.5-flash` |
| **API Key** | For Gemini or local OpenAI-compatible APIs |
| **Alibaba Cloud API Key** | For Qwen Cloud (Responses API) |

The local embedded model (`qwen2.5-coder-1.5b-instruct-q4_k_m.gguf`) is downloaded automatically to your IDE system directory on first inference. If a partial/corrupted download is detected, it is deleted and re-downloaded automatically.

---

## Usage

Open the **Celebrimbot** tool window (right side panel) and start chatting.

**Examples:**

```
You: ciao
Celebrimbot: [🧝 Galadriel] Ciao! Come posso aiutarti oggi?

You: crea un file python con una funzione che calcola la levenshtein similarity
[⚔️ Aragorn: preparing the task...]
[🌿 Samwise: executing task...]
Celebrimbot: ✅ Code written to src/levenshtein.py
Celebrimbot: ✅ All tasks completed!

You: elimina src/levenshtein.py
[⚔️ Aragorn: preparing the task...]
[🌿 Samwise: executing task...]
Celebrimbot: ✅ Deleted src/levenshtein.py
Celebrimbot: ✅ All tasks completed!

You: cerca online kotlin coroutines timeout example
[⚔️ Aragorn: preparing the task...]
[🌿 Samwise: executing task...]
Celebrimbot: Summary: ...
Celebrimbot: ✅ All tasks completed!

You: refactora il servizio per usare le nuove interfacce
[🧙 Elrond: preparing the brief...]
[💎 Celebrimbor: forging the plan...]
[🌿 Samwise: executing 3 task(s)...]
Celebrimbot: ✅ All tasks completed!
```

The header shows `🖥️ N  ☁️ N` — local inference count vs cloud planner calls — so you always know how many API calls were made.

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.3.20 |
| Platform | IntelliJ Platform 2025.2 |
| Local LLM | [java-llama.cpp](https://github.com/kherud/java-llama.cpp) 3.4.1 |
| Model | Qwen2.5-Coder-1.5B-Instruct Q4_K_M (GGUF) |
| Cloud AI | Alibaba Cloud Model Studio (DashScope) |
| Cloud fallback | Google Gemini 1.5 Flash |
| CLI | Clikt 4.4.0 |
| HTTP Bridge | Ktor 2.3.11 (Netty) |
| JSON | Gson 2.10.1 |
| Build | Gradle 9.4.1 + Shadow JAR |

---

## Development

```bash
./gradlew runIde          # Run plugin in sandbox IDE
./gradlew buildPlugin     # Build distributable ZIP
./gradlew shadowJar       # Build standalone CLI fat JAR (celebrimbot.jar)
./gradlew test            # Run unit tests
./gradlew verifyPlugin    # Verify compatibility
```

**CLI usage (after shadowJar):**
```bash
java -jar build/libs/celebrimbot.jar forge "create a Python file with a fibonacci function"
java -jar build/libs/celebrimbot.jar serve --port 16180
java -jar build/libs/celebrimbot.jar scan
```

---

## Project Structure

```
src/main/kotlin/.../celebrimbot/
├── cli/
│   └── CelebrimbotCLI                # Standalone CLI (forge / scan / serve)
├── io/
│   ├── FileOperator                  # Interface for file operations
│   ├── IdeFileOperator               # PSI/VFS implementation (IDE mode)
│   ├── HeadlessFileOperator          # java.nio implementation (standalone)
│   ├── TerminalOperator              # Interface for terminal execution
│   ├── IdeTerminalOperator           # IDE terminal implementation
│   ├── HeadlessTerminalOperator      # ProcessBuilder implementation
│   ├── LlmEngine                     # Interface for LLM inference
│   ├── StandaloneLlmEngine           # llama.cpp standalone implementation
│   ├── WebSearchOperator             # Interface for web search + fetch_page
│   ├── DuckDuckGoSearchOperator      # DuckDuckGo implementation
│   ├── ProjectScanOperator           # Interface for project scanning
│   ├── HeadlessProjectScanOperator   # java.nio implementation
│   ├── GitOperator                   # Interface for git operations
│   └── HeadlessGitOperator           # ProcessBuilder git implementation
├── model/
│   └── CelebrimbotPlan               # CelebrimbotTask / CelebrimbotPlan data classes
├── services/
│   ├── CelebrimbotAgentOrchestrator  # Multi-agent loop: routing, planning, execution
│   ├── CelebrimbotLlmService         # AI provider abstraction (local/Alibaba/Gemini)
│   ├── CelebrimbotEmbeddedEngine     # Local llama.cpp inference engine
│   └── CelebrimbotTerminalService    # IDE terminal command execution
├── settings/
│   ├── CelebrimbotSettingsState      # Persistent per-project configuration
│   ├── CelebrimbotSettingsConfigurable # Settings UI panel
│   └── CelebrimbotPasswordSafe       # Secure API key storage
├── startup/
│   └── CelebrimbotStartupActivity    # Plugin initialization and model warm-up
├── toolWindow/
│   └── CelebrimbotToolWindowFactory  # Chat UI panel with stats counter and copy/clear
└── MyBundle.kt                       # i18n resource bundle accessor

src/main/resources/
├── prompts/
│   ├── gandalf_system_prompt.txt     # Router: CHAT / EASY_TASK / COMPLEX_TASK
│   ├── galadriel_system_prompt.txt   # Conversational AI persona
│   ├── aragorn_system_prompt.txt     # Easy-task planner persona
│   ├── elrond_system_prompt.txt      # Complex pre-planner persona
│   ├── celebrimbor_system_prompt.txt # Master planner persona
│   ├── samwise_system_prompt.txt     # Precise worker persona
│   ├── frodo_system_prompt.txt       # Adventurous worker persona (write_code)
│   ├── legolas_gimli_system_prompt.txt # Expert worker duo persona (complex tasks)
│   └── bilbo_system_prompt.txt       # Session chronicler persona
├── icons/
│   └── celebrimbot.svg
└── META-INF/
    └── plugin.xml
```

---

## License

This project is based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
