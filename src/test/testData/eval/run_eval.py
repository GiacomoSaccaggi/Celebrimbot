#!/usr/bin/env python3
"""
Celebrimbot Agent Eval — standalone Python runner.
Tests Amazon Q + all local models via Ollama.

Setup local models:
  brew install ollama
  ollama serve &
  ollama pull qwen2.5-coder:1.5b
  ollama pull qwen2.5-coder:7b
  ollama pull llama3.1:8b
  ollama pull deepseek-coder:6.7b
  ollama pull phi3.5
"""

import json, os, re, sys, time, uuid, ssl
import urllib.request, urllib.error
from pathlib import Path
from typing import Optional

# ── Paths ─────────────────────────────────────────────────────────────────────

BASE        = Path(__file__).resolve().parents[4]  # repo root
PROMPTS_DIR = BASE / "src/main/resources/prompts"
SUITE_FILE  = BASE / "src/test/testData/eval/eval_suite.json"
EVAL_DIR    = BASE / "build/eval"
REPORT_PATH = BASE / "EVAL_REPORT.md"

SSO_CACHE   = Path.home() / ".aws/sso/cache"
AQ_ENDPOINT = "https://codewhisperer.us-east-1.amazonaws.com/"
AQ_TARGET   = "AmazonCodeWhispererStreamingService.GenerateAssistantResponse"
OLLAMA_URL  = "http://localhost:11434/api/generate"

_SSL = ssl.create_default_context()
_SSL.check_hostname = False
_SSL.verify_mode = ssl.CERT_NONE

# ── Local model registry ──────────────────────────────────────────────────────

LOCAL_MODELS = {
    "qwen2.5-coder:1.5b": "Qwen 2.5 Coder 1.5B",
    "qwen2.5-coder:7b":   "Qwen 2.5 Coder 7B",
    "llama3.1:8b":        "Llama 3.1 8B",
    "deepseek-coder:6.7b":"DeepSeek Coder 6.7B",
    "phi3.5":             "Phi-3.5 Mini",
}

def available_ollama_models() -> list:
    try:
        req = urllib.request.Request("http://localhost:11434/api/tags")
        with urllib.request.urlopen(req, timeout=3) as r:
            data = json.loads(r.read())
        names = {m["name"].split(":")[0] + ":" + m["name"].split(":")[1]
                 if ":" in m["name"] else m["name"]
                 for m in data.get("models", [])}
        return [m for m in LOCAL_MODELS if m in names or m.split(":")[0] in {n.split(":")[0] for n in names}]
    except Exception:
        return []

# ── Provider configurations ───────────────────────────────────────────────────

ALL_CHARS = ["Gandalf","Galadriel","Aragorn","Elrond","Celebrimbor","Frodo","LegolasGimli","Treebeard","Bilbo"]

def make_configs(local_models: list) -> list:
    configs = [
        {
            "name": "All Claude Sonnet 4.6 (baseline)",
            "description": "Baseline: every character uses Amazon Q",
            "model": "amazon_q",
            "providers": {c: "amazon_q" for c in ALL_CHARS}
        },
        {
            "name": "Claude Sonnet 4.6 core only",
            "description": "Gandalf + Celebrimbor on Amazon Q, rest local prompts",
            "model": "amazon_q_core",
            "providers": {
                "Gandalf": "amazon_q", "Galadriel": "local_aq",
                "Aragorn": "local_aq", "Elrond": "local_aq", "Celebrimbor": "amazon_q",
                "Frodo": "local_aq", "LegolasGimli": "local_aq",
                "Treebeard": "local_aq", "Bilbo": "local_aq"
            }
        },
    ]
    for model_tag in local_models:
        label = LOCAL_MODELS.get(model_tag, model_tag)
        configs.append({
            "name": f"All Local — {label}",
            "description": f"Every character uses {label} via Ollama",
            "model": model_tag,
            "providers": {c: f"ollama:{model_tag}" for c in ALL_CHARS}
        })
        configs.append({
            "name": f"Claude Sonnet 4.6 planners + {label} workers",
            "description": f"Gandalf/Elrond/Celebrimbor/Treebeard on Amazon Q, workers on {label}",
            "model": model_tag,
            "providers": {
                "Gandalf": "amazon_q", "Galadriel": f"ollama:{model_tag}",
                "Aragorn": f"ollama:{model_tag}", "Elrond": "amazon_q", "Celebrimbor": "amazon_q",
                "Frodo": f"ollama:{model_tag}", "LegolasGimli": f"ollama:{model_tag}",
                "Treebeard": "amazon_q", "Bilbo": f"ollama:{model_tag}"
            }
        })
    return configs

