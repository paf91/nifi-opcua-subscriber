#!/usr/bin/env python3
"""Inventory the actual JARs bundled in a NAR; this is not a vulnerability scan."""
import argparse
import hashlib
import io
import json
import re
from pathlib import Path
import zipfile


def inventory(path):
    rows = []
    with zipfile.ZipFile(path) as nar:
        for name in sorted(n for n in nar.namelist() if n.endswith(".jar")):
            data = nar.read(name)
            coordinates = []
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                for prop in sorted(n for n in jar.namelist()
                                   if n.startswith("META-INF/maven/") and n.endswith("/pom.properties")):
                    values = {}
                    for line in jar.read(prop).decode("utf-8", errors="replace").splitlines():
                        if "=" in line and not line.startswith("#"):
                            key, value = line.split("=", 1)
                            values[key.strip()] = value.strip()
                    if all(k in values for k in ("groupId", "artifactId", "version")):
                        coordinates.append({k: values[k] for k in ("groupId", "artifactId", "version")})
                notices = sorted(n for n in jar.namelist()
                                 if any(term in Path(n).name.upper() for term in ("LICENSE", "NOTICE", "COPYING")))
            filename_coordinates = []
            # These artifacts omit pom.properties. Map only known published artifact names,
            # keeping inferred coordinates separate from embedded metadata.
            groups = {
                "bcprov-jdk18on": "org.bouncycastle",
                "bcpkix-jdk18on": "org.bouncycastle",
                "bcutil-jdk18on": "org.bouncycastle",
                "netty-channel-fsm": "com.digitalpetri.netty",
                "strict-machine": "com.digitalpetri.fsm",
                "jspecify": "org.jspecify",
            }
            if not coordinates:
                for artifact, group in groups.items():
                    match = re.fullmatch(re.escape(artifact) + r"-([0-9][A-Za-z0-9._+-]*)\.jar", Path(name).name)
                    if match:
                        filename_coordinates.append({"groupId": group, "artifactId": artifact, "version": match.group(1)})
            for coordinate in coordinates + filename_coordinates:
                group, artifact, version = (coordinate[k] for k in ("groupId", "artifactId", "version"))
                coordinate["sourceJar"] = (
                    f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/"
                    f"{artifact}/{version}/{artifact}-{version}-sources.jar"
                ) if not version.endswith("-SNAPSHOT") else None
            rows.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(),
                         "embeddedMavenCoordinates": coordinates, "knownFilenameCoordinates": filename_coordinates, "licenseAndNoticePaths": notices})
    return {"artifact": Path(path).name, "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(),
            "jars": rows, "note": "Coordinates may include shaded components; source URLs are candidates, not verified downloads."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("nar", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = json.dumps(inventory(args.nar), indent=2) + "\n"
    if args.output:
        args.output.write_text(output)
    else:
        print(output, end="")
