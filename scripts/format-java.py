#!/usr/bin/env python3
"""Apply or check the pinned Google Java Format AOSP style (JDK 21)."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import urllib.request

VERSION = "1.24.0"
SHA256 = "812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    jar = ROOT / ".tools" / ("google-java-format-" + VERSION + ".jar")
    if not jar.exists():
        url = ("https://repo.maven.apache.org/maven2/com/google/googlejavaformat/"
               f"google-java-format/{VERSION}/google-java-format-{VERSION}-all-deps.jar")
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise RuntimeError("Formatter checksum mismatch")
        jar.parent.mkdir(exist_ok=True)
        jar.write_bytes(data)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Cached formatter checksum mismatch")
    sources = sorted(str(p) for folder in ["processors/src", "demo/src"]
                     for p in (ROOT / folder).rglob("*.java"))
    flags = ["--dry-run", "--set-exit-if-changed"] if args.check else ["--replace"]
    subprocess.run(["java", "-jar", str(jar), "--aosp"] + flags + sources, check=True)


if __name__ == "__main__":
    main()
