#!/usr/bin/env python3
"""Query OSV for public Maven coordinates from actual NAR inventories."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import urllib.request


def collect(paths):
    packages = set()
    unversioned = []
    for path in paths:
        inventory = json.loads(Path(path).read_text())
        for jar in inventory["jars"]:
            coordinates = jar["embeddedMavenCoordinates"] + jar.get("knownFilenameCoordinates", [])
            if not coordinates:
                unversioned.append({"inventory": str(path), "jar": jar["path"]})
            for item in coordinates:
                if not item["version"].endswith("-SNAPSHOT"):
                    packages.add((item["groupId"] + ":" + item["artifactId"], item["version"]))
    return sorted(packages), unversioned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventories", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    packages, unversioned = collect(args.inventories)
    if not packages:
        raise SystemExit("No versioned Maven coordinates to check")
    queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version}
               for name, version in packages]
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    if len(results) != len(queries):
        raise RuntimeError("Incomplete advisory response; not reporting a clean result")
    rows = [{"package": query["package"]["name"], "version": query["version"],
             "advisories": result.get("vulns", [])} for query, result in zip(queries, results)]
    report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "provider": "https://osv.dev/",
              "packages": rows, "jarsWithoutCoordinates": unversioned,
              "scope": "Version matches only; excludes project SNAPSHOT, runtime/JDK/container and unversioned JARs. No reachability analysis."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    findings = sum(bool(row["advisories"]) for row in rows)
    print(f"Checked {len(rows)} package/version pairs; {findings} with advisory matches; {len(unversioned)} JARs without coordinates.")
    if findings or unversioned:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
