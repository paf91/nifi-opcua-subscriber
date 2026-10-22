import hashlib
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from test_tools import load


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.release = load('release')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def bundle(self, version='1.0.2'):
        output = self.root / ('release-' + version)
        self.release.prepare_output(output)
        (output / 'source.txt').write_text('synthetic release ' + version)
        return output

    def test_patch_versions_produce_distinct_archives_and_correct_manifests(self):
        for version in ('1.0.1', '1.0.2'):
            output = self.bundle(version)
            archive = self.release.finish_bundle(output)
            self.assertEqual('release-' + version + '.tar.gz', archive.name)
            self.assertFalse((output / 'INCOMPLETE').exists())
            digest = archive.with_name(archive.name + '.sha256')
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name + '\n',
                             digest.read_text())
            with tarfile.open(archive) as tar:
                names = tar.getnames()
                self.assertNotIn('nifi-opcua-subscriber-release/INCOMPLETE', names)
                sums = tar.extractfile('nifi-opcua-subscriber-release/SHA256SUMS').read().decode()
                self.assertNotIn('INCOMPLETE', sums)
                self.assertIn('  source.txt\n', sums)
                self.assertEqual(hashlib.sha256((output / 'source.txt').read_bytes()).hexdigest(),
                                 sums.split()[0])
        self.assertTrue((self.root / 'release-1.0.1.tar.gz').exists())
        self.assertTrue((self.root / 'release-1.0.2.tar.gz').exists())

    def test_existing_archive_or_checksum_is_rejected_before_creating_output(self):
        for suffix in ('.tar.gz', '.tar.gz.sha256'):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory(dir=self.root) as directory:
                output = Path(directory) / 'release-1.0.2'
                existing = output.with_name(output.name + suffix)
                existing.write_text('keep this existing release')
                with self.assertRaises(FileExistsError):
                    self.release.prepare_output(output)
                self.assertFalse(output.exists())
                self.assertEqual('keep this existing release', existing.read_text())

    def test_packaging_failure_keeps_incomplete_and_leaves_no_archive(self):
        output = self.bundle()
        with mock.patch.object(self.release.tarfile.TarFile, 'add', side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'):
                self.release.finish_bundle(output)
        self.assertTrue((output / 'INCOMPLETE').exists())
        for path in self.release.archive_paths(output):
            self.assertFalse(path.exists())

    def test_checksum_publication_failure_removes_only_its_archive(self):
        output = self.bundle()
        link = self.release.os.link
        calls = 0

        def publish(source, destination):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError('checksum publication failed')
            link(source, destination)

        with mock.patch.object(self.release.os, 'link', side_effect=publish):
            with self.assertRaisesRegex(OSError, 'checksum publication failed'):
                self.release.finish_bundle(output)
        self.assertTrue((output / 'INCOMPLETE').exists())
        for path in self.release.archive_paths(output):
            self.assertFalse(path.exists())

    def test_collision_after_preflight_does_not_overwrite_an_existing_archive(self):
        output = self.bundle()
        archive, _ = self.release.archive_paths(output)
        link = self.release.os.link

        def racing_publish(source, destination):
            archive.write_text('another build won')
            link(source, destination)

        with mock.patch.object(self.release.os, 'link', side_effect=racing_publish):
            with self.assertRaises(FileExistsError):
                self.release.finish_bundle(output)
        self.assertEqual('another build won', archive.read_text())
        self.assertTrue((output / 'INCOMPLETE').exists())


if __name__ == '__main__':
    unittest.main()
