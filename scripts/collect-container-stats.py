#!/usr/bin/env python3
"""Collect Docker memory/CPU/PID samples for selected acceptance containers."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--containers', nargs='+', required=True)
    parser.add_argument('--seconds', type=int, default=2400)
    parser.add_argument('--interval', type=int, default=15)
    args = parser.parse_args()
    if args.seconds <= 0 or args.interval <= 0:
        parser.error('Duration and interval must be positive')
    if ROOT / 'dist' not in args.output.resolve().parents or args.output.exists():
        parser.error('Use a new output file inside ignored dist/')
    # Restrict collection to this project's explicitly labelled test containers.
    for name in args.containers:
        info = json.loads(subprocess.check_output(['docker', 'inspect', name], text=True))[0]
        if not info['Config'].get('Labels', {}).get('opcua.acceptance'):
            parser.error('Container is not labelled as an OPC UA acceptance container: ' + name)
    report = {'startedAt': datetime.now(timezone.utc).isoformat(), 'containers': args.containers,
              'note': 'Docker cgroup accounting, not Java heap or proof of leak freedom.', 'samples': []}
    end = time.monotonic() + args.seconds
    args.output.parent.mkdir(parents=True, exist_ok=True)
    while time.monotonic() < end:
        running = set(subprocess.check_output(['docker', 'ps', '--format', '{{.Names}}'], text=True).splitlines())
        names = [n for n in args.containers if n in running]
        if not names:
            break
        result = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *names], text=True, capture_output=True)
        sample = {'at': time.time(), 'containers': [json.loads(line) for line in result.stdout.splitlines() if line.strip()]}
        if result.returncode:
            sample['collectionError'] = result.stderr.strip()
        report['samples'].append(sample)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        time.sleep(min(args.interval, max(0, end - time.monotonic())))
    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
