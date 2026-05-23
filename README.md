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
                    Treebeard (Cloud)     Legolas & Gimli
                    Ent Reviewer          execute tasks
                    reflection loop            |
                          |              Treebeard (Cloud)
                       Bilbo (Local)     Ent Reviewer
                       session summary   reflection loop
                                                     |
                                              Bilbo (Local)
                                              session summary
```

### The Fellowship

| Character | Role | Default Provider |
|-----------|------|------------------|
| **Gandalf** | Router: decides CHAT / EASY_TASK / COMPLEX_TASK | Local Qwen |
| **Galadriel** | Conversational AI: answers in English with Tolkienian flair, addresses user as "Mellow" | Local Qwen |
| **Aragorn** | Easy-task planner: breaks request into atomic steps, assigns workers | Local Qwen |
| **Elrond** | Complex pre-planner: enriches the full request with history, relevant files, and technical notes | Local Qwen |
| **Celebrimbor** | Master planner: receives Elrond's brief and produces precise atomic tasks | Cloud (Alibaba / Gemini) |
| **Samwise** | Precise worker: executes mechanical tasks faithfully (delete, terminal, scan, git) | Local Qwen |
| **Frodo** | Adventurous worker: handles all `write_code` tasks, fills gaps with hobbit-sense | Local Qwen |
| **Legolas & Gimli** | Expert worker duo: called only for complex algorithms, large refactors, or tasks Frodo failed | Cloud (Alibaba / Gemini) |
| **Treebeard** | Ent Reviewer: reviews completed work against the original request; triggers re-planning if incomplete; up to 2 reflection cycles before conceding to Bilbo | Cloud (Alibaba / Local fallback) |
| **Bilbo** | Chronicler: writes a concise session summary, addresses user as "Mellow" | Local Qwen |

### Router Logic (Gandalf)

| Decision | Meaning | Pipeline |
|----------|---------|---------|
| `CHAT` | Greeting, question, explanation | Galadriel |
| `EASY_TASK` | Single self-contained action (one file) | Aragorn → Workers → Treebeard → Bilbo |
| `COMPLEX_TASK` | Multi-step, package creation, edit existing files, or repeated request | Elrond → Celebrimbor → Workers → Treebeard → Bilbo |

Gandalf receives the full conversation history. If the same request has been asked before without success, it automatically escalates to `COMPLEX_TASK`.

### AI Provider Priority

Each character's provider is configurable per-project in Settings. The table below shows the **default** priority chain.

| Character | Role | 1st Choice | 2nd Choice | 3rd Choice |
|-----------|------|-----------|-----------|----------|
| **Gandalf** | Router | Local Qwen | Amazon Q | — |
| **Galadriel** | Chat | Local Qwen | Alibaba Cloud | Gemini |
| **Aragorn** | Easy-task planner | Local Qwen | — | — |
| **Elrond** | Complex pre-planner | Local Qwen | — | — |
| **Celebrimbor** | Master planner | Alibaba Cloud | Gemini | Local Qwen |
| **Samwise** | Mechanical worker | Local Qwen | Alibaba Cloud | Gemini |
| **Frodo** | Code worker (write_code) | Local Qwen | Alibaba Cloud | — |
| **Legolas & Gimli** | Expert code worker | Alibaba Cloud | Gemini | Local Qwen |
| **Treebeard** | Ent Reviewer (reflection) | Alibaba Cloud | Local Qwen | Safe fallback |
| **Bilbo** | Summarizer | Local Qwen | — | — |

All four providers — `Local`, `Alibaba Qwen Cloud`, `Google Gemini`, `Amazon Q Developer` — are selectable per-character. Amazon Q Developer authenticates via the SSO token written by the Amazon Q JetBrains plugin (no separate API key needed). The local model runs entirely on your machine via [java-llama.cpp](https://github.com/kherud/java-llama.cpp) — no internet required for most operations.

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
- **Multi-provider fallback** — Local Qwen → Alibaba Cloud (Qwen Plus) → Google Gemini → Claude Sonnet 4.6 (via Amazon Q Developer)
- **Secure credential storage** — API keys stored via IntelliJ PasswordSafe, never in plain text
- **Per-project settings** — each project can use a different provider and model
- **Standalone CLI** — `celebrimbot forge / scan / serve / mcp-stdio / undo` for use outside the IDE
- **HTTP bridge** — embedded Ktor server for remote invocation from other tools
- **Dynamic Tool Registry** — all capabilities self-describe as JSON schemas; planners receive auto-generated tool lists, adding a new tool requires zero prompt editing
- **Shadow Log (Auto-Undo)** — every file is backed up before being written or deleted; `celebrimbot undo` restores the last session from `.celebrimbot/shadow_log/`
- **Council's Review (Validation Loop)** — after every `write_code`, the build command runs automatically; up to 3 self-correction cycles with error feedback before escalating
- **Elrond's Palantír (BM25 Index)** — lightweight local semantic index; COMPLEX_TASK planning retrieves the top-8 relevant files instead of sending the full project skeleton
- **MCP Bridge (Beacons of Gondor)** — MCP-compliant JSON-RPC 2.0 server over HTTP (`POST /mcp`) and Stdio (`celebrimbot mcp-stdio`) for Claude Desktop and other MCP hosts
- **Treebeard (Ent Reviewer)** — critic agent between Workers and Bilbo; reviews completed work against the original request; triggers up to 2 re-planning cycles if incomplete; never hasty, never satisfied with placeholder code

---

## Available Actions

| Category | Action | Description |
|----------|--------|-------------|
| File | `read_psi` | Read file content |
| File | `write_code` | Create or overwrite a file (LLM-assisted) |
| File | `write_file` | Create or overwrite a file (raw, no LLM — for MCP clients) |
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
- Optional: Amazon Q Developer (authenticated via the Amazon Q JetBrains plugin — no separate API key)

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
| **Provider** | `Local API`, `Google Gemini`, `Alibaba Qwen Cloud`, or `Amazon Q Developer` |
| **Base URL** | API endpoint (pre-filled per provider) |
| **Model Name** | e.g. `qwen-plus`, `gemini-1.5-flash` |
| **API Key** | For Gemini or local OpenAI-compatible APIs |
| **Alibaba Cloud API Key** | For Qwen Cloud (Responses API) |
| **Validation Command** | Custom build command for the Council's Review loop (e.g. `./gradlew classes`). Leave empty to auto-detect from project marker files. |

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
| Cloud option | Amazon Q Developer (Claude Sonnet, via SSO) |
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
java -jar build/libs/celebrimbot.jar scan
java -jar build/libs/celebrimbot.jar serve --port 16180
java -jar build/libs/celebrimbot.jar mcp-stdio
java -jar build/libs/celebrimbot.jar undo
```

