<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Celebrimbot Changelog

## [Unreleased]

## [0.0.5]

### Added
- **Kiro (AWS) provider**: every Fellowship character can now use Kiro as its inference backend — selectable per-character in Settings → Celebrimbot → Fellowship AI Configuration. Authentication reads the token Kiro writes to `~/.aws/sso/cache/kiro-auth-token.json` automatically — no separate login needed if Kiro IDE is already open and authenticated
- `CharacterProvider.KIRO` added to the enum alongside `AMAZON_Q`, `ALIBABA_QWEN`, `GOOGLE_GEMINI`, `LOCAL`
- `AmazonQCliProvider.readKiroToken()`: reads `kiro-auth-token.json` and `kiro-auth-token-cli.json` from `~/.aws/sso/cache/`, picks the token with the latest expiry
- `AmazonQCliProvider.isKiroAuthenticated()`, `loginWithKiro()`, `askAsKiro()`: dedicated Kiro auth and inference methods reusing the existing CodeWhisperer streaming API
- Settings UI: "Kiro (AWS)" section with status label, "Check status" and "Login with Kiro" buttons, and privacy notice

## [0.0.4]

### Added
- **Amazon Q Developer provider**: every Fellowship character can now use Claude Sonnet 4.6 (via Amazon Q Developer) as its inference backend — selectable per-character in Settings → Celebrimbot → Fellowship AI Configuration. Authentication reuses the SSO token written by the Amazon Q JetBrains plugin or VSCode extension to `~/.aws/sso/cache/` — no separate CLI or API key required
- `settings/AmazonQSettings.kt`: persistent per-project settings for Amazon Q (SSO start URL, region, timeout, redact-secrets toggle)
- `services/AmazonQCliProvider.kt`: SSO token discovery, authentication check, browser login via `aws sso login`, and inference via the CodeWhisperer Streaming API (`AmazonCodeWhispererStreamingService.GenerateAssistantResponse`). Handles event-stream response parsing, secret redaction, and token expiry detection
- `CharacterProvider.AMAZON_Q` added to the enum — all existing per-character config infrastructure picks it up automatically
- Settings UI: Amazon Q section with status label, "Check status" and "Login with browser" buttons, SSO URL/region fields, timeout slider, and redact-secrets checkbox
- **Per-character provider display in chat**: every agent message now shows which provider responded — e.g. `[🧙 Gandalf (Amazon Q): CHAT]`, `[🧝 Galadriel (Amazon Q)]`, `[💎 Celebrimbor (Amazon Q): forging the plan...]`. Fallback errors are surfaced inline: `(Local Qwen (fallback — Amazon Q error: ...))`
- `CelebrimbotLlmService.LlmResponse`: new wrapper `data class(text, provider)` returned by `askCharacterWithMeta()` — all orchestrator call sites updated to use it
- `askCharacterWithMeta()` replaces the old `askCharacter()` everywhere in the orchestrator; `askCharacter()` kept as a thin delegate for backward compatibility
- **Routing fully delegated to Gandalf's configured provider**: removed all heuristic pre-checks (`forceComplexPatterns`, `isSimilarToPrevious`, `heuristicRoute`). Gandalf now calls `askCharacterWithMeta("Gandalf", ...)` and the result drives routing — if the model returns something unrecognisable, the safe fallback is `COMPLEX_TASK`
- **`promptFor()` helper in orchestrator**: loads `*_system_prompt_local.txt` when the character's provider is `LOCAL`, falls back to the standard prompt otherwise — enables model-specific prompt variants without changing call sites
- **Local prompt variants** (`*_system_prompt_local.txt`) for all 9 characters: shorter, zero Tolkien metaphors, 3 examples max, direct JSON instructions — optimised for 1.5B–7B models that struggle with long prompts
- **`executeTasks()` centralised helper**: replaces three identical inline task-execution loops (EASY_TASK, COMPLEX_TASK, Treebeard re-plan). Maintains a `readContext` map (`path → content`) so `write_code` tasks automatically receive the content of any file previously read by `read_psi` in the same session
- **`buildExecutionSummary()` enriched**: now reads the actual content of written files (first 500 chars preview) so Treebeard can evaluate quality, not just file names
- **Treebeard uses `askCharacterWithMeta`**: removed the hardcoded Alibaba-first logic in `TreebeardReviewService`; Treebeard now respects its configured provider like every other character
- **LLM-as-a-Judge eval framework** (`src/test/testData/eval/`): standalone Python script that runs the full agent pipeline headlessly across 12 provider configurations (Amazon Q + 5 local models via Ollama) and scores each run 0–10 using Claude Sonnet 4.6 (via Amazon Q Developer) as judge
  - `eval_suite.json`: 8 test cases covering routing accuracy, planner quality, worker output, Treebeard behaviour, and Bilbo summary style
  - `run_eval.py`: `HeadlessPipeline`, `AmazonQHeadlessProvider`, `AgentJudge`, `EvalRunner` — no IDE required; outputs `EVAL_REPORT.md` and per-config JSON files in `build/eval/`
  - `EvalCommand` added to CLI (`celebrimbot eval --suite ... --output ...`)

