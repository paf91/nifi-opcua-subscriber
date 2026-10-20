#!/usr/bin/env python3
"""Build a local, unpublished release bundle from one clean Git commit."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def manifest(directory):
    paths = sorted(p for p in directory.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
    (directory / 'SHA256SUMS').write_text(''.join(checksum(p) + '  ' + p.relative_to(directory).as_posix() + '\n' for p in paths))


def build_targets(pom):
    root = ET.parse(pom).getroot()
    version = root.findtext('m:version', namespaces=NS)
    result = []
    for profile in root.findall('m:profiles/m:profile', NS):
        name = profile.findtext('m:id', namespaces=NS)
        if name in ('nifi1', 'nifi2'):
            props = profile.find('m:properties', NS)
            nifi = props.findtext('m:nifi.version', namespaces=NS)
            java = props.findtext('m:maven.compiler.release', namespaces=NS)
            result.append({'profile': name, 'java': java, 'nifi': nifi,
                           'bytecode': str(int(java) + 44), 'artifact': f'nifi-opcua-subscriber-{nifi}-{version}.nar'})
    if {t['profile'] for t in result} != {'nifi1', 'nifi2'}:
        raise ValueError('Both build profiles must be declared in the root POM')
    return result


def run(args, cwd, log=None):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True,
                   stdout=log, stderr=subprocess.STDOUT if log else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New directory under dist/; existing output is never overwritten')
    parser.add_argument('--maven-cache', type=Path, default=ROOT / '.m2')
    parser.add_argument('--output-timestamp', type=int, help='Fixed archive timestamp for reproducibility checks; defaults to the source commit time')
    args = parser.parse_args()
    if args.output_timestamp is not None and args.output_timestamp < 0:
        parser.error('Output timestamp must be nonnegative')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip():
        parser.error('Commit or resolve working-tree changes before building a release')
    output = args.output.resolve()
    if ROOT / 'dist' not in output.parents:
        parser.error('Output must be inside ignored dist/')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'INCOMPLETE').write_text('Build in progress or failed; do not distribute.\n')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    epoch = subprocess.check_output(['git', 'show', '-s', '--format=%ct', 'HEAD'], cwd=ROOT, text=True).strip()
    if args.output_timestamp is not None:
        epoch = str(args.output_timestamp)
    args.maven_cache.mkdir(parents=True, exist_ok=True)
    cache = args.maven_cache.resolve()
    run(['python3', ROOT / 'scripts/export-public.py', '--output', output / 'source.tar.gz'], ROOT)
    metadata = {'sourceCommit': commit, 'buildTimestamp': datetime.now(timezone.utc).isoformat(),
                'outputTimestamp': epoch, 'status': 'pre-release', 'targets': [],
                'scope': 'Build, test and package checks. Runtime acceptance is recorded separately by NAR hash.'}
    with tempfile.TemporaryDirectory(prefix='opcua-release-') as directory:
        with tarfile.open(output / 'source.tar.gz') as archive:
            archive.extractall(directory)
        source = Path(directory) / 'nifi-opcua-subscriber'
        run(['python3', source / 'scripts/check-project.py', '--root', source], source)
        run(['python3', '-m', 'unittest', 'discover', '-s', 'tests', '-v'], source)
        targets = build_targets(source / 'pom.xml')
        def docker(java, arguments, log):
            image = 'maven:3.9.11-eclipse-temurin-' + java
            run(['docker', 'run', '--rm', '--user', f'{os.getuid()}:{os.getgid()}',
                 '-e', 'MAVEN_CONFIG=/tmp/maven', '-e', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4',
                 '-v', str(source) + ':/work', '-v', str(cache) + ':/tmp/m2', '-w', '/work', image,
                 *arguments], source, log)
            return json.loads(subprocess.check_output(['docker', 'image', 'inspect', image], text=True))[0]['Id']
        for target in targets:
            dest = output / target['profile']
            dest.mkdir()
            print('Building ' + target['profile'], flush=True)
            with (dest / 'build.log').open('w') as log:
                image_id = docker(target['java'], ['mvn', '-B', '-ntp', '-Dmaven.repo.local=/tmp/m2/repository',
                    '-Dproject.build.outputTimestamp=' + epoch, '-P' + target['profile'], 'clean', 'verify'], log)
            nar = source / 'nar/target' / target['artifact']
            run(['python3', source / 'scripts/verify-nar.py', nar, target['bytecode']], source)
            shutil.copy2(nar, dest / nar.name)
            shutil.copytree(source / 'processors/target/surefire-reports', dest / 'surefire-reports')
            run(['python3', source / 'scripts/nar-inventory.py', dest / nar.name,
                 '--output', dest / 'dependency-inventory.json'], source)
            metadata['targets'].append(dict(target, sha256=checksum(dest / nar.name), buildImageId=image_id))
        with (output / 'demo-build.log').open('w') as log:
            docker('8', ['mvn', '-B', '-ntp', '-Dmaven.repo.local=/tmp/m2/repository',
                         '-f', 'demo/pom.xml', 'clean', 'package'], log)
        # Verify the same exported Java sources with the pinned formatter.
        run(['python3', '-c', "import urllib.request; urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.24.0/google-java-format-1.24.0-all-deps.jar', '.formatter.jar')"], source)
        formatter = source / '.formatter.jar'
        if checksum(formatter) != '812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1':
            raise RuntimeError('Formatter checksum mismatch')
        java_files = [str(p.relative_to(source)) for folder in ['processors/src', 'demo/src'] for p in (source / folder).rglob('*.java')]
        with (output / 'format.log').open('w') as log:
            docker('21', ['java', '-jar', '.formatter.jar', '--aosp', '--dry-run', '--set-exit-if-changed', *java_files], log)
    run(['python3', ROOT / 'scripts/check-advisories.py',
         output / 'nifi1/dependency-inventory.json', output / 'nifi2/dependency-inventory.json',
         '--output', output / 'advisory-report.json'], ROOT)
    report = json.loads((output / 'advisory-report.json').read_text())
    if report['jarsWithoutCoordinates']:
        raise RuntimeError('Unidentified JARs require review before release packaging')
    (output / 'build-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (output / 'INCOMPLETE').unlink()
    manifest(output)
    archive = output.with_suffix('.tar.gz')
    if archive.exists():
        raise RuntimeError('Refusing to overwrite existing archive: ' + str(archive))
    with tarfile.open(archive, 'w:gz') as tar:
        tar.add(output, arcname='nifi-opcua-subscriber-release')
    archive.with_suffix('.gz.sha256').write_text(checksum(archive) + '  ' + archive.name + '\n')
    print('Local release bundle: ' + str(archive))
    print('Nothing was published or deployed.')


if __name__ == '__main__':
    main()
