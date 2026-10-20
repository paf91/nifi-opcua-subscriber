#!/usr/bin/env python3
"""Export a checked, clean source snapshot without Git history or ignored files."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist" / "nifi-opcua-subscriber-source.tar.gz")
    args = parser.parse_args()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        raise SystemExit("Commit or otherwise resolve working-tree changes before exporting.")
    spec = importlib.util.spec_from_file_location("project_check", ROOT / "scripts/check-project.py")
    check = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(check)
    errors = check.check(ROOT)
    if errors:
        raise SystemExit("\n".join(errors))
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    data = subprocess.check_output(["git", "archive", "--format=tar.gz", "--prefix=nifi-opcua-subscriber/", commit], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for member in archive.getmembers():
            name = member.name.removeprefix("nifi-opcua-subscriber/") if hasattr(str, "removeprefix") else member.name[len("nifi-opcua-subscriber/"):]
            if member.issym() or member.islnk() or (member.isfile() and check.forbidden(name)):
                raise SystemExit("Forbidden export entry: " + name)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    metadata = {"sourceCommit": commit, "artifact": args.output.name,
                "sha256": hashlib.sha256(data).hexdigest(), "includesGitHistory": False}
    args.output.with_suffix(args.output.suffix + ".json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
