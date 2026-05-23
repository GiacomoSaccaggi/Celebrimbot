# Celebrimbot Provider Configuration Ranking

Generated: 2026-05-23 03:39:24
Test cases: 8
Configurations tested: 12

## 🏆 Ranking

| Rank | Configuration | Model | Avg Score | Pass Rate | Route Acc | Passed |
|------|--------------|-------|-----------|-----------|-----------|--------|
| 🥇 | **Claude Sonnet 4.6 planners + Qwen 2.5 Coder 7B workers** | qwen2.5-coder:7b | **9.0/10** | 100.0% | 87.5% | 8/8 |
| 🥈 | **Claude Sonnet 4.6 planners + Phi-3.5 Mini workers** | phi3.5 | **9.0/10** | 100.0% | 87.5% | 8/8 |
| 🥉 | **Claude Sonnet 4.6 planners + Llama 3.1 8B workers** | llama3.1:8b | **8.9/10** | 100.0% | 87.5% | 8/8 |
| #4 | **All Claude Sonnet 4.6 (baseline)** | amazon_q | **8.6/10** | 87.5% | 87.5% | 7/8 |
| #5 | **Claude Sonnet 4.6 core only** | amazon_q_core | **8.4/10** | 87.5% | 87.5% | 7/8 |
| #6 | **Claude Sonnet 4.6 planners + Qwen 2.5 Coder 1.5B workers** | qwen2.5-coder:1.5b | **7.8/10** | 87.5% | 87.5% | 7/8 |
| #7 | **All Local — Qwen 2.5 Coder 7B** | qwen2.5-coder:7b | **7.8/10** | 87.5% | 75.0% | 7/8 |
| #8 | **All Local — Llama 3.1 8B** | llama3.1:8b | **7.8/10** | 87.5% | 87.5% | 7/8 |
| #9 | **All Local — Phi-3.5 Mini** | phi3.5 | **6.2/10** | 62.5% | 75.0% | 5/8 |
| #10 | **Claude Sonnet 4.6 planners + DeepSeek Coder 6.7B workers** | deepseek-coder:6.7b | **6.1/10** | 75.0% | 87.5% | 6/8 |
| #11 | **All Local — DeepSeek Coder 6.7B** | deepseek-coder:6.7b | **4.9/10** | 50.0% | 62.5% | 4/8 |
| #12 | **All Local — Qwen 2.5 Coder 1.5B** | qwen2.5-coder:1.5b | **4.9/10** | 37.5% | 62.5% | 3/8 |

## Score Breakdown by Test Case