# ── SSO token ─────────────────────────────────────────────────────────────────

def read_sso_token() -> Optional[str]:
    if not SSO_CACHE.is_dir():
        return None
    best, best_expiry = None, ""
    for f in SSO_CACHE.glob("*.json"):
        try:
            d = json.loads(f.read_text())
            t, e = d.get("accessToken"), d.get("expiresAt", "")
            if t and e > best_expiry:
                best, best_expiry = t, e
        except Exception:
            pass
    return best

# ── LLM backends ──────────────────────────────────────────────────────────────

def ask_amazon_q(prompt: str, persona: str, token: str) -> str:
    body = json.dumps({"conversationState": {
        "conversationId": str(uuid.uuid4()), "chatTriggerType": "MANUAL",
        "currentMessage": {"userInputMessage": {
            "content": f"{persona}\n\n{prompt}",
            "userInputMessageContext": {}
        }}
    }}).encode()
    req = urllib.request.Request(AQ_ENDPOINT, data=body, headers={
        "Content-Type": "application/x-amz-json-1.0",
        "X-Amz-Target": AQ_TARGET,
        "Authorization": f"Bearer {token}",
    }, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120, context=_SSL) as r:
            raw = r.read().decode("utf-8", errors="replace")
        chunks = re.findall(r'"content"\s*:\s*"((?:[^"\\]|\\.)*)"', raw)
        result = "".join(chunks).replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
        return result.strip() if result.strip() else f"Error: empty AQ response"
    except Exception as e:
        return f"Error: {e}"

