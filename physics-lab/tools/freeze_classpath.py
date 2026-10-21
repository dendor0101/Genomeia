#!/usr/bin/env python3
"""Сохраняет отдельные копии классов и JAR до оптимизации, не перезаписывая эталон."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('classpath')
p.add_argument('destination')
a = p.parse_args()
source = Path(a.classpath).read_text(encoding='utf-8-sig').strip()
entries = [Path(entry).resolve() for entry in source.split(os.pathsep) if entry]
target = Path(a.destination).resolve()
if target.exists():
    p.error('Каталог эталона уже существует; перезапись запрещена')
if not entries or any(not entry.exists() for entry in entries):
    p.error('Classpath пуст или содержит отсутствующий файл/каталог')
target.mkdir(parents=True)
frozen = []
manifest = []
for i, entry in enumerate(entries):
    destination = target / f'{i:03d}-{entry.name}'
    if entry.is_dir():
        shutil.copytree(entry, destination)
    else:
        shutil.copy2(entry, destination)
    frozen.append(str(destination))
    files = sorted(destination.rglob('*')) if destination.is_dir() else [destination]
    for file in files:
        if file.is_file():
            manifest.append({'path': str(file.relative_to(target)),
                             'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
(target / 'classpath.txt').write_text(os.pathsep.join(frozen), encoding='utf-8')
(target / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(target / 'classpath.txt')
