#!/usr/bin/env python3
from datetime import datetime
from zoneinfo import ZoneInfo
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
import hashlib
import json
import sqlite3
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
APP = PROJECT / "build/install/ai-agents/bin/ai-agents"


FIXED_ORDER = """def calculate_total(prices: list[float], discount_percent: float) -> float:
    if not 0 <= discount_percent <= 100:
        raise ValueError("discount_percent must be between 0 and 100")
    return sum(prices) * (1 - discount_percent / 100)
"""


def scripted_reply(messages):
    stage = sum(message['role'] == 'assistant' for message in messages)

    def call(name, arguments):
        return {'id': f"scripted-{stage}-{name}-{arguments.get('path', '')}", 'type': 'function',
                'function': {'name': name, 'arguments': json.dumps(arguments)}}

    task_mode = any(message['role'] == 'system' and 'Task Mode:' in (message.get('content') or '') for message in messages)
    if task_mode and any(message.get('content', '').startswith('All tasks completed with runtime evidence.') for message in messages if message.get('content')):
        return {'role': 'assistant', 'content': 'All three tasks completed with saved runtime evidence.'}
    approved = any(message['role'] == 'user' and message.get('content') == 'Plan approved. Execute it now.' for message in messages)
    if 'Start in Plan Mode' in messages[0]['content'] and not approved:
        if stage == 0:
            calls = [call('read_file', {'path': 'order.py'})]
        else:
            calls = [call('submit_plan', {
                'goal': 'Fix order',
                'steps': [
                    {'description': 'Read source and tests', 'tools': ['read_file']},
                    {'description': 'Fix calculation', 'tools': ['write_file']},
                    {'description': 'Run tests', 'tools': ['Bash']},
                ],
                'risks': ['Bash has user permissions'],
                'assumptions': ['Python is available'],
                'done_when': 'Tests pass without editing test_order.py',
            })]
    elif task_mode:
        current_index = max(index for index, message in enumerate(messages) if (message.get('content') or '').startswith('Current task: '))
        task, _ = json.JSONDecoder().raw_decode(messages[current_index]['content'][len('Current task: '):])
        current = messages[current_index:]
        completed = {message.get('tool_call_id') for message in current if message['role'] == 'tool'}
        evidence = [item for message in current for item in message.get('tool_calls', [])
                    if item['id'] in completed and item['function']['name'] == task['verificationTool']]
        if evidence:
            calls = [call('complete_task', {'task_id': task['id'], 'call_id': evidence[-1]['id']})]
        elif task['id'] == 'inspect':
            calls = [call('read_file', {'path': 'order.py'})]
        elif task['id'] == 'modify':
            calls = [call('write_file', {'path': 'order.py', 'content': FIXED_ORDER})]
        else:
            calls = [call('Bash', {'command': 'python3 -B -m unittest -v', 'cwd': '.'})]
    else:
        completed = {message.get('tool_call_id') for message in messages if message['role'] == 'tool'}
        done = {item['function']['name'] for message in messages for item in message.get('tool_calls', []) if item['id'] in completed}
        if 'Bash' in done:
            return {'role': 'assistant', 'content': 'Scripted control task finished. Inspect the actual Bash result.'}
        if 'write_file' in done:
            calls = [call('Bash', {'command': 'python3 -B -m unittest -v', 'cwd': '.'})]
        elif 'list_files' in done:
            calls = [call('write_file', {'path': 'order.py', 'content': FIXED_ORDER})]
        else:
            calls = [call('list_files', {'path': '.'}), call('read_file', {'path': 'order.py'}),
                     call('read_file', {'path': 'test_order.py'})]
    if task_mode and calls[0]['function']['name'] == 'submit_plan':
        plan = json.loads(calls[0]['function']['arguments'])
        for step, task_id, dependency in zip(plan['steps'], ['inspect', 'modify', 'test'], [[], ['inspect'], ['modify']]):
            step.update(id=task_id, depends_on=dependency, verification_tool=step['tools'][0])
        calls[0]['function']['arguments'] = json.dumps(plan)
    return {'role': 'assistant', 'content': None, 'tool_calls': calls}


