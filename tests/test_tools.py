import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DemoInitializationTest(unittest.TestCase):
    def test_preserves_existing_key(self):
        with tempfile.TemporaryDirectory() as directory:
            env = load("init-demo").initialize(directory)
            first = env.read_bytes()
            load("init-demo").initialize(directory)
            self.assertEqual(first, env.read_bytes())
            self.assertEqual(0o600, env.stat().st_mode & 0o777)
            self.assertTrue((Path(directory) / "demo/runtime/security").is_dir())

    def test_rejects_invalid_env_without_overwriting(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / ".env"
            env.write_text("OTHER=value\n")
            with self.assertRaises(ValueError):
                load("init-demo").initialize(directory)
            self.assertEqual("OTHER=value\n", env.read_text())


class PublicationChecksTest(unittest.TestCase):
    def test_detects_broken_links_and_forbidden_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text("[missing](missing.md)\n")
            (root / "private.docx").write_bytes(b"not public")
            errors = load("check-project").check(root)
            self.assertEqual(2, len(errors))

    def test_valid_links_and_synthetic_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text("[guide](guide.md#section)\n[web](https://example.com)\n")
            (root / "guide.md").write_text("# Guide\nDemo password: demo-password\n")
            self.assertEqual([], load("check-project").check(root))

    def test_token_is_detected_without_echoing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            token = "glpat-" + "x" * 24
            (root / "data.txt").write_text(token)
            errors = load("check-project").check(root)
            self.assertEqual(1, len(errors))
            self.assertNotIn(token, errors[0])


class InventoryTest(unittest.TestCase):
    def test_lists_actual_jars_with_checksums_and_coordinates(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as stream:
            stream.writestr("META-INF/maven/example/library/pom.properties",
                            "groupId=example\nartifactId=library\nversion=1.2.3\n")
            stream.writestr("META-INF/LICENSE", "example")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.nar"
            with zipfile.ZipFile(path, "w") as stream:
                stream.writestr("META-INF/bundled-dependencies/library-1.2.3.jar", jar.getvalue())
            result = load("nar-inventory").inventory(path)
            self.assertEqual(1, len(result["jars"]))
            row = result["jars"][0]
            self.assertEqual(hashlib.sha256(jar.getvalue()).hexdigest(), row["sha256"])
            self.assertEqual(["META-INF/LICENSE"], row["licenseAndNoticePaths"])
            self.assertEqual("library", row["embeddedMavenCoordinates"][0]["artifactId"])


class AdvisoryCoverageTest(unittest.TestCase):
    def test_inventory_identifies_bouncy_castle_without_maven_metadata(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as stream:
            stream.writestr("META-INF/LICENSE", "example")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.nar"
            with zipfile.ZipFile(path, "w") as stream:
                stream.writestr("META-INF/bundled-dependencies/bcprov-jdk18on-1.86.jar", jar.getvalue())
            row = load("nar-inventory").inventory(path)["jars"][0]
            self.assertEqual([], row["embeddedMavenCoordinates"])
            coordinate = row["knownFilenameCoordinates"][0]
            self.assertEqual("org.bouncycastle", coordinate["groupId"])
            self.assertEqual("1.86", coordinate["version"])

    def test_advisories_include_inferred_coordinates_and_report_unknown_jars(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.json"
            path.write_text(json.dumps({"jars": [
                {"path": "bc.jar", "embeddedMavenCoordinates": [],
                 "knownFilenameCoordinates": [{"groupId": "org.bouncycastle",
                    "artifactId": "bcprov-jdk18on", "version": "1.86"}]},
                {"path": "unknown.jar", "embeddedMavenCoordinates": []},
                {"path": "own.jar", "embeddedMavenCoordinates": [{"groupId": "example",
                    "artifactId": "own", "version": "1.0-SNAPSHOT"}]}
            ]}))
            packages, unknown = load("check-advisories").collect([path])
            self.assertEqual([("org.bouncycastle:bcprov-jdk18on", "1.86")], packages)
            self.assertEqual([{"inventory": str(path), "jar": "unknown.jar"}], unknown)


class NarVerificationTest(unittest.TestCase):
    def build_nar(self, path, major):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as stream:
            stream.writestr("META-INF/services/org.apache.nifi.processor.Processor",
                            "io.github.paf91.nifi.opcua.SubscribeOpcUa")
            stream.writestr("META-INF/LICENSE", "test license")
            stream.writestr("Example.class", b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big"))
        with zipfile.ZipFile(path, "w") as stream:
            stream.writestr("META-INF/docs/extension-manifest.xml", "io.github.paf91.nifi.opcua.SubscribeOpcUa")
            stream.writestr("META-INF/LICENSE", "test license")
            stream.writestr("META-INF/bundled-dependencies/nifi-opcua-subscriber-processors-test.jar", jar.getvalue())

    def test_accepts_target_bytecode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.nar"
            self.build_nar(path, 52)
            self.assertEqual((1, 1), load("verify-nar").verify(path, 52))

    def test_rejects_wrong_jvm_target(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.nar"
            self.build_nar(path, 65)
            with self.assertRaisesRegex(ValueError, "Incompatible bytecode"):
                load("verify-nar").verify(path, 52)


class ReleaseTest(unittest.TestCase):
    def test_target_artifacts_follow_pom_versions(self):
        targets = load("release").build_targets(ROOT / "pom.xml")
        self.assertEqual({"nifi1", "nifi2"}, {t["profile"] for t in targets})
        self.assertEqual({"52", "65"}, {t["bytecode"] for t in targets})
        self.assertTrue(all(t["nifi"] in t["artifact"] for t in targets))

    def test_manifest_covers_nested_files_without_hashing_itself(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "nested").mkdir()
            (root / "nested/artifact.nar").write_bytes(b"artifact")
            (root / "SHA256SUMS").write_text("old manifest")
            load("release").manifest(root)
            self.assertEqual(hashlib.sha256(b"artifact").hexdigest() + "  nested/artifact.nar\n",
                             (root / "SHA256SUMS").read_text())


class AcceptanceTimestampTest(unittest.TestCase):
    def test_java_nanoseconds_and_milliseconds_are_accepted(self):
        parse = load("acceptance").timestamp
        self.assertAlmostEqual(parse("2026-01-01T00:00:00.123456789Z"),
                               parse("2026-01-01T00:00:00.123456Z"))
        self.assertEqual(parse("2026-01-01T00:00:00.123Z"),
                         parse("2026-01-01T00:00:00.123000Z"))


class AcceptanceResourcesTest(unittest.TestCase):
    def test_cluster_samples_use_node_values_not_aggregate(self):
        diagnostics = {"aggregateSnapshot": {"usedHeapBytes": 300}, "nodeSnapshots": [
            {"address": "node1", "snapshot": {"usedHeapBytes": 100, "totalThreads": 10}},
            {"address": "node2", "snapshot": {"usedHeapBytes": 200, "totalThreads": 20}}]}
        result = load("acceptance").resource_snapshots(diagnostics, "node1")
        self.assertEqual(100, result["node1"]["usedHeapBytes"])
        self.assertEqual(200, result["node2"]["usedHeapBytes"])

    def test_standalone_uses_its_only_snapshot(self):
        result = load("acceptance").resource_snapshots({"aggregateSnapshot": {"totalThreads": 8}}, "node1")
        self.assertEqual(8, result["node1"]["totalThreads"])
