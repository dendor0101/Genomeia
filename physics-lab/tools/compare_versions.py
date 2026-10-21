#!/usr/bin/env python3
"""Последовательный A/B двух замороженных classpath; физика считает в одном потоке."""
from pathlib import Path
import argparse, ctypes, json, os, shutil, statistics, subprocess, sys, tempfile

sys.stdout.reconfigure(encoding='utf-8')
p = argparse.ArgumentParser()
p.add_argument('--baseline', required=True)
p.add_argument('--current', required=True)
p.add_argument('--input', required=True)
p.add_argument('--tag', required=True)
p.add_argument('--mode', choices=['scale', 'replay'], default='scale')
p.add_argument('--size', type=int, default=108)
p.add_argument('--gait', choices=['rest', 'gait', 'slam'], default='rest')
p.add_argument('--jit-body')
p.add_argument('--warm', type=int, default=64)
p.add_argument('--blocks', type=int, default=4)
p.add_argument('--block', type=int, default=8)
p.add_argument('--first', choices=['old', 'new'], default='old')
p.add_argument('--affinity', type=lambda x: int(x, 0), help='Windows: маска логических CPU, например 0x4')
p.add_argument('--output', default='build/reports/isolated-bench')
p.add_argument('--java', help='Путь к java; по умолчанию JAVA_HOME или PATH')
p.add_argument('--jvm-arg', action='append', default=[])
p.add_argument('--new-jvm-arg', action='append', default=[])
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
if a.blocks <= 0 or a.block <= 0 or a.warm < 0:
    p.error('blocks/block должны быть положительными, warm — неотрицательным')
if a.affinity is not None and (sys.platform != 'win32' or a.affinity <= 0):
    p.error('affinity поддерживается на Windows и должна быть положительной')
out = Path(a.output).resolve()
out.mkdir(parents=True, exist_ok=True)
helpers = Path(tempfile.mkdtemp(prefix='isolated-helper-', dir=out))
package = Path('io/github/some_example_name/physics/lab/bench')
target = helpers / package
target.mkdir(parents=True)
for pattern in ['IsolatedBenchWorker*.class', 'PairedReplayWorker*.class']:
    files = list((root / 'physics-lab/build/classes/kotlin/main' / package).glob(pattern))
    assert files, pattern
    for file in files:
        shutil.copy2(file, target / file.name)

java_name = 'java.exe' if sys.platform == 'win32' else 'java'
java = a.java or (str(Path(os.environ['JAVA_HOME']) / 'bin' / java_name) if 'JAVA_HOME' in os.environ else shutil.which(java_name))
if not java or not Path(java).is_file():
    p.error('Не найдена Java: задайте JAVA_HOME или --java')
classpath = {key: str(helpers) + os.pathsep + Path(value).read_text(encoding='utf-8-sig').strip()
             for key, value in [('old', a.baseline), ('new', a.current)]}
processes = {}
logs = {}

def receive(key, prefix):
    proc = processes[key]
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError(f'{key} exited ({proc.poll()}), see {logs[key].name}')
        logs[key].write(line)
        logs[key].flush()
        if line.startswith(prefix):
            return line.rstrip('\r\n')

def start(key):
    cmd = [java, '-Xmx3g', '-Xbatch', *a.jvm_arg, *(a.new_jvm_arg if key == 'new' else []), '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
           '-Dstderr.encoding=UTF-8', '-cp', classpath[key],
           'io.github.some_example_name.physics.lab.bench.IsolatedBenchWorker',
           a.mode, str(Path(a.input).resolve()), str(a.size), a.gait, str(a.warm),
           str(Path(a.jit_body or a.input).resolve())]
    logs[key] = (out / f'{a.tag}-{key}-process.txt').open('w', encoding='utf-8')
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    processes[key] = proc
    if a.affinity is not None:
        setter = ctypes.windll.kernel32.SetProcessAffinityMask
        setter.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        setter.restype = ctypes.c_int
        if not setter(int(proc._handle), a.affinity):
            raise ctypes.WinError()
    print(key, 'pid=', proc.pid, 'affinity=', a.affinity, receive(key, 'ISOLATED_READY'), flush=True)