@contextmanager
def mock_model_server():
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != '/v1/chat/completions':
                self.send_error(404)
                return
            payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            body = json.dumps({'choices': [{'message': scripted_reply(payload['messages'])}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}/v1/'
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert APP.exists(), "First run ./gradlew installDist"
    lines = ["# Локальная проверка CLI", "", "Команда: `python3 scripts/verify_cli.py` после `./gradlew test installDist`.",
             "Каждый вызов CLI запускается отдельным JVM-процессом. API модели — локальный тестовый HTTP-сервер; Bash и файловые операции настоящие.", ""]
    with mock_model_server() as url, tempfile.TemporaryDirectory(prefix="hse-agent-cli-") as tmp:
        environment = dict(os.environ, OPENAI_BASE_URL=url, OPENAI_API_KEY="test", OPENAI_MODEL="scripted")
        lines += ["Тестовый API: `" + url + "`.", "Начало: " + datetime.now(ZoneInfo("Europe/Moscow")).isoformat(), ""]
        base = Path(tmp)

        def scenario(name):
            folder = base / name
            folder.mkdir()
            shutil.copytree(PROJECT / "workspace", folder / "workspace")
            return folder

        def invoke(folder, *args, expected=0):
            command = [str(APP), *args, "--state-dir", str(folder / "runs")]
            output = subprocess.run(command, cwd=PROJECT, text=True, capture_output=True, timeout=30, env=environment)
            assert output.returncode == expected, (command, output.returncode, output.stdout, output.stderr)
            lines.extend(["```text", "ai-agents " + " ".join(args), "exit_code=" + str(output.returncode),
                          output.stdout.strip(), output.stderr.strip(), "```", ""])
            return output.stdout

        def start(folder, *extra, expected=0):
            text = invoke(folder, "start", "--workspace", str(folder / "workspace"),
                          "--policy", str(PROJECT / "policy.json"), *extra, expected=expected)
            return re.search(r"run_id=([0-9a-f-]+)", text).group(1)

        def state(folder, run_id):
            with sqlite3.connect(folder / "runs/agent.db") as connection:
                return json.loads(connection.execute("SELECT state_json FROM runs WHERE id=?", (run_id,)).fetchone()[0])

        auto = scenario("auto")
        tests_hash = digest(auto / "workspace/test_order.py")
        run_id = start(auto, "--mode", "auto")
        result = state(auto, run_id)
        assert result["status"] == "COMPLETED"
        assert result["budget"]["used"]["modelRequests"] > 0
        assert result["budget"]["used"]["toolCalls"] > 0
        assert result["budget"]["used"]["sideEffects"] == 2
        assert result["budget"]["used"]["resultBytes"] > 0
        budget_text = invoke(auto, "budget", run_id)
        assert "[budget]" in budget_text and "осталось" in budget_text
        before_resume = result["budget"]
        invoke(auto, "resume", run_id)
        assert state(auto, run_id)["budget"] == before_resume
        bash = next(v for v in result["calls"].values() if v["call"]["name"] == "Bash")
        assert json.loads(bash["result"])["exit_code"] == 0
        assert digest(auto / "workspace/test_order.py") == tests_hash
        lines += ["AUTO: COMPLETED; Bash exit_code=0; SHA-256 test_order.py unchanged.", ""]

        plan = scenario("safe-plan")
        code_hash = digest(plan / "workspace/order.py")
        config = plan / "budget.json"
        initial_limits = json.loads((PROJECT / "budget.json").read_text())
        config.write_text(json.dumps(initial_limits))
        run_id = start(plan, "--mode", "safe", "--plan", "--budget", str(config))
        config.write_text(json.dumps(dict.fromkeys(initial_limits, 0)))
        assert state(plan, run_id)["status"] == "WAITING_PLAN"
        assert digest(plan / "workspace/order.py") == code_hash
        invoke(plan, "approve-plan", run_id)
        assert state(plan, run_id)["status"] == "WAITING_ACTION"
        assert digest(plan / "workspace/order.py") == code_hash
        invoke(plan, "approve", run_id, "--reason", "Approve write")
        result = state(plan, run_id)
        assert result["status"] == "WAITING_ACTION"
        assert result["batch"][result["batchIndex"]]["name"] == "Bash"
        invoke(plan, "approve", run_id, "--reason", "Approve Bash")
        assert state(plan, run_id)["status"] == "COMPLETED"
        assert state(plan, run_id)["budget"]["limits"] == initial_limits
        lines += ["SAFE + PLAN: file unchanged before both plan and write approval; separate Bash approval; COMPLETED.", ""]

        crash = scenario("crash")
        run_id = start(crash, "--mode", "auto", "--crash-after", "write_file", expected=86)
        changed = digest(crash / "workspace/order.py")
        invoke(crash, "resume", run_id)
        assert state(crash, run_id)["status"] == "NEEDS_RECOVERY"
        assert digest(crash / "workspace/order.py") == changed
        invoke(crash, "recover", run_id, "--choice", "retry", "--reason", "Inspected file; permit replacement")
        result = state(crash, run_id)
        assert result["status"] == "COMPLETED"
        write = next(v for v in result["calls"].values() if v["call"]["name"] == "write_file")
        assert write["attempts"] == 2
        lines += ["CRASH: exit_code=86; new process stopped at NEEDS_RECOVERY; explicit retry attempt=2; COMPLETED.", ""]

        task_folder = scenario("tasks")
        before_tests = digest(task_folder / "workspace/test_order.py")
        run_id = start(task_folder, "--tasks", "--mode", "auto")
        planned = state(task_folder, run_id)
        assert planned["status"] == "WAITING_PLAN"
        assert len(planned["tasks"]) == 3
        assert planned["budget"]["used"]["sideEffects"] == 0
        invoke(task_folder, "approve-plan", run_id, "--crash-after", "write_file", expected=86)
        crashed = state(task_folder, run_id)
        completed_inspect = next(task for task in crashed["tasks"] if task["id"] == "inspect")
        assert completed_inspect["status"] == "COMPLETED" and completed_inspect["result"]
        assert next(task for task in crashed["tasks"] if task["id"] == "test")["status"] == "PENDING"
        checklist = invoke(task_folder, "tasks", run_id)
        assert "[x] inspect:" in checklist and "[>] modify:" in checklist and "[ ] test:" in checklist
        invoke(task_folder, "resume", run_id)
        assert state(task_folder, run_id)["status"] == "NEEDS_RECOVERY"
        invoke(task_folder, "recover", run_id, "--choice", "retry", "--reason", "Inspected uncertain write")
        final = state(task_folder, run_id)
        assert final["status"] == "COMPLETED"
        assert all(task["status"] == "COMPLETED" and task["result"] for task in final["tasks"])
        assert next(task for task in final["tasks"] if task["id"] == "inspect") == completed_inspect
        reads = [record for record in final["calls"].values() if record.get("taskId") == "inspect" and record["call"]["name"] == "read_file"]
        assert len(reads) == 1 and reads[0]["attempts"] == 1
        assert all(record.get("taskId") for record in final["calls"].values() if record["phase"] == "EXECUTE")
        assert all(final["calls"][call_id].get("taskId") is None for call_id in final["planningCallIds"])
        assert digest(task_folder / "workspace/test_order.py") == before_tests
        assert invoke(task_folder, "tasks", run_id).count("[x]") == 3
        lines += ["TASKS: 3 dependent tasks verified; no effects before plan approval; crash/recovery retained completed task; all execution calls have taskId; tests unchanged.", ""]

        limited = scenario("budget-exhaustion")
        config = limited / "budget.json"
        limits = dict(initial_limits, toolCalls=1)
        config.write_text(json.dumps(limits))
        before_order = digest(limited / "workspace/order.py")
        run_id = start(limited, "--mode", "auto", "--budget", str(config), expected=1)
        result = state(limited, run_id)
        assert result["status"] == "BUDGET_EXHAUSTED"
        assert result["calls"] == {}
        assert result["budget"]["used"]["modelRequests"] == 1
        assert result["budget"]["used"]["toolCalls"] == 0
        assert digest(limited / "workspace/order.py") == before_order
        invoke(limited, "resume", run_id, expected=1)
        assert state(limited, run_id)["budget"] == result["budget"]
        clean_environment = {key: value for key, value in environment.items() if not key.startswith("OPENAI_")}
        output = subprocess.run([str(APP), "budget", run_id, "--state-dir", str(limited / "runs")],
                                cwd=PROJECT, text=True, capture_output=True, env=clean_environment, timeout=30)
        assert output.returncode == 0 and "[budget]" in output.stdout
        lines += ["BUDGET: oversized batch executed no tools; resume retained counters; budget command needs no model configuration.", ""]

    lines += ["CLI verification: 5 scenarios passed.", "External LLM request: not performed; the CLI API client called a local scripted HTTP server."]
    report = PROJECT / "build/reports/cli-verification.md"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines) + "\n")
    print("CLI verification: 5 scenarios passed.")
    print("Report:", report)


if __name__ == "__main__":
    main()