### Changed
- `CelebrimbotAgentOrchestrator`: all `loadPrompt()` calls replaced with `promptFor(character, filename)` — prompt selection is now provider-aware throughout the pipeline
- `bilboSummary()` refactored: uses `askCharacterWithMeta` and shows provider in the Bilbo header
- `writeCodeAction()`: `askWorker()` now returns `Pair<String, String>` (text, provider) and emits the provider in the worker label
- `executeTaskWithRetry()`: Samwise label shows configured provider; escalation to Elrond uses `askCharacterWithMeta`
- Treebeard re-plan in `treebeardAndBilbo()` uses `askCharacterWithMeta` for Celebrimbor

### Fixed
- `sharedContext` bug: previously only the last `read_psi` result was passed to the next task; now a `readContext` map ensures every `write_code` receives the content of its specific file if it was read earlier in the same session
- `companion object` structure in `CelebrimbotAgentOrchestrator` was malformed (constants outside the object); fixed
- `JsonObject` import missing in `TreebeardReviewService` after refactor; restored
- Amazon Q response parsing: handles AWS event-stream binary framing (extracts `"content":"..."` chunks via regex instead of trying to parse the raw binary as JSON)
- Amazon Q endpoint corrected: `/chat/send-message` → `/` (root) with `X-Amz-Target: AmazonCodeWhispererStreamingService.GenerateAssistantResponse` and `Content-Type: application/x-amz-json-1.0`
- Hardcoded absolute path removed from `run_eval.py`; now uses `Path(__file__).resolve().parents[4]` (repo-relative)

---


