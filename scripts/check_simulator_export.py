#!/usr/bin/env python3
"""Run after simulator compilation; isolate outputs to detect missing/stale exports."""
import csv
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
cp = os.pathsep.join((str(root / 'simulator/classes'), str(root / 'simulator/lib/*')))
with tempfile.TemporaryDirectory(prefix='yat-export-') as tmp:
    for args, names in [
        (['--seed=42'], ['algorithm_comparison_results.csv']),
        (['--seeds=1,2'], ['algorithm_comparison_seed_1.csv', 'algorithm_comparison_seed_2.csv']),
    ]:
        subprocess.run([java, '-Duser.language=de', '-Duser.country=DE', '-cp', cp, 'AlgorithmComparisonExperiment',
                        '--cases=5', '--tasks=20', '--no-viz', *args],
                       cwd=tmp, check=True, stdout=subprocess.DEVNULL)
        for name in names:
            path = Path(tmp) / 'result' / name
            with path.open() as stream:
                rows = list(csv.DictReader(stream))
            assert len(rows) == 10, (name, len(rows))
            assert {r['Algorithm'] for r in rows} == {'WFD', 'CacheAware_v2'}
            assert all(0 <= float(r['LoadBalance']) <= 1 for r in rows)
            assert all(None not in r and len(r) == 9 for r in rows)
            meta = json.loads(path.with_suffix('.csv.meta.json').read_text(encoding='utf-8'))
            assert meta['data_rows'] == len(rows)
            assert meta['columns'] == list(rows[0])
print('PASS: single/multi-seed no-viz CSV exports, row counts and metric bounds')
# Occupy output paths with directories: deterministic failure even when run as root.
for no_viz in (True, False):
    for blocked in ('algorithm_comparison_results.csv', 'algorithm_comparison_results.csv.meta.json'):
        with tempfile.TemporaryDirectory(prefix='yat-export-failure-') as tmp:
            (Path(tmp) / 'result' / blocked).mkdir(parents=True)
            command = [java, '-cp', cp, 'AlgorithmComparisonExperiment',
                       '--cases=5', '--tasks=20', '--seed=42']
            if no_viz:
                command.append('--no-viz')
            run = subprocess.run(command, cwd=tmp, capture_output=True, text=True)
            assert run.returncode != 0, (no_viz, blocked, run.stdout)
            assert 'UncheckedIOException' in run.stderr, run.stderr
print('PASS: CSV/metadata failures propagate in both rendering modes')

with tempfile.TemporaryDirectory(prefix='yat-singleton-') as tmp:
    run = subprocess.run([java, '-cp', cp, 'AlgorithmComparisonExperiment',
                          '--cases=5', '--tasks=20', '--seeds=1', '--no-viz'],
                         cwd=tmp, check=True, capture_output=True, text=True, encoding='utf-8')
    assert run.stdout.count('95%CI不可估计') == 3, run.stdout
    assert '95%CI=±0.000' not in run.stdout
    assert 'Makespan改进显著' not in run.stdout
print('PASS: singleton CLI reports unavailable uncertainty')

# Reject invalid experiments before creating any result files.
for spec in ('1,1', '', '1,', ',1', '1,,2'):
    with tempfile.TemporaryDirectory(prefix='yat-seed-invalid-') as tmp:
        run = subprocess.run([java, '-cp', cp, 'AlgorithmComparisonExperiment',
                              '--cases=5', '--tasks=20', '--no-viz', '--seeds=' + spec],
                             cwd=tmp, capture_output=True, timeout=30)
        assert run.returncode == 2, (spec, run.returncode)
        assert not (Path(tmp) / 'result').exists(), spec
with tempfile.TemporaryDirectory(prefix='yat-seed-yaml-') as tmp:
    config = Path(tmp) / 'duplicate.yaml'
    config.write_text('schema_version: 1\nexperiment:\n  seeds: [7, 7]\n', encoding='utf-8')
    run = subprocess.run([java, '-cp', cp, 'AlgorithmComparisonExperiment',
                          '--config=' + str(config), '--no-viz'],
                         cwd=tmp, capture_output=True, timeout=30)
    assert run.returncode == 2
    assert not (Path(tmp) / 'result').exists()
print('PASS: duplicate/malformed CLI seeds and duplicate YAML seeds rejected before export')

for invalid in ('--cores=0', '--cores=-2', '--cases=0', '--tasks=-1', '--seedz=2'):
    with tempfile.TemporaryDirectory(prefix='yat-cli-invalid-') as tmp:
        run = subprocess.run([java, '-cp', cp, 'AlgorithmComparisonExperiment',
                              '--no-viz', invalid], cwd=tmp, capture_output=True, timeout=30)
        assert run.returncode == 2, (invalid, run.returncode)
        assert not (Path(tmp) / 'result').exists()
print('PASS: invalid dimensions and unknown CLI options fail before experiments')

# Explicit CLI seed switches a YAML multi-seed run; explicit file outranks env.
with tempfile.TemporaryDirectory(prefix='yat-precedence-') as tmp:
    config = Path(tmp) / 'multi.yaml'
    config.write_text('schema_version: 1\nexperiment:\n  seeds: [1, 2]\n', encoding='utf-8')
    env = dict(os.environ, YAT_SIM_CONFIG=str(Path(tmp) / 'missing.yaml'))
    base = [java, '-cp', cp, 'AlgorithmComparisonExperiment', '--cases=5', '--tasks=20', '--no-viz']
    run = subprocess.run(base, cwd=tmp, env=env, capture_output=True, timeout=30)
    assert run.returncode == 2
    assert not (Path(tmp) / 'result').exists()
    subprocess.run(base + ['--config=' + str(config), '--seed=42'], cwd=tmp, env=env,
                   check=True, stdout=subprocess.DEVNULL, timeout=30)
    assert (Path(tmp) / 'result/algorithm_comparison_results.csv').is_file()
    assert not list((Path(tmp) / 'result').glob('algorithm_comparison_seed_*.csv'))
    subprocess.run(base + ['--config=' + str(config), '--seed=42', '--seeds=3,4'],
                   cwd=tmp, env=env, check=True, stdout=subprocess.DEVNULL, timeout=30)
    assert (Path(tmp) / 'result/algorithm_comparison_seed_3.csv').is_file()
    assert (Path(tmp) / 'result/algorithm_comparison_seed_4.csv').is_file()
print('PASS: CLI seed mode and explicit/environment configuration precedence')