def ask_ollama(prompt: str, persona: str, model: str) -> str:
    body = json.dumps({
        "model": model,
        "prompt": f"{persona}\n\n{prompt}",
        "stream": False,
        "options": {"temperature": 0.1, "num_predict": 2048}
    }).encode()
    req = urllib.request.Request(OLLAMA_URL, data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = json.loads(r.read())
        return data.get("response", "Error: no response from Ollama").strip()
    except Exception as e:
        return f"Error: {e}"

# ── Prompt loading ─────────────────────────────────────────────────────────────

def load_prompt(filename: str, use_local: bool) -> str:
    if use_local:
        local_file = PROMPTS_DIR / filename.replace(".txt", "_local.txt")
        if local_file.exists():
            return local_file.read_text().strip()
    return (PROMPTS_DIR / filename).read_text().strip()

# ── Headless pipeline ─────────────────────────────────────────────────────────

class Pipeline:
    def __init__(self, providers: dict, token: str):
        self.providers = providers
        self.token = token
        self.trace = []
        self.logs = []

    def ask(self, character: str, prompt: str, filename: str, json_prefix: str = "") -> str:
        prov = self.providers.get(character, "amazon_q")
        use_local = not prov.startswith("amazon_q")
        persona = load_prompt(filename, use_local)
        if json_prefix and not persona.endswith("\n"):
            persona += "\n"

        if prov == "amazon_q" or prov == "local_aq":
            result = ask_amazon_q(prompt, persona, self.token)
        elif prov.startswith("ollama:"):
            model = prov.split(":", 1)[1]
            result = ask_ollama(prompt, persona, model)
        else:
            result = ask_amazon_q(prompt, persona, self.token)

        # FIX: expose Elrond's JSON in logs for judge visibility
        self.logs.append(f"[{character} ({prov})] → {result[:400]}")
        return result

    def run(self, user_input: str) -> tuple:
        skeleton = "Project Structure:\n(empty test workspace)"

        route_raw = self.ask("Gandalf", user_input, "gandalf_system_prompt.txt").strip().upper()
        # Extract just the route word (Amazon Q may add explanation)
        route_word = "COMPLEX_TASK"
        for word in ["CHAT", "EASY_TASK", "COMPLEX_TASK"]:
            if word in route_raw:
                route_word = word
                break
        self.trace.append(f"[Gandalf: {route_word}]")

        if route_word == "CHAT":
            resp = self.ask("Galadriel", f"User: {user_input}", "galadriel_system_prompt.txt")
            self.trace.append(f"[Galadriel] {resp[:300]}")
        elif route_word == "EASY_TASK":
            self._easy_task(user_input, skeleton)
        else:
            self._complex_task(user_input, skeleton)

        files = []
        return self.trace, self.logs, files

    def _easy_task(self, user_input: str, skeleton: str):
        plan_json = self.ask("Aragorn",
            f"QUEST:\n{user_input}\n\nBACKGROUND:\n{skeleton}",
            "aragorn_system_prompt.txt", json_prefix="{")
        self.trace.append("[Aragorn: preparing task...]")
        self.logs.append(f"[Aragorn plan] {plan_json[:300]}")
        tasks = self._parse_tasks(plan_json)
        self._execute_tasks(tasks, user_input)

    def _complex_task(self, user_input: str, skeleton: str):
        # FIX: expose Elrond JSON in trace (not just logs) so judge can see it
        elrond_json = self.ask("Elrond",
            f"QUEST:\n{user_input}\n\nBACKGROUND:\n{skeleton}",
            "elrond_system_prompt.txt", json_prefix="{")
        self.trace.append(f"[Elrond brief] {elrond_json[:600]}")

        plan_json = self.ask("Celebrimbor",
            f"Brief from Elrond:\n{elrond_json[:2000]}\n\nBACKGROUND:\n{skeleton}",
            "celebrimbor_system_prompt.txt")
        self.trace.append(f"[Celebrimbor plan] {plan_json[:300]}")
        tasks = self._parse_tasks(plan_json)
        self._execute_tasks(tasks, user_input)

    def _execute_tasks(self, tasks: list, user_input: str):
        import tempfile, os
        work_dir = Path(tempfile.mkdtemp(prefix="celebrimbot_eval_"))
        read_context = {}
        written_files = []

        for task in tasks:
            action = task.get("action", "")
            target = task.get("target", "")
            if action == "write_code":
                worker = task.get("worker", "frodo")
                character = "LegolasGimli" if worker == "legolas_gimli" else "Frodo"
                fname = "legolas_gimli_system_prompt.txt" if worker == "legolas_gimli" else "frodo_system_prompt.txt"
                context = read_context.get(target, "")
                wp = f"Task: \"{task.get('instruction','')}\"\nTarget: {target}\n"
                if context:
                    wp += f"Current content:\n{context}\n"
                code_resp = self.ask(character, wp, fname)
                code = self._extract_code(code_resp) or code_resp
                if target:
                    full_path = work_dir / target
                    full_path.parent.mkdir(parents=True, exist_ok=True)
                    full_path.write_text(code)
                    written_files.append((target, code))
                    self.trace.append(f"✅ Code written to {target} ({len(code)} chars)")
            elif action == "read_psi" and target:
                full_path = work_dir / target
                content = full_path.read_text() if full_path.exists() else ""
                read_context[target] = content
                self.trace.append(f"[📄 Read {target}: {len(content)} chars]")
            elif action == "delete_file" and target:
                (work_dir / target).unlink(missing_ok=True)
                self.trace.append(f"✅ Deleted {target}")

        # Treebeard review — FIX: include file content preview in prompt
        if written_files:
            summary = f"User asked: {user_input}\nFiles written:\n"
            for fname, content in written_files:
                summary += f"  - {fname} ({len(content)} chars)\n    Preview: {content[:400]}\n"
            verdict_raw = self.ask("Treebeard",
                f"ORIGINAL QUEST:\n{user_input}\n\n{summary}\n\nDeliver verdict as JSON.",
                "treebeard_system_prompt.txt", json_prefix="{")
            self.trace.append(f"[Treebeard verdict] {verdict_raw[:300]}")
            self.logs.append(f"[Treebeard] {verdict_raw[:300]}")

        # Bilbo summary
        files_str = ", ".join(f for f, _ in written_files)
        bilbo_resp = self.ask("Bilbo",
            f"User asked: {user_input}\nFiles written: {files_str}",
            "bilbo_system_prompt.txt")
        self.trace.append(f"[Bilbo] {bilbo_resp[:300]}")

        # Store files for judge
        self._written_files = written_files
        self._work_dir = work_dir

    def _parse_tasks(self, json_str: str) -> list:
        try:
            cleaned = re.sub(r"```json|```", "", json_str).strip()
            match = re.search(r'\{[\s\S]*\}', cleaned)
            if match:
                return json.loads(match.group()).get("tasks", [])
        except Exception:
            pass
        return []

    def _extract_code(self, text: str) -> Optional[str]:
        match = re.search(r'```(?:\w+)?\n([\s\S]*?)```', text)
        return match.group(1).strip() if match else None

# ── Judge ─────────────────────────────────────────────────────────────────────

def judge(case: dict, actual_route: str, trace: list, logs: list, token: str) -> dict:
    file_section = ""
    # Include written file contents if available
    written = [(t, c) for line in trace for t, c in
               [re.match(r'✅ Code written to (\S+)', line) and
                (re.match(r'✅ Code written to (\S+)', line).group(1), "") or ("", "")]
               if t]

    judge_prompt = f"""Evaluate this AI agent test case strictly.

INPUT: "{case['input']}"
EXPECTED ROUTE: {case['expectedRoute']}
ACTUAL ROUTE: {actual_route}
CRITERIA: {case['judgeCriteria']}

AGENT TRACE (includes Elrond JSON, Treebeard verdict, Bilbo summary, written files):
{chr(10).join(trace[:40])}

INTERNAL LOGS (last 15):
{chr(10).join(logs[-15:])}

Respond with ONLY this JSON (no markdown, no explanation):
{{"passed":true/false,"score":0-10,"reasoning":"one sentence","issues":["issue1","issue2"]}}"""

    persona = "You are a strict evaluator. Respond with valid JSON only. No markdown."
    raw = ask_amazon_q(judge_prompt, persona, token)
    try:
        cleaned = re.sub(r"```json|```", "", raw).strip()
        match = re.search(r'\{[\s\S]*\}', cleaned)
        if match:
            d = json.loads(match.group())
            return {
                "passed": bool(d.get("passed", False)),
                "score": int(d.get("score", 0)),
                "reasoning": str(d.get("reasoning", "")),
                "issues": list(d.get("issues", []))
            }
    except Exception:
        pass
    return {"passed": False, "score": 0, "reasoning": f"Judge parse failed: {raw[:150]}", "issues": []}

# ── Run one configuration ─────────────────────────────────────────────────────

def run_config(config: dict, cases: list, token: str) -> dict:
    name = config["name"]
    print(f"\n{'='*60}\n{name}\n{'='*60}")
    results = []

    for case in cases:
        print(f"  [{case['id']}] {case['input'][:55]}...", end=" ", flush=True)
        start = time.time()
        trace, logs = [], []
        try:
            p = Pipeline(config["providers"], token)
            trace, logs, _ = p.run(case["input"])
        except Exception as e:
            trace = [f"Pipeline error: {e}"]
            logs = [f"ERROR: {e}"]
        duration = int((time.time() - start) * 1000)

        # Extract route
        actual_route = "UNKNOWN"
        for t in trace:
            m = re.search(r'\[Gandalf:\s*([\w_]+)\]', t)
            if m:
                actual_route = m.group(1).upper()
                break

        verdict = judge(case, actual_route, trace, logs, token)
        route_correct = actual_route == case["expectedRoute"]

        results.append({
            "id": case["id"], "input": case["input"],
            "expectedRoute": case["expectedRoute"], "actualRoute": actual_route,
            "routeCorrect": route_correct,
            "passed": verdict["passed"], "score": verdict["score"],
            "reasoning": verdict["reasoning"], "issues": verdict["issues"],
            "durationMs": duration
        })
        icon = "✅" if verdict["passed"] else "❌"
        print(f"{icon} {verdict['score']}/10 route={actual_route} {duration}ms")
        if verdict["issues"]:
            print(f"     ↳ {'; '.join(verdict['issues'][:2])}")

    passed = sum(1 for r in results if r["passed"])
    total = len(results)
    avg = round(sum(r["score"] for r in results) / total, 1) if total else 0
    route_acc = round(sum(1 for r in results if r["routeCorrect"]) / total * 100, 1) if total else 0

    return {
        "name": name, "description": config["description"],
        "model": config.get("model", ""),
        "providers": config["providers"], "results": results,
        "summary": {"passed": passed, "total": total,
                    "passRate": round(passed/total*100, 1) if total else 0,
                    "avgScore": avg, "routeAccuracy": route_acc}
    }

# ── Markdown report ───────────────────────────────────────────────────────────

def write_report(all_configs: list, path: Path):
    ranked = sorted(all_configs, key=lambda c: (c["summary"]["avgScore"], c["summary"]["passRate"]), reverse=True)
    medals = {1: "🥇", 2: "🥈", 3: "🥉"}
    all_ids = [r["id"] for r in ranked[0]["results"]] if ranked else []

    lines = [
        "# Celebrimbot Provider Configuration Ranking",
        f"\nGenerated: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"Test cases: {ranked[0]['summary']['total'] if ranked else 0}",
        f"Configurations tested: {len(ranked)}\n",

        "## 🏆 Ranking\n",
        "| Rank | Configuration | Model | Avg Score | Pass Rate | Route Acc | Passed |",
        "|------|--------------|-------|-----------|-----------|-----------|--------|",
    ]
    for i, cfg in enumerate(ranked, 1):
        s = cfg["summary"]
        medal = medals.get(i, f"#{i}")
        lines.append(f"| {medal} | **{cfg['name']}** | {cfg.get('model','')} | **{s['avgScore']}/10** | {s['passRate']}% | {s['routeAccuracy']}% | {s['passed']}/{s['total']} |")

    lines += ["\n## Score Breakdown by Test Case\n"]
    header = "| Test Case | " + " | ".join(f"{c['name'].split('—')[-1].strip()[:20]}" for c in ranked) + " |"
    sep = "|-----------|" + "|".join(["------"] * len(ranked)) + "|"
    lines += [header, sep]
    for tid in all_ids:
        row = f"| `{tid}` |"
        for cfg in ranked:
            r = next((r for r in cfg["results"] if r["id"] == tid), None)
            if r:
                icon = "✅" if r["passed"] else "❌"
                row += f" {r['score']}/10 {icon} |"
            else:
                row += " — |"
        lines.append(row)

    lines += ["\n## Detailed Results\n"]
    for cfg in ranked:
        s = cfg["summary"]
        lines += [
            f"### {cfg['name']} — {s['avgScore']}/10\n",
            f"*{cfg['description']}*\n",
            "| Test | Expected | Actual | Score | Passed | Issues |",
            "|------|----------|--------|-------|--------|--------|",
        ]
        for r in cfg["results"]:
            ri = "✅" if r["routeCorrect"] else "❌"
            pi = "✅" if r["passed"] else "❌"
            issues = r["issues"][0][:60] if r["issues"] else "—"
            lines.append(f"| `{r['id']}` | {r['expectedRoute']} | {r['actualRoute']} {ri} | {r['score']}/10 | {pi} | {issues} |")
        lines.append("")

    lines += ["\n## Provider Assignments\n"]
    lines += ["| Configuration | Gandalf | Aragorn | Elrond | Celebrimbor | Frodo | Treebeard | Bilbo |",
              "|--------------|---------|---------|--------|-------------|-------|-----------|-------|"]
    for cfg in ranked:
        p = cfg["providers"]
        def fmt(c): return p.get(c,"?").replace("ollama:","").replace("amazon_q","AQ").replace("local_aq","AQ+local")
        lines.append(f"| {cfg['name'][:35]} | {fmt('Gandalf')} | {fmt('Aragorn')} | {fmt('Elrond')} | {fmt('Celebrimbor')} | {fmt('Frodo')} | {fmt('Treebeard')} | {fmt('Bilbo')} |")

    path.write_text("\n".join(lines))
    print(f"\n📊 Report saved → {path}")

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    token = read_sso_token()
    if not token:
        print("❌ Amazon Q not authenticated. Run: aws sso login")
        sys.exit(1)
    print(f"✅ Amazon Q token found")

    local_models = available_ollama_models()
    if local_models:
        print(f"✅ Ollama models available: {', '.join(local_models)}")
    else:
        print("⚠️  No Ollama models found — testing Amazon Q configs only")
        print("   To add local models: brew install ollama && ollama pull qwen2.5-coder:1.5b")

    cases = json.loads(SUITE_FILE.read_text())
    print(f"📋 {len(cases)} test cases loaded")

    configs = make_configs(local_models)
    print(f"🔧 {len(configs)} configurations to test\n")

    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    all_results = []

    for config in configs:
        result = run_config(config, cases, token)
        all_results.append(result)
        (EVAL_DIR / f"{config['name'].replace(' ','_').replace('/','_')[:50]}.json").write_text(
            json.dumps(result, indent=2))

    write_report(all_results, REPORT_PATH)

    print("\n" + "="*60)
    print("FINAL RANKING:")
    print("="*60)
    ranked = sorted(all_results, key=lambda c: c["summary"]["avgScore"], reverse=True)
    for i, cfg in enumerate(ranked, 1):
        s = cfg["summary"]
        print(f"  #{i:2d} {cfg['name'][:50]:<50} {s['avgScore']}/10  pass={s['passRate']}%")

if __name__ == "__main__":
    main()