def run(key, count):
    processes[key].stdin.write(f'RUN {count}\n')
    processes[key].stdin.flush()
    line = receive(key, 'ISOLATED_RESULT').split('\t')
    return {'start': int(line[1]), 'count': int(line[2]), 'ns': int(line[3]),
            'cpu': int(line[4]), 'hidden': line[5], 'hashes': line[6]}

order = [a.first, 'new' if a.first == 'old' else 'old']
measured = []
checked = 0
try:
    for key in order:
        start(key)
    warm_blocks = a.warm if a.mode == 'replay' else (a.warm + a.block - 1) // a.block
    for block in range(warm_blocks + a.blocks):
        warm = block < warm_blocks
        count = 1 if a.mode == 'replay' else min(a.block, a.warm - block * a.block) if warm else a.block
        keys = order if block % 2 == 0 else order[::-1]
        result = {key: run(key, count) for key in keys}
        old = result['old']; new = result['new']
        for field in ['start', 'count', 'hidden', 'hashes']:
            if old[field] != new[field]:
                raise AssertionError(f'Parity mismatch: block={block}, field={field}')
        checked += old['count']
        row = {key: value['ns'] / 1e6 / value['count'] for key, value in result.items()}
        row['old_cpu'] = old['cpu'] / 1e6 / old['count']
        row['new_cpu'] = new['cpu'] / 1e6 / new['count']
        if not warm:
            measured.append(row)
        print(f'BLOCK {block} warm={warm} ticks={old["count"]} old_ms={row["old"]:.4f} '
              f'new_ms={row["new"]:.4f} cpu_ms={row["old_cpu"]:.4f}/{row["new_cpu"]:.4f}', flush=True)
    # Metadata is collected after all timed blocks, while the JVMs are idle.
    for key, proc in processes.items():
        if not Path(java).with_name('jcmd.exe' if sys.platform == 'win32' else 'jcmd').is_file():
            continue
        for command in ['VM.flags', 'Compiler.codelist']:
            with (out / f'{a.tag}-{key}-{command}.txt').open('wb') as destination:
                subprocess.run([str(Path(java).with_name('jcmd.exe' if sys.platform == 'win32' else 'jcmd')), str(proc.pid), command],
                               stdout=destination, stderr=subprocess.STDOUT, check=True)
    summary = {'tag': a.tag, 'mode': a.mode, 'size': a.size, 'affinity': a.affinity,
               'warm': a.warm, 'first': a.first, 'java': str(Path(java).resolve()), 'input': str(Path(a.input).resolve()), 'gait': a.gait, 'jvm_args': a.jvm_arg, 'new_jvm_args': a.new_jvm_arg, 'checked': checked, 'blocks': measured,
               'baseline': str(Path(a.baseline).resolve()), 'current': str(Path(a.current).resolve())}
    summary['old_ms'] = statistics.mean(r['old'] for r in measured)
    summary['new_ms'] = statistics.mean(r['new'] for r in measured)
    summary['speedup'] = summary['old_ms'] / summary['new_ms']
    (out / f'{a.tag}.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print('RESULT', json.dumps({k: summary[k] for k in ['tag', 'old_ms', 'new_ms', 'speedup', 'checked']}), flush=True)
    suffix = ('скрытые состояния также совпали' if a.mode == 'scale' else
              'исходные суммы журнала также проверены; скрытое состояние проверяется отдельно')
    print(f'контрольные суммы: все {checked} сошлись; {suffix}', flush=True)
finally:
    for key, proc in processes.items():
        if proc.poll() is None:
            try:
                proc.stdin.write('QUIT\n'); proc.stdin.flush(); proc.stdin.close()
                proc.wait(timeout=60)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                proc.terminate(); proc.wait(timeout=10)
        logs[key].close()
