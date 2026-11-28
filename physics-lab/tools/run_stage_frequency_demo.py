"""Окно отдельного опыта частоты стадий; готовые classpath лежат в build/experiments."""
from pathlib import Path
import argparse
import datetime
import json
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('variant', choices=['baseline', 'contacts', 'momentum-capped', 'medium4', 'medium32', 'combined-min', 'combined-min-pierce'])
parser.add_argument('--body', choices=['body', 'medusa'], default='body')
parser.add_argument('--java', help='Java 21; по умолчанию JAVA_HOME или PATH')
args = parser.parse_args()
experiments = root / 'build/experiments'
classpath_file = experiments / args.variant / 'classpath.txt'
if not classpath_file.is_file():
    parser.error('Нет замороженной сборки: ' + str(classpath_file))
java_name = 'java.exe' if os.name == 'nt' else 'java'
java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin' / java_name)
                     if 'JAVA_HOME' in os.environ else shutil.which(java_name))
if not java or not Path(java).is_file():
    parser.error('Укажите --java или JAVA_HOME с JDK 21')
body = root / ('body-export-медуза.txt' if args.body == 'medusa'
               else 'docs/physics/repros/detached-fragments-2026-10-21/body.txt')
if not body.is_file():
    parser.error('Нет экспорта тела: ' + str(body))
extra = [root / 'lwjgl3/build/classes/kotlin/lab', root / 'lwjgl3/build/classes/java/main',
         root / 'lwjgl3/build/classes/kotlin/main']
if not (extra[0] / 'io/github/some_example_name/lwjgl3/demo/RealBodyDemoKt.class').is_file():
    parser.error('Сначала выполнить :lwjgl3:compileLabKotlin')
run = experiments / 'manual' / f'{datetime.datetime.now():%Y%m%d-%H%M%S-%f}-{args.variant}-{args.body}'
run.mkdir(parents=True)
for name in ['player-log.txt', 'player-log-prev.txt', 'drag-log.txt']:
    if (root / name).is_file():
        shutil.copy2(root / name, run / ('before-' + name))
shutil.copy2(body, run / 'body.txt')
classpath = os.pathsep.join(str(p) for p in extra if p.is_dir()) + os.pathsep + classpath_file.read_text(encoding='utf-8').strip()
env = {k: v for k, v in os.environ.items() if not k.startswith(('RB_', 'CT_'))}
with (run / 'stdout.txt').open('wb') as out, (run / 'stderr.txt').open('wb') as err:
    process = subprocess.Popen([java, '-Xmx3g', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8', '-cp', classpath, 'io.github.some_example_name.lwjgl3.demo.RealBodyDemoKt',
        str(run / 'body.txt')], cwd=root, env=env, stdout=out, stderr=err,
        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
(run / 'launch.json').write_text(json.dumps({'pid': process.pid, 'variant': args.variant,
    'body': args.body, 'classpath': classpath}, indent=2), encoding='utf-8')
print('PID', process.pid, 'VARIANT', args.variant, 'LOGS', run)