| Test Case | Claude Sonnet 4.6 planners +  | Claude Sonnet 4.6 planners +  | Claude Sonnet 4.6 planners +  | All Claude Sonnet 4.6 (baseli | Claude Sonnet 4.6 core only | Claude Sonnet 4.6 planners +  | Qwen 2.5 Coder 7B | Llama 3.1 8B | Phi-3.5 Mini | Claude Sonnet 4.6 planners +  | DeepSeek Coder 6.7B | Qwen 2.5 Coder 1.5B |
|-----------|------|------|------|------|------|------|------|------|------|------|------|------|
| `routing_chat_greeting` | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ |
| `routing_chat_question` | 9/10 ✅ | 10/10 ✅ | 9/10 ✅ | 10/10 ✅ | 10/10 ✅ | 9/10 ✅ | 9/10 ✅ | 10/10 ✅ | 2/10 ❌ | 7/10 ✅ | 2/10 ❌ | 1/10 ❌ |
| `routing_easy_task` | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 10/10 ✅ | 7/10 ✅ | 8/10 ✅ | 10/10 ✅ |
| `routing_complex_task_package` | 10/10 ✅ | 9/10 ✅ | 8/10 ✅ | 9/10 ✅ | 8/10 ✅ | 8/10 ✅ | 8/10 ✅ | 7/10 ✅ | 4/10 ❌ | 7/10 ✅ | 7/10 ✅ | 4/10 ❌ |
| `elrond_enriches_context` | 8/10 ✅ | 9/10 ✅ | 9/10 ✅ | 8/10 ✅ | 7/10 ✅ | 7/10 ✅ | 0/10 ❌ | 6/10 ✅ | 6/10 ✅ | 7/10 ✅ | 3/10 ❌ | 7/10 ✅ |
| `treebeard_detects_incomplete_work` | 7/10 ✅ | 7/10 ✅ | 7/10 ✅ | 3/10 ❌ | 2/10 ❌ | 7/10 ✅ | 7/10 ✅ | 7/10 ✅ | 7/10 ✅ | 3/10 ❌ | 1/10 ❌ | 2/10 ❌ |
| `frodo_writes_valid_python` | 9/10 ✅ | 9/10 ✅ | 8/10 ✅ | 10/10 ✅ | 10/10 ✅ | 9/10 ✅ | 9/10 ✅ | 3/10 ❌ | 2/10 ❌ | 7/10 ✅ | 7/10 ✅ | 3/10 ❌ |
| `bilbo_summary_quality` | 9/10 ✅ | 8/10 ✅ | 10/10 ✅ | 9/10 ✅ | 10/10 ✅ | 2/10 ❌ | 9/10 ✅ | 9/10 ✅ | 9/10 ✅ | 1/10 ❌ | 1/10 ❌ | 2/10 ❌ |

## Detailed Results

### Claude Sonnet 4.6 planners + Qwen 2.5 Coder 7B workers — 9.0/10

*Gandalf/Elrond/Celebrimbor/Treebeard on Amazon Q, workers on Qwen 2.5 Coder 7B*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 9/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 10/10 | ✅ | — |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 8/10 | ✅ | full_request focuses on informing the user about empty works |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 7/10 | ✅ | Gandalf misrouted to EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | — |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | — |

### Claude Sonnet 4.6 planners + Phi-3.5 Mini workers — 9.0/10

*Gandalf/Elrond/Celebrimbor/Treebeard on Amazon Q, workers on Phi-3.5 Mini*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 9/10 | ✅ | Treebeard marked isComplete as false due to quality concerns |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 9/10 | ✅ | — |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 7/10 | ✅ | Route was misclassified as EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | The Frodo output appears slightly truncated in the logs but  |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 8/10 | ✅ | — |

### Claude Sonnet 4.6 planners + Llama 3.1 8B workers — 8.9/10

*Gandalf/Elrond/Celebrimbor/Treebeard on Amazon Q, workers on Llama 3.1 8B*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 9/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 8/10 | ✅ | Treebeard marked isComplete as false due to shallow __init__ |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 9/10 | ✅ | — |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 7/10 | ✅ | Gandalf misrouted as EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 8/10 | ✅ | Truncated trace shows 'return self.stack.pop' without parent |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |

### All Claude Sonnet 4.6 (baseline) — 8.6/10

*Baseline: every character uses Amazon Q*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 9/10 | ✅ | Treebeard marked isComplete as false suggesting setup.py pla |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 8/10 | ✅ | full_request focuses on informing user about empty workspace |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 3/10 | ❌ | Gandalf routed to EASY_TASK instead of expected COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | — |

### Claude Sonnet 4.6 core only — 8.4/10

*Gandalf + Celebrimbor on Amazon Q, rest local prompts*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 8/10 | ✅ | Agent trace is truncated so full file creation cannot be ver |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | full_request is more of an error message than a restatement  |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 2/10 | ❌ | Incorrect routing: COMPLEX_TASK expected but EASY_TASK was c |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |

### Claude Sonnet 4.6 planners + Qwen 2.5 Coder 1.5B workers — 7.8/10

*Gandalf/Elrond/Celebrimbor/Treebeard on Amazon Q, workers on Qwen 2.5 Coder 1.5B*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 9/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 8/10 | ✅ | Treebeard marked isComplete as false due to structural issue |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | Bilbo's summary falsely claims logging was added when no Pyt |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 7/10 | ✅ | Gandalf misrouted to EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | The Frodo output is truncated so we cannot fully verify the  |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 2/10 | ❌ | Bilbo does not address the user as 'Mellow' |

### All Local — Qwen 2.5 Coder 7B — 7.8/10

*Every character uses Qwen 2.5 Coder 7B via Ollama*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 9/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 8/10 | ✅ | Treebeard incorrectly reported README.md as missing even tho |
| `elrond_enriches_context` | COMPLEX_TASK | UNKNOWN ❌ | 0/10 | ❌ | Pipeline error prevented any processing |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 7/10 | ✅ | Gandalf misrouted to EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | The Frodo output appears slightly truncated in the logs but  |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | — |

### All Local — Llama 3.1 8B — 7.8/10

*Every character uses Llama 3.1 8B via Ollama*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | Treebeard marked the task as incomplete due to setup.py bein |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 6/10 | ✅ | Elrond JSON appears truncated (missing closing brace/quote i |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 7/10 | ✅ | Gandalf routed to EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 3/10 | ❌ | Treebeard marked isComplete as false due to a typo in the po |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | — |

### All Local — Phi-3.5 Mini — 6.2/10

*Every character uses Phi-3.5 Mini via Ollama*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | COMPLEX_TASK ❌ | 2/10 | ❌ | Gandalf output COMPLEX_TASK instead of required CHAT |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 4/10 | ❌ | No evidence of actual file creation/writing in the trace |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 6/10 | ✅ | Elrond's full_request is truncated so we cannot fully verify |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | Treebeard JSON appears truncated and may not be valid JSON |
| `frodo_writes_valid_python` | EASY_TASK | COMPLEX_TASK ❌ | 2/10 | ❌ | Routed to COMPLEX_TASK instead of EASY_TASK |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 9/10 | ✅ | — |

### Claude Sonnet 4.6 planners + DeepSeek Coder 6.7B workers — 6.1/10

*Gandalf/Elrond/Celebrimbor/Treebeard on Amazon Q, workers on DeepSeek Coder 6.7B*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | CHAT ✅ | 7/10 | ✅ | Galadriel incorrectly describes contrastive learning as supe |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 7/10 | ✅ | Bilbo's response asks for clarification instead of confirmin |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | pyproject.toml contains a fibonacci function instead of vali |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | full_request focuses on the empty workspace condition rather |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 3/10 | ❌ | Gandalf routed to EASY_TASK instead of COMPLEX_TASK |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 7/10 | ✅ | Frodo's logged output appears truncated, making it unclear i |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 1/10 | ❌ | Bilbo does not address the user as 'Mellow' |

### All Local — DeepSeek Coder 6.7B — 4.9/10

*Every character uses DeepSeek Coder 6.7B via Ollama*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | EASY_TASK ❌ | 2/10 | ❌ | Gandalf output EASY_TASK instead of required CHAT |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 8/10 | ✅ | Bilbo's summary incorrectly states the request is unclear de |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | Treebeard marked the task as incomplete due to missing add_e |
| `elrond_enriches_context` | COMPLEX_TASK | EASY_TASK ❌ | 3/10 | ❌ | Route misclassified as EASY_TASK instead of COMPLEX_TASK |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | EASY_TASK ❌ | 1/10 | ❌ | Treebeard set isComplete=true when README.md is only 376 cha |
| `frodo_writes_valid_python` | EASY_TASK | EASY_TASK ✅ | 7/10 | ✅ | Frodo's generated code appears truncated at 'raise In' sugge |
| `bilbo_summary_quality` | EASY_TASK | EASY_TASK ✅ | 1/10 | ❌ | Does not address user as 'Mellow' |

### All Local — Qwen 2.5 Coder 1.5B — 4.9/10

*Every character uses Qwen 2.5 Coder 1.5B via Ollama*

| Test | Expected | Actual | Score | Passed | Issues |
|------|----------|--------|-------|--------|--------|
| `routing_chat_greeting` | CHAT | CHAT ✅ | 10/10 | ✅ | — |
| `routing_chat_question` | CHAT | COMPLEX_TASK ❌ | 1/10 | ❌ | Gandalf routed to COMPLEX_TASK instead of required CHAT |
| `routing_easy_task` | EASY_TASK | EASY_TASK ✅ | 10/10 | ✅ | — |
| `routing_complex_task_package` | COMPLEX_TASK | COMPLEX_TASK ✅ | 4/10 | ❌ | No setup.py file was actually created/written despite being  |
| `elrond_enriches_context` | COMPLEX_TASK | COMPLEX_TASK ✅ | 7/10 | ✅ | full_request describes creating new files from scratch rathe |
| `treebeard_detects_incomplete_work` | COMPLEX_TASK | COMPLEX_TASK ✅ | 2/10 | ❌ | README.md is only 57 chars, well below the 500 char minimum |
| `frodo_writes_valid_python` | EASY_TASK | COMPLEX_TASK ❌ | 3/10 | ❌ | Route mismatch: expected EASY_TASK but got COMPLEX_TASK |
| `bilbo_summary_quality` | EASY_TASK | COMPLEX_TASK ❌ | 2/10 | ❌ | User is not addressed as 'Mellow' |


## Provider Assignments

| Configuration | Gandalf | Aragorn | Elrond | Celebrimbor | Frodo | Treebeard | Bilbo |
|--------------|---------|---------|--------|-------------|-------|-----------|-------|
| Claude Sonnet 4.6 planners + Qwen 2.5 Coder  | AQ | qwen2.5-coder:7b | AQ | AQ | qwen2.5-coder:7b | AQ | qwen2.5-coder:7b |
| Claude Sonnet 4.6 planners + Phi-3.5 Mini wo | AQ | phi3.5 | AQ | AQ | phi3.5 | AQ | phi3.5 |
| Claude Sonnet 4.6 planners + Llama 3.1 8B wo | AQ | llama3.1:8b | AQ | AQ | llama3.1:8b | AQ | llama3.1:8b |
| All Claude Sonnet 4.6 (baseline) | AQ | AQ | AQ | AQ | AQ | AQ | AQ |
| Claude Sonnet 4.6 core only | AQ | AQ+local | AQ+local | AQ | AQ+local | AQ+local | AQ+local |
| Claude Sonnet 4.6 planners + Qwen 2.5 Coder  | AQ | qwen2.5-coder:1.5b | AQ | AQ | qwen2.5-coder:1.5b | AQ | qwen2.5-coder:1.5b |
| All Local — Qwen 2.5 Coder 7B | qwen2.5-coder:7b | qwen2.5-coder:7b | qwen2.5-coder:7b | qwen2.5-coder:7b | qwen2.5-coder:7b | qwen2.5-coder:7b | qwen2.5-coder:7b |
| All Local — Llama 3.1 8B | llama3.1:8b | llama3.1:8b | llama3.1:8b | llama3.1:8b | llama3.1:8b | llama3.1:8b | llama3.1:8b |
| All Local — Phi-3.5 Mini | phi3.5 | phi3.5 | phi3.5 | phi3.5 | phi3.5 | phi3.5 | phi3.5 |
| Claude Sonnet 4.6 planners + DeepSeek Coder  | AQ | deepseek-coder:6.7b | AQ | AQ | deepseek-coder:6.7b | AQ | deepseek-coder:6.7b |
| All Local — DeepSeek Coder 6.7B | deepseek-coder:6.7b | deepseek-coder:6.7b | deepseek-coder:6.7b | deepseek-coder:6.7b | deepseek-coder:6.7b | deepseek-coder:6.7b | deepseek-coder:6.7b |
| All Local — Qwen 2.5 Coder 1.5B | qwen2.5-coder:1.5b | qwen2.5-coder:1.5b | qwen2.5-coder:1.5b | qwen2.5-coder:1.5b | qwen2.5-coder:1.5b | qwen2.5-coder:1.5b | qwen2.5-coder:1.5b |