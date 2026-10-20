#!/usr/bin/env python3
"""Check local Markdown links, publication paths and selected credential patterns."""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_SUFFIXES = {".xlsx", ".xls", ".docx", ".doc", ".pem", ".key", ".p12", ".pfx", ".der", ".jks"}
PATTERNS = [
    re.compile(rb"(?m)^-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    re.compile(rb"glpat-[A-Za-z0-9_-]{20,}"),
    re.compile(rb"gh[pousr]_[A-Za-z0-9]{30,}"),
    re.compile(rb"AKIA[0-9A-Z]{16}"),
]


def forbidden(name):
    path = Path(name)
    return (path.suffix.lower() in FORBIDDEN_SUFFIXES or path.name == ".env"
            or path.name.startswith(".env.") and path.name != ".env.example"
            or any(part in {".git", ".tools", ".m2", "__pycache__", "target", "dist"} for part in path.parts)
            or name.startswith("demo/runtime/") or name == "examples/tags-inventory.csv")


def files(root):
    if (root / ".git").exists():
        names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard"],
                                        cwd=root, text=True).splitlines()
        return [root / n for n in sorted(set(names)) if (root / n).is_file()]
    return sorted(p for p in root.rglob("*") if p.is_file())


def check(root):
    errors = []
    for path in files(root):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            errors.append(name + ": publication does not allow symlinks")
        if forbidden(name):
            errors.append(name + ": forbidden publication path")
        data = path.read_bytes()
        if any(pattern.search(data) for pattern in PATTERNS):
            errors.append(name + ": possible credential/private key (value redacted)")
        if path.suffix == ".md":
            # Check file targets; fragments are intentionally not validated.
            for target in re.findall(r"\]\(([^)\s]+)(?:\s+[^)]*)?\)", data.decode()):
                if target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                relative = target.split("#", 1)[0]
                if relative and not (path.parent / relative).exists():
                    errors.append(name + ": broken local link: " + relative)
    return errors


def history(root):
    """Inspect all reachable historical blobs without printing their contents."""
    count = 0
    for line in subprocess.check_output(["git", "rev-list", "--objects", "--all"], cwd=root, text=True).splitlines():
        oid, _, name = line.partition(" ")
        if not name:
            continue
        kind = subprocess.check_output(["git", "cat-file", "-t", oid], cwd=root, text=True).strip()
        if kind != "blob":
            continue
        count += 1
        data = subprocess.check_output(["git", "cat-file", "blob", oid], cwd=root)
        if any(pattern.search(data) for pattern in PATTERNS):
            raise RuntimeError("Possible credential in history: " + name + " (value redacted)")
    print(f"Scanned {count} reachable historical blobs for selected credential patterns.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--history", action="store_true")
    args = parser.parse_args()
    errors = check(args.root.resolve())
    if errors:
        raise SystemExit("\n".join(errors))
    if args.history:
        history(args.root.resolve())
    print("Project checks passed (not an exhaustive secret or security audit).")
