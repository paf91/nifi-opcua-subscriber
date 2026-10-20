#!/usr/bin/env python3
"""Initialize local demo state without replacing an existing encryption key."""
import argparse
import os
from pathlib import Path
import secrets


def initialize(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    env = directory / ".env"
    try:
        descriptor = os.open(str(env), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        entries = [line.split("=", 1)[1].strip().strip("\"'")
                   for line in env.read_text().splitlines()
                   if line.startswith("NIFI_DEMO_SENSITIVE_KEY=")]
        if len(entries) != 1 or not entries[0]:
            raise ValueError("Existing .env must contain one nonempty NIFI_DEMO_SENSITIVE_KEY; it was not modified")
    else:
        with os.fdopen(descriptor, "w") as stream:
            stream.write("NIFI_DEMO_SENSITIVE_KEY=" + secrets.token_hex(32) + "\n")
    (directory / "demo/runtime/security").mkdir(parents=True, exist_ok=True)
    return env


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    initialize(args.directory)
    print("Demo state initialized; existing sensitive-properties key preserved.")