---

## Project Structure

```
src/main/kotlin/.../celebrimbot/
├── cli/
│   └── CelebrimbotCLI                # Standalone CLI (forge / scan / serve / mcp-stdio / undo)
├── io/
│   ├── FileOperator                  # Interface for file operations
│   ├── IdeFileOperator               # PSI/VFS implementation (IDE mode)
│   ├── HeadlessFileOperator          # java.nio implementation (standalone)
│   ├── ShadowLogOperator             # Interface for shadow log backup/undo
│   ├── HeadlessShadowLogOperator     # java.nio shadow log implementation
│   ├── ShadowedFileOperator          # Decorator: intercepts writes/deletes for backup
│   ├── TerminalOperator              # Interface for terminal execution (returns TerminalResult)
│   ├── IdeTerminalOperator           # IDE terminal implementation
│   ├── HeadlessTerminalOperator      # ProcessBuilder implementation
│   ├── LlmEngine                     # Interface for LLM inference
│   ├── StandaloneLlmEngine           # llama.cpp standalone implementation
│   ├── WebSearchOperator             # Interface for web search + fetch_page
│   ├── DuckDuckGoSearchOperator      # DuckDuckGo implementation
│   ├── ProjectScanOperator           # Interface for project scanning + walkSourceFiles
│   ├── HeadlessProjectScanOperator   # java.nio implementation
│   ├── GitOperator                   # Interface for git operations
│   └── HeadlessGitOperator           # ProcessBuilder git implementation
├── index/
│   └── PalantirIndex                 # BM25 semantic index (build, query, save, load)
├── mcp/
│   ├── McpRouter                     # JSON-RPC 2.0 dispatcher (all MCP method handlers)
│   └── McpTransport                  # Compact JSON-RPC response/error formatting
├── model/
│   ├── CelebrimbotPlan               # CelebrimbotTask data class
│   ├── CelebrimbotTool               # CelebrimbotTool interface, ToolParam, ToolResult, ToolCategory
│   └── TreebeardReviewResult         # Treebeard's verdict: isComplete, reasoning, additionalRequests
├── registry/
│   ├── ToolRegistry                  # Central tool vault with toJsonSchema() and toMcpToolList()
│   └── tools/
│       └── Tools                     # 15 tool implementations wrapping all operators
├── services/
│   ├── CelebrimbotAgentOrchestrator  # Multi-agent loop: routing, planning, execution, reflection
│   ├── CelebrimbotLlmService         # AI provider abstraction (local/Alibaba/Gemini/Amazon Q)
│   ├── CelebrimbotEmbeddedEngine     # Local llama.cpp inference engine
│   ├── CelebrimbotTerminalService    # IDE terminal command execution
│   ├── ValidationService             # Build system detection + Council's Review command
│   └── TreebeardReviewService        # Ent Reviewer: reflection loop after Workers
├── settings/
│   ├── CelebrimbotSettingsState      # Persistent per-project configuration
│   ├── CelebrimbotSettingsConfigurable # Settings UI panel
│   └── CelebrimbotPasswordSafe       # Secure API key storage
├── startup/
│   └── CelebrimbotStartupActivity    # Plugin init, model warm-up, Palantír index refresh
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
│   ├── treebeard_system_prompt.txt   # Ent Reviewer persona: slow, methodical, strict JSON output
│   └── bilbo_system_prompt.txt       # Session chronicler persona
├── icons/
│   └── celebrimbot.svg
└── META-INF/
    └── plugin.xml
```

