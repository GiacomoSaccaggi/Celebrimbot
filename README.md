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
Celebrimbot is an IntelliJ Platform plugin that brings a full multi-agent AI system into your IDE. It can read your project files, write and modify code, execute terminal commands, and hold natural conversations — all from a single chat panel anchored to your IDE window.

Unlike simple autocomplete tools, Celebrimbot operates as an **agentic loop**: it plans, executes, verifies results, and escalates failures back to a higher-level planner — without leaving your editor.
<!-- Plugin description end -->

---

## How It Works

Celebrimbot uses a three-tier architecture to handle every user request:

```
User Message
     │
     ▼
┌─────────────┐
│   Router    │  ← Qwen 1.5B local model decides: CHAT or PLAN?
└─────────────┘
     │              │
   CHAT            PLAN
     │              │
     ▼              ▼
┌─────────┐   ┌──────────────────────────────────────┐
│  Chat   │   │  Planner (Alibaba Cloud / Gemini /    │
│  Local  │   │  Local Qwen fallback)                 │
│  Qwen   │   │                                       │
└─────────┘   │  Decides strategy:                    │
              │  • "direct"  → answer immediately     │
              │  • "plan"    → list of tasks           │
              └──────────────────────────────────────┘
                             │
                             ▼
              ┌──────────────────────────────────────┐
              │  Worker (Local Qwen 1.5B)             │
              │                                       │
              │  Executes tasks:                      │
              │  • read_psi   → read file content     │
              │  • write_code → modify file in editor │
              │  • run_terminal → execute command     │
              │                                       │
              │  3 retries per task                   │
              │  → escalates to Planner on failure    │
              └──────────────────────────────────────┘
```

### AI Provider Priority

| Role | 1st Choice | 2nd Choice | 3rd Choice |
|------|-----------|-----------|-----------|
| **Router** | Local Qwen (embedded) | Heuristic fallback | — |
| **Chat** | Local Qwen (embedded) | Alibaba Cloud | Gemini |
| **Planner** | Alibaba Cloud | Gemini | Local Qwen (embedded) |
| **Worker** | Local Qwen (embedded) | Alibaba Cloud | Gemini |

The local model runs entirely on your machine via [java-llama.cpp](https://github.com/kherud/java-llama.cpp) — no internet required for most operations.

---

## Features

- **Conversational AI** — natural chat with full project context awareness
- **Autonomous code editing** — reads files, applies changes directly in the editor
- **Terminal execution** — runs shell commands from within the IDE
- **Multi-agent loop** — Planner → Worker → retry → escalation pipeline
- **Smart routing** — Qwen decides whether a message needs a plan or a simple answer
- **Offline-first** — embedded Qwen 2.5 Coder 1.5B runs locally with no API calls
- **Multi-provider fallback** — Alibaba Cloud (Qwen Plus) → Google Gemini → local model
- **Secure credential storage** — API keys stored via IntelliJ PasswordSafe, never in plain text
- **Per-project settings** — each project can use a different provider and model

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

The local embedded model (`qwen2.5-coder-1.5b-instruct-q4_k_m.gguf`) is downloaded automatically to your IDE system directory on first inference.

---

## Usage

Open the **Celebrimbot** tool window (right side panel) and start chatting.

**Examples:**

```
You: ciao
Celebrimbot: [🖥️ Local Qwen] Ciao! Come posso aiutarti oggi?

You: what files do you see in this project?
Celebrimbot: [🖥️ Local Qwen] I can see Main.java in /src/Main.java

You: remove the second Main class from Main.java
[🧠 Planner: deciding strategy...]
[⚙️ Worker: 🖥️ Local Qwen → executing 2 task(s)...]
[📄 Read Main.java: 1153 chars]
Celebrimbot: ✅ Code written to Main.java
```

The engine label (`🖥️ Local Qwen`, `☁️ Alibaba Qwen`) tells you exactly which model handled each response and whether any API calls were made.

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
| JSON | Gson 2.10.1 |
| Build | Gradle 9.4.1 + Shadow JAR |

---

## Development

```bash
./gradlew runIde          # Run plugin in sandbox IDE
./gradlew buildPlugin     # Build distributable ZIP
./gradlew test            # Run unit tests
./gradlew verifyPlugin    # Verify compatibility
```

---

## Project Structure

```
src/main/kotlin/.../celebrimbot/
├── services/
│   ├── CelebrimbotAgentOrchestrator  # Multi-agent loop: routing, planning, execution
│   ├── CelebrimbotLlmService         # AI provider abstraction (local/Alibaba/Gemini)
│   ├── CelebrimbotEmbeddedEngine     # Local llama.cpp inference engine
│   └── CelebrimbotTerminalService    # IDE terminal command execution
├── settings/
│   ├── CelebrimbotSettingsState      # Persistent per-project configuration
│   ├── CelebrimbotSettingsConfigurable # Settings UI panel
│   └── CelebrimbotPasswordSafe       # Secure API key storage
├── toolWindow/
│   └── CelebrimbotToolWindowFactory  # Chat UI panel
└── startup/
    └── CelebrimbotStartupActivity    # Plugin initialization
```

---

## License

This project is based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