### Added
- **Feature 3 — The Rings of Power (Dynamic Tool Registry)**: unified all operators into a `ToolRegistry`. Every capability is now a `CelebrimbotTool` with a self-describing JSON schema. Aragorn and Celebrimbor receive auto-generated tool lists via `{{TOOLS}}` placeholder injection — adding a new tool automatically appears in both planners' prompts with zero manual editing
- `model/CelebrimbotTool.kt`: `CelebrimbotTool` interface, `ToolParam`, `ToolResult`, `ToolCategory` data classes
- `registry/ToolRegistry.kt`: central vault with `register`, `get`, `all`, `toJsonSchema()`, and `toMcpToolList()` methods
- `registry/tools/Tools.kt`: 15 tool implementations wrapping all existing operators (`ReadFileTool`, `WriteFileTool`, `DeleteFileTool`, `RunTerminalTool`, `WebSearchTool`, `FetchPageTool`, `ListFilesTool`, `GrepFilesTool`, `FindFileTool`, `FileStatsTool`, `GitStatusTool`, `GitLogTool`, `GitDiffTool`, `GitBlameTool`, `GitBranchTool`)
- `WriteFileTool`: raw file write (no LLM) exposed for MCP clients that generate their own code
- `performAction` refactored from a 15-branch `when` block to a single registry lookup; `write_code` remains the dedicated LLM path (the One Ring)
- **Feature 4 — The Shadow Log (Auto-Undo)**: automatic backup of every file before it is written or deleted
- `io/ShadowLogOperator.kt`: interface + `ShadowOperation`, `ShadowManifest`, `UndoResult`, `SessionSummary` data classes
- `io/HeadlessShadowLogOperator.kt`: java.nio implementation — backups stored in `.celebrimbot/shadow_log/<sessionId>/`, path encoding (`/` → `__`), `.DELETED` suffix for deleted files, max 10 sessions with automatic pruning, `.gitignore` auto-update
- `io/ShadowedFileOperator.kt`: decorator pattern — transparently intercepts `writeFile` and `deleteFile` to trigger backups before delegating; zero changes to `IdeFileOperator` or `HeadlessFileOperator`
- Shadow Log sessions opened/sealed around every EASY_TASK and COMPLEX_TASK execution block (always sealed in `finally`)
- `celebrimbot undo` CLI command: restores the last session, printing restored/removed/recreated files per operation
- **Feature 1 — The Council's Review (Validation Loop)**: after every `write_code`, automatically runs the project's build command and retries up to 3 cycles on failure
- `services/ValidationService.kt`: detects build system from marker files (Gradle, Maven, Node, Cargo, Python); user-configurable override via Settings
- `TerminalOperator` upgraded to return `TerminalResult(output, exitCode)` instead of raw `String` — reliable exit-code-based failure detection replaces fragile keyword matching
- `HeadlessTerminalOperator` and `CelebrimbotTerminalService` updated to surface real OS exit codes
- `RunTerminalTool` now uses `exitCode == 0` for success/failure instead of output keyword scanning
- Council's Review inner loop: emits `[🏛️ Council: validating ... (cycle N/3)]` progress messages; on failure extracts last 80 lines (max 2000 chars) of merged output and feeds it back to the same worker (Frodo or Legolas & Gimli) as a refinement prompt
- `settings/CelebrimbotSettingsState.kt`: added `validationCommand: String = ""` field
- `settings/CelebrimbotSettingsConfigurable.kt`: added "Council's Review" settings group with validation command text field
- **Feature 2 — Elrond's Palantír (BM25 Semantic Index)**: lightweight local index for context-aware file retrieval in COMPLEX_TASK planning
- `index/PalantirIndex.kt`: `PalantirEntry` and `PalantirIndex` data classes; BM25 scorer (k1=1.5, b=0.75, Robertson-Sparck Jones IDF); language-specific regex symbol extraction (Kotlin/Java, Python, JS/TS, Go, Rust); tokenizer with ~50 programming stopwords; `save`/`loadOrNull` JSON persistence at `.celebrimbot/palantir_index.json`
- `PalantirIndex.build()`: full reindex; `buildIncremental()`: reuses unchanged entries, re-tokenizes only modified files
- `isStale()`: returns true if >20% of indexed files have a different `lastModified` on disk
- `ProjectScanOperator.walkSourceFiles()`: returns all indexable source files, skipping `.git`, `build`, `node_modules`, `.gradle`, `.idea`, files >100 KB, and binary files (null-byte detection)
- COMPLEX_TASK branch now queries the Palantír for top-8 relevant files (score + symbols + first 3000 chars each) instead of sending the full project skeleton; falls back to skeleton if index is missing or stale
- `celebrimbot scan` CLI command: builds and persists the Palantír index, prints entry/term counts
- `CelebrimbotStartupActivity`: triggers background incremental index refresh on project open if index is missing or older than 1 hour
- **Feature 5 — The Beacons of Gondor (MCP Bridge)**: Celebrimbot is now a Model Context Protocol server
- `mcp/McpTransport.kt`: compact Gson JSON-RPC 2.0 response/error formatter (no pretty-printing — critical for Stdio line framing)
- `mcp/McpRouter.kt`: full JSON-RPC 2.0 dispatcher handling `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `resources/list`, `resources/read`; `-32601` for unknown methods, `-32700` for parse errors
- `ToolRegistry.toMcpToolList()`: transforms registered tools into MCP-compliant `inputSchema` JSON Schema format
- `ServeCommand` upgraded: binds to `127.0.0.1` only, adds `POST /mcp` endpoint alongside legacy `POST /forge`
- `McpStdioCommand` (`celebrimbot mcp-stdio`): newline-delimited JSON-RPC over stdin/stdout for Claude Desktop and other MCP hosts; diagnostic output redirected to stderr; `stdout.flush()` after every response
- `buildMcpRouter()` shared factory: identical tool set across HTTP and Stdio transports
- MCP protocol version `2024-11-05`; `resources/list` exposes up to 100 project source files as `file:///` URIs with MIME type guessing
- **Treebeard (Ent Reviewer — Reflection Loop)**: new critic agent inserted between Workers and Bilbo; reviews every completed plan against the original user request and triggers re-planning if the work is incomplete
- `model/TreebeardReviewResult.kt`: data class with `isComplete: Boolean`, `reasoning: String`, `additionalRequestsForPlanner: List<String>`
- `services/TreebeardReviewService.kt`: calls Alibaba Cloud (falls back to local engine) with Treebeard's persona; parses the LLM's JSON verdict; returns a safe `isComplete=true` fallback if the LLM is unreachable so the flow is never permanently blocked
- `prompts/treebeard_system_prompt.txt`: Treebeard's persona — slow, methodical, hates hasty code; strict JSON-only output schema; explicit evaluation checklist for Python packages (module files, setup.py, tests, README); examples of good vs bad `additionalRequestsForPlanner` entries
- `treebeardAndBilbo()` in `CelebrimbotAgentOrchestrator`: reflection loop with `MAX_REFLECTION_LOOPS = 2`; on incomplete verdict, missing tasks are sent back to Celebrimbor for a new execution cycle; each reflection cycle opens its own Shadow Log session; on loop limit Treebeard concedes and Bilbo chronicles the full accumulated task list
- `buildExecutionSummary()` helper: builds a human-readable summary of all executed tasks for Treebeard's review
- Deterministic routing pre-check in `route()`: keyword patterns (`pacchett`, `package`, `refactor`, `sposta`, `rinomina`, etc.) force `COMPLEX_TASK` before the LLM is consulted, bypassing the 1.5B model's unreliable routing for multi-file requests
- Aragorn, Elrond, and Celebrimbor prompts reframed: user request labelled `QUEST (focus on this)`, project skeleton labelled `BACKGROUND CONTEXT (do NOT reuse these paths for new files)` — prevents the model from treating existing file paths as targets for new files
- Elrond prompt: added `PACKAGE NAME` rule — derives snake_case name from request content when user doesn't specify one; explicitly forbids using `template` as a default package name
- Gandalf prompt: `EASY_TASK` redefined as single-file only; `COMPLEX_TASK` explicitly includes package creation; added package-specific routing examples
- Aragorn and Celebrimbor prompts: massively expanded few-shot example sets covering all action types (single file, multi-file, git, terminal, web, scan, delete, move, refactor, package creation with correct folder rules)
- Inference token limit raised: `CelebrimbotEmbeddedEngine` and `StandaloneLlmEngine` 1024 → 2048 — prevents 8-task package plans from being truncated