---

## Provider Benchmark Results

The following results come from an automated LLM-as-a-Judge evaluation (`src/test/testData/eval/run_eval.py`) run across **12 provider configurations** and **8 test cases**, using **Claude Sonnet 4.6 (via Amazon Q Developer)** as the judge. Full results are in [`EVAL_REPORT.md`](EVAL_REPORT.md).

### How the Eval Works

The evaluation framework is a standalone Python script that runs the full Celebrimbot agent pipeline headlessly — no IDE required. Each test case defines a natural-language input, the expected routing decision (CHAT / EASY_TASK / COMPLEX_TASK), and a set of judge criteria.

**Test cases** (`src/test/testData/eval/eval_suite.json`) cover:
- Routing accuracy (does Gandalf classify greetings, single-file tasks, and multi-file tasks correctly?)
- Planner quality (does Elrond produce a valid JSON brief with all required fields?)
- Worker output quality (does Frodo write syntactically valid Python with the requested classes/methods?)
- Reviewer behaviour (does Treebeard flag incomplete work when a README is too short?)
- Summary quality (does Bilbo address the user as "Mellow" in Tolkienian style?)

**How it runs:**
1. For each configuration, the script spins up a `HeadlessPipeline` in a temporary directory
2. The pipeline calls the configured LLM backend (Amazon Q Developer via SSO token, or Ollama for local models) for each character
3. After execution, the judge (Claude Sonnet 4.6 via Amazon Q Developer) receives the full agent trace, internal logs, and written file contents, then scores the run 0–10 and flags specific issues
4. Results are aggregated into `EVAL_REPORT.md` and per-configuration JSON files in `build/eval/`

**To run it yourself:**
```bash
# Requires: Amazon Q Developer SSO login + Ollama for local models
brew install ollama && ollama serve &
ollama pull qwen2.5-coder:1.5b qwen2.5-coder:7b llama3.1:8b deepseek-coder:6.7b phi3.5
python3 src/test/testData/eval/run_eval.py
```

