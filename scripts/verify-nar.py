#!/usr/bin/env python3
"""Verify registration, extension docs, bundled licenses and base bytecode."""
import argparse
import io
from pathlib import Path
import struct
import zipfile

PROCESSOR = "io.github.paf91.nifi.opcua.SubscribeOpcUa"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(path, maximum):
    classes = 0
    processors = 0
    with zipfile.ZipFile(path) as nar:
        require(PROCESSOR in nar.read("META-INF/docs/extension-manifest.xml").decode(),
                "Missing processor documentation")
        require("META-INF/LICENSE" in nar.namelist(), "NAR project license is missing")
        jars = [name for name in nar.namelist() if name.endswith(".jar")]
        forbidden = ("/nifi-api-", "/sdk-server-", "/milo-sdk-server-", "/slf4j-api-",
                     "/slf4j-simple-", "/junit-", "/mockito-")
        require(not any(part in name for name in jars for part in forbidden),
                "Runtime contains provided/test-only dependencies")
        for name in jars:
            with zipfile.ZipFile(io.BytesIO(nar.read(name))) as jar:
                if "/nifi-opcua-subscriber-processors-" in name:
                    processors += 1
                    require(PROCESSOR in jar.read("META-INF/services/org.apache.nifi.processor.Processor").decode(),
                            "Processor service registration is missing")
                    require("META-INF/LICENSE" in jar.namelist(), "Processor JAR license is missing")
                for entry in jar.namelist():
                    if not entry.endswith(".class") or entry.startswith("META-INF/versions/") or entry.endswith("module-info.class"):
                        continue
                    data = jar.read(entry)
                    require(len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe", "Invalid class file: " + entry)
                    major = struct.unpack(">H", data[6:8])[0]
                    require(major <= maximum, f"Incompatible bytecode: {name}: {entry}: {major} > {maximum}")
                    classes += 1
        require(processors == 1, "Expected exactly one processor JAR")
        require(classes > 0, "NAR contains no base classes")
    return len(jars), classes


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("nar", type=Path)
    parser.add_argument("maximum", type=int, choices=[52, 65])
    args = parser.parse_args()
    jars, classes = verify(args.nar, args.maximum)
    print(f"{args.nar.name}: validated {jars} bundled JARs and {classes} classes (maximum class version {args.maximum})")
