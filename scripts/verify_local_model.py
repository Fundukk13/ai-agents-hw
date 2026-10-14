import argparse
from datetime import datetime
import hashlib
import json
import sqlite3
import os
from pathlib import Path
import signal
import shutil
import subprocess
import time
from urllib.request import urlopen
from zoneinfo import ZoneInfo

PROJECT = Path(__file__).resolve().parents[1]
APP = PROJECT / 'build/install/ai-agents/bin/ai-agents'
ZONE = ZoneInfo('Europe/Moscow')


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', default='gpt-oss:20b')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    if not APP.exists():
        raise SystemExit('First run ./gradlew installDist')
    tags = json.load(urlopen('http://127.0.0.1:11434/api/tags', timeout=10))
    if args.model not in {item['name'] for item in tags['models']}:
        raise SystemExit('Download the model with ollama pull ' + args.model)
    started = datetime.now(ZONE)
    folder = PROJECT / 'build/local-model-checks' / started.strftime('%Y%m%d-%H%M%S-%f')
    folder.mkdir(parents=True)
    workspace = folder / 'workspace'
    shutil.copytree(PROJECT / 'workspace', workspace)
    before_order = sha256(workspace / 'order.py')
    before_tests = sha256(workspace / 'test_order.py')
    original_order = sha256(PROJECT / 'workspace/order.py')
    original_tests = sha256(PROJECT / 'workspace/test_order.py')
    baseline = subprocess.run(['python3', '-B', '-m', 'unittest', '-v'], cwd=workspace, text=True, capture_output=True)
    (folder / 'baseline-tests.log').write_text(baseline.stdout + baseline.stderr)
    environment = dict(os.environ)
    environment.update({
        'OPENAI_BASE_URL': 'http://127.0.0.1:11434/v1/',
        'OPENAI_API_KEY': 'ollama',
        'OPENAI_MODEL': args.model,
        'OPENAI_REASONING_EFFORT': 'low',
    })
    command = [str(APP), 'start', '--mode', 'auto', '--workspace', str(workspace),
               '--policy', str(PROJECT / 'policy.json'), '--state-dir', str(folder / 'runs')]
    print('Model:', args.model, flush=True)
    print('Work directory:', folder, flush=True)
    start_time = time.monotonic()
    samples = []
    timed_out = False
    with (folder / 'agent.log').open('w') as log:
        process = subprocess.Popen(command, cwd=PROJECT, env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        while process.poll() is None:
            if time.monotonic() - start_time > args.timeout:
                timed_out = True
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                break
            try:
                sample = json.load(urlopen('http://127.0.0.1:11434/api/ps', timeout=3))
                samples.append({'checked_at': datetime.now(ZONE).isoformat(), **sample})
            except Exception:
                pass
            time.sleep(2)
    elapsed = time.monotonic() - start_time
    (folder / 'memory-samples.json').write_text(json.dumps(samples, ensure_ascii=False, indent=2) + '\n')
    with sqlite3.connect(folder / 'runs/agent.db') as connection:
        row = connection.execute('SELECT state_json FROM runs').fetchone()
        state = json.loads(row[0]) if row else {}
    calls = []
    for record in state.get('calls', {}).values():
        result = json.loads(record['result']) if record.get('result') else {}
        calls.append({'id': record['call']['id'], 'name': record['call']['name'], 'status': record['status'],
                      'ok': result.get('ok'), 'exit_code': result.get('exit_code'), 'timed_out': result.get('timed_out'),
                      'error': result.get('error')})
    final_tests = subprocess.run(['python3', '-B', '-m', 'unittest', '-v'], cwd=workspace, text=True, capture_output=True)
    (folder / 'final-tests.log').write_text(final_tests.stdout + final_tests.stderr)
    unchanged_tests = sha256(workspace / 'test_order.py') == before_tests
    changed_order = sha256(workspace / 'order.py') != before_order
    original_unchanged = sha256(PROJECT / 'workspace/order.py') == original_order and sha256(PROJECT / 'workspace/test_order.py') == original_tests
    bash_passed = any(call['name'] == 'Bash' and call['ok'] and call['exit_code'] == 0 and not call['timed_out'] for call in calls)
    passed = (process.returncode == 0 and not timed_out and state.get('status') == 'COMPLETED'
              and baseline.returncode != 0 and final_tests.returncode == 0 and bash_passed
              and unchanged_tests and changed_order and original_unchanged)
    allocations = [model for sample in samples for model in sample.get('models', []) if model.get('name') == args.model]
    max_size = max((model.get('size', 0) for model in allocations), default=0)
    max_vram = max((model.get('size_vram', 0) for model in allocations), default=0)
    summary = {
        'model': args.model, 'endpoint': environment['OPENAI_BASE_URL'], 'reasoning_effort': 'low',
        'started_at': started.isoformat(), 'finished_at': datetime.now(ZONE).isoformat(),
        'seconds': round(elapsed, 2), 'cli_exit_code': process.returncode, 'timed_out': timed_out,
        'run_id': state.get('runId'), 'status': state.get('status'), 'error': state.get('error'),
        'baseline_test_exit_code': baseline.returncode,
        'final_test_exit_code': final_tests.returncode, 'bash_passed': bash_passed,
        'order_changed': changed_order, 'test_order_unchanged': unchanged_tests,
        'original_workspace_unchanged': original_unchanged, 'max_ollama_size_bytes': max_size,
        'max_ollama_size_vram_bytes': max_vram, 'calls': calls, 'passed': passed,
    }
    (folder / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    relative = os.path.relpath(folder, PROJECT / 'build/reports')
    lines = ['# Проверка агента на локальной модели', '',
             'Команда: `python3 scripts/verify_local_model.py --model ' + args.model + '`.',
             'Сервис: Ollama, локальное окружение, `http://127.0.0.1:11434/v1/`.',
             'Начало: ' + summary['started_at'] + '; окончание: ' + summary['finished_at'] + '.',
             'run_id: `' + str(summary['run_id']) + '`.', '',
             'Результат: **' + ('PASS' if passed else 'FAIL') + '**.',
             'Время агента: ' + str(summary['seconds']) + ' с.',
             'Baseline unittest exit_code=' + str(baseline.returncode) + '; final unittest exit_code=' + str(final_tests.returncode) + '.',
             'Bash с exit_code=0: ' + str(bash_passed) + '.',
             'order.py изменён: ' + str(changed_order) + '; test_order.py неизменён: ' + str(unchanged_tests) + '.',
             'Исходный workspace проекта неизменён: ' + str(original_unchanged) + '.',
             'Максимальный size по выборке /api/ps: ' + str(max_size) + ' байт.',
             'Максимальный size_vram по выборке /api/ps: ' + str(max_vram) + ' байт.',
             'Это выделенная память, сообщённая Ollama, а не полный расход RAM компьютера.', '',
             '| tool_call_id | Инструмент | Статус | ok | exit_code |',
             '|---|---|---|---|---|']
    lines.extend('| ' + ' | '.join(str(call[k]) for k in ['id', 'name', 'status', 'ok', 'exit_code']) + ' |' for call in calls)
    lines.extend(['', '[Полный результат](' + relative + '/summary.json)',
                  '[Лог агента](' + relative + '/agent.log)', '[Результат unittest](' + relative + '/final-tests.log)',
                  '[Выборка памяти](' + relative + '/memory-samples.json)'])
    report = PROJECT / 'build/reports/local-model-verification.md'
    report.write_text('\n'.join(lines) + '\n')
    print('Real model verification:', 'PASS' if passed else 'FAIL', flush=True)
    print('Seconds:', summary['seconds'], flush=True)
    print('Report:', report, flush=True)
    print('Tool calls:', ', '.join(call['name'] for call in calls), flush=True)
    if not passed:
        print('Error:', summary['error'], flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