### Final Ranking

| Rank | Configuration | Avg Score | Pass Rate |
|------|--------------|-----------|----------|
| 🥇 | Claude Sonnet 4.6 planners + **Qwen 2.5 Coder 7B** workers | **9.0/10** | 100% |
| 🥈 | Claude Sonnet 4.6 planners + **Phi-3.5 Mini** workers | **9.0/10** | 100% |
| 🥉 | Claude Sonnet 4.6 planners + **Llama 3.1 8B** workers | **8.9/10** | 100% |
| #4 | All Claude Sonnet 4.6 (baseline) | 8.6/10 | 87.5% |
| #5 | Claude Sonnet 4.6 core only | 8.4/10 | 87.5% |
| #6 | Claude Sonnet 4.6 planners + Qwen 2.5 Coder 1.5B workers | 7.8/10 | 87.5% |
| #7 | All Local — Qwen 2.5 Coder 7B | 7.8/10 | 87.5% |
| #8 | All Local — Llama 3.1 8B | 7.8/10 | 87.5% |
| #9 | All Local — Phi-3.5 Mini | 6.2/10 | 62.5% |
| #10 | Claude Sonnet 4.6 planners + DeepSeek Coder 6.7B workers | 6.1/10 | 75.0% |
| #11 | All Local — Qwen 2.5 Coder 1.5B | 4.9/10 | 37.5% |
| #12 | All Local — DeepSeek Coder 6.7B | 4.9/10 | 50.0% |

### Key Findings

**The optimal configuration is: Claude Sonnet 4.6 (via Amazon Q Developer) for planners + a 7B+ local model for workers.**

The top three configurations all share the same pattern: Claude Sonnet 4.6 (via Amazon Q Developer) handles Gandalf (routing), Elrond (context enrichment), Celebrimbor (master planning), and Treebeard (review), while a local model runs Aragorn, Frodo, Legolas & Gimli, Galadriel, and Bilbo. This hybrid approach **outperforms using Claude Sonnet 4.6 for everything** (9.0 vs 8.6) while minimising cloud API calls.

**Local 7B models are competitive workers.** Qwen 2.5 Coder 7B and Llama 3.1 8B running fully locally (no cloud) both score 7.8/10 — higher than the "Claude Sonnet 4.6 core only" configuration (8.4/10 but with more cloud calls). For teams that need full offline operation, Qwen 7B or Llama 8B are viable.

**DeepSeek Coder 6.7B underperforms its size.** Despite being comparable in size to Qwen 7B, DeepSeek scores only 4.9/10 all-local and 6.1/10 as a worker. It frequently refuses tasks with "I can't assist with that" and produces malformed JSON, making it unreliable for agentic pipelines.

**Qwen 2.5 Coder 1.5B is too small for reliable routing and planning.** At 4.9/10 all-local, it misroutes simple greetings as COMPLEX_TASK and produces incomplete JSON plans. It is only viable as a Frodo/worker when paired with a cloud planner.

**Phi-3.5 Mini (3.8B) punches above its weight as a worker.** Paired with Claude Sonnet 4.6 planners it reaches 9.0/10 — matching Qwen 7B at a fraction of the RAM footprint (~2.5 GB vs ~4.5 GB). Best choice for resource-constrained environments.

### Recommended Configurations

| Use Case | Gandalf | Elrond | Celebrimbor | Workers | Score |
|----------|---------|--------|-------------|---------|-------|
| **Best quality** | Amazon Q | Amazon Q | Amazon Q | Qwen 7B / Phi-3.5 | 9.0/10 |
| **Balanced** | Amazon Q | Amazon Q | Amazon Q | Llama 3.1 8B | 8.9/10 |
| **Minimum cloud** | Amazon Q | Amazon Q | Amazon Q | Qwen 1.5B | 7.8/10 |
| **Full offline** | Qwen 7B | Qwen 7B | Qwen 7B | Qwen 7B | 7.8/10 |
| **Ultra-light offline** | Phi-3.5 | Phi-3.5 | Phi-3.5 | Phi-3.5 | 6.2/10 |

---

## License

This project is based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
