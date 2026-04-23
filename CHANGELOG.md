<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Celebrimbot Changelog

## [Unreleased]

## [0.0.2]

### Added
- **Frodo worker**: new Local Qwen worker for all `write_code` tasks — adventurous hobbit that fills gaps with hobbit-sense
- **Legolas & Gimli worker**: new cloud-first expert worker duo (Alibaba → Gemini → local fallback) for complex algorithms, large refactors, and tasks Frodo failed at
- `CelebrimbotTask.worker` field: planners assign `"worker":"frodo"` for standard code tasks, `"worker":"legolas_gimli"` for complex ones
- **Tolkienian personas**: all 9 agents (Gandalf, Galadriel, Aragorn, Elrond, Celebrimbor, Samwise, Frodo, Legolas & Gimli, Bilbo) have full character prompts with Tolkienian tone and rich few-shot examples, stored in `src/main/resources/prompts/`
- Galadriel always responds in English regardless of user language, addresses user as "Mellow" (Dwarvish for friend)
- Bilbo addresses user as "Mellow" and chronicles only what actually happened (no hallucinated actions)
- `onComplete` callback in `executePlan`: conversation history is updated only once per turn, after all progress messages are collected
- `onInternalLog` callback: captures every LLM prompt/response exchange; exposed via the **Copy** button under `--- Internal Layer Exchanges ---`
- Partial JSON recovery in `parsePlannerResult`: when the local model truncates the response, individual task objects are salvaged via regex before falling back to `inferPlanFromPrompt`
- `cleanForHistory()` utility: strips HTML tags, entities, bracket prefixes, and `"Could not parse plan"` noise before storing messages in conversation history
- Selected-code context injected into prompt only when selection is > 20 characters (avoids accidental number selections polluting context)
- Structured Bilbo summary: passes `Files read/written/deleted` and `Commands run` instead of raw output, preventing hallucination
- Three-way router: `CHAT`, `EASY_TASK`, `COMPLEX_TASK` — replaces the previous binary `CHAT`/`PLAN` routing
- Conversation history passed to the router so repeated failed requests automatically escalate to `COMPLEX_TASK`
- Similarity scoring in router: prompts sharing >80% of significant words (length > 4) with a recent message force `COMPLEX_TASK`
- `EASY_TASK` path: Aragorn produces a full task list (not just a single task); executes locally without calling the cloud Planner
- `WebSearchOperator` / `DuckDuckGoSearchOperator` — DuckDuckGo Instant Answer search (no API key) + `fetch_page`
- `ProjectScanOperator` / `HeadlessProjectScanOperator` — `list_files`, `grep_files`, `find_file`, `file_stats`
- `GitOperator` / `HeadlessGitOperator` — `git_status`, `git_log`, `git_diff`, `git_blame`, `git_branch`
- `delete_file` action with VirtualFile-based deletion in IDE mode
- 15 total actions: `read_psi`, `write_code`, `delete_file`, `list_files`, `grep_files`, `find_file`, `file_stats`, `git_status`, `git_log`, `git_diff`, `git_blame`, `git_branch`, `web_search`, `fetch_page`, `run_terminal`
- Standalone CLI via Clikt: `celebrimbot forge`, `celebrimbot scan`, `celebrimbot serve`
- `HeadlessFileOperator` — `java.nio`-based file operations without IntelliJ dependency
- `HeadlessTerminalOperator` — `/bin/sh -c` execution (handles pipes, quotes, redirects)
- `StandaloneLlmEngine` — llama.cpp inference without IntelliJ services
- `IdeFileOperator` / `IdeTerminalOperator` — IDE-bound operator implementations
- HTTP bridge via Ktor/Netty: `GET /health`, `POST /forge`
- Shadow JAR (`celebrimbot.jar`) with CLI deps isolated via `cliOnly` Gradle configuration
- Apple Silicon auto-detection via `os.arch == "aarch64"`, Metal acceleration with `nGpuLayers = 99`
- Stats counter in chat header: `🖥️ N  ☁️ N` (local inferences / cloud planner calls), resets on Clear
- Markdown rendering in chat: bold, italic, lists, headings, inline code, fenced code blocks, links
- Chat bubbles fully responsive: width adapts to viewport on resize via `ComponentListener`
- `inferPlanFromPrompt` fallback: when planner returns empty/invalid JSON, intent is inferred directly from prompt text
- `model/CelebrimbotPlan.kt`: `CelebrimbotTask` and `CelebrimbotPlan` data classes extracted into dedicated model package

### Fixed
- Galadriel prefix no longer duplicated in chat (`[🧝 Galadriel] [🧝 Galadriel]` bug)
- Conversation history updated only once per turn via `onComplete`, not on every `onProgress` call
- `parseAragornResult` now correctly reads `tasks` array (was reading single `task` object, causing fallback to `inferPlanFromPrompt` on every EASY_TASK)
- Leading slash stripped from all task target paths before execution (`/src/foo.py` → `src/foo.py`)
- Elrond `file_contents` always empty `{}` — model no longer hallucinates file content
- Celebrimbor instructed to emit compact single-line JSON per task to avoid truncation
- `isModelDownloaded()` now validates file size (≥ 800 MB) to detect corrupted/partial downloads — partial files are deleted and re-downloaded automatically
- `IdeFileOperator.readFile` uses `ReadAction.compute` instead of `WriteCommandAction`
- `HeadlessTerminalOperator` uses `/bin/sh -c` instead of naive `split(" ")`
- Clikt and Ktor declared as `compileOnly` + `cliOnly` — no longer bundled in plugin ZIP

---

## [0.0.1]

### Added
- Initial plugin scaffold from IntelliJ Platform Plugin Template
- Conversational AI chat with project context awareness via Local Qwen 2.5 Coder 1.5B
- Autonomous code editing: `read_psi` + `write_code` actions
- Terminal execution via `run_terminal` action
- Two-way router: `CHAT` or `PLAN` decided by local Qwen model
- Multi-agent loop: Planner → Worker → 3 retries → escalation
- Multi-provider fallback: Alibaba Cloud (Qwen Plus) → Google Gemini → local embedded model
- Offline-first: embedded Qwen 2.5 Coder 1.5B-Instruct Q4_K_M (GGUF), downloaded automatically on first use
- Secure API key storage via IntelliJ PasswordSafe
- Per-project settings: provider, base URL, model name, API keys
- Chat UI tool window anchored to the right side of the IDE
- Model unloaded from RAM when tool window is hidden