### Fixed
- `IdeTerminalOperator` return type updated to `CompletableFuture<TerminalResult>` to match upgraded interface
- `sanitizeJson()` helper: replaces literal newlines inside JSON string values with spaces before Gson parsing — prevents truncated plans from local model output with unescaped newlines in `instruction` fields
- Routing fallback (no local model): changed from `if (length < 60) CHAT else EASY_TASK` to always `EASY_TASK` — short prompts like "creami un pacchetto" were incorrectly routed to Galadriel
- Celebrimbor's brief now sanitized and capped at 2000 chars before injection into the planner prompt
- Aragorn and Elrond prompts: added language enforcement rule (Python request → only `.py` targets), no-invented-constraints rule, Python package structure rule (`__init__.py` + module + `setup.py` + `pyproject.toml` + `requirements.txt` + `README.md` + tests)
- Celebrimbor prompt: added completeness law (N files in brief → exactly N tasks in plan)
- `DeleteFileTool` output no longer contains HTML `<b>` tags (inappropriate outside IDE chat UI)
- Numerous code quality fixes: removed unused imports, dead functions (`parseStewardResult`, `parsePlan`, `askSamwise`, `askQuestion`), replaced deprecated `ProcessAdapter` with `ProcessListener`, replaced deprecated `URL(String)` with `URI.toURL()`, converted `try-finally` to `use()`, fixed redundant regex escapes, renamed `LOG` → `log` (naming convention), extracted duplicated Bilbo summary and LLM inference patterns into shared helpers
- Netty transitive dependency pinned to `4.1.132.Final` via `resolutionStrategy.force` to eliminate CVEs (CVE-2025-55163, CVE-2025-58057, CVE-2026-33871, etc.)
- `verifyPlugin`: `externalPrefixes` added for Clikt and Ktor packages (CLI-only, absent from plugin ZIP — suppresses false positives)
- `pluginVersion` bumped to `0.0.3` in `gradle.properties`

---

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
