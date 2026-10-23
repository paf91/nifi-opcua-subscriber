#!/usr/bin/env python3
"""Run disposable full-NiFi acceptance tests using synthetic OPC UA data."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import secrets
import re
import signal
import subprocess
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]


def command(*args):
    try:
        return subprocess.check_output(list(args), text=True, stderr=subprocess.STDOUT).strip()
    except subprocess.CalledProcessError as e:
        raise RuntimeError(f'{args[0]} {args[1]} failed: {e.output[-2000:]}') from None


def timestamp(value):
    # Java Instant can emit nanoseconds; Python 3.9/3.10 accepts microseconds.
    value = re.sub(r"(\.\d{6})\d+", r"\1", value)
    return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def validate_fresh_records(records, now=None):
    # Load.Tag values change once per second. A new callback containing an old
    # source value does not demonstrate that this synthetic server recovered.
    now = time.time() if now is None else now
    assert records, 'No records to validate'
    result = {}
    for field in ('sourceTimestamp', 'receivedTimestamp'):
        assert all(isinstance(r.get(field), str) and r[field] for r in records), f'Missing {field}'
        times = [timestamp(r[field]) for r in records]
        assert now - min(times) < 30, f'Stale {field} does not prove recovery'
        assert max(times) <= now + 5, f'Future {field}; check test host clocks'
        result['oldest' + field[0].upper() + field[1:]] = min(times)
        result['newest' + field[0].upper() + field[1:]] = max(times)
    return result


def resource_snapshots(diagnostics, standalone_node):
    keys = ['usedHeap', 'usedHeapBytes', 'totalThreads', 'daemonThreads']
    if diagnostics.get('nodeSnapshots'):
        return {n['address']: {k: n['snapshot'].get(k) for k in keys}
                for n in diagnostics['nodeSnapshots']}
    return {standalone_node: {k: diagnostics['aggregateSnapshot'].get(k) for k in keys}}


class Harness:
    def __init__(self, args):
        self.args = args
        self.name = 'opcua-acceptance-' + secrets.token_hex(4)
        self.out = args.output.resolve()
        if ROOT / 'dist' not in self.out.parents:
            raise ValueError('Acceptance output must be inside ignored dist/')
        self.out.mkdir(parents=True, exist_ok=False)
        self.security = self.out / 'security'
        self.security.mkdir()
        self.containers = []
        self.ports = {}
        self.report = {'startedAt': datetime.now(timezone.utc).isoformat(), 'profile': args.profile,
                       'tags': args.tags, 'soakSeconds': args.seconds, 'checks': [], 'samples': [],
                       'narSha256': hashlib.sha256(args.nar.read_bytes()).hexdigest(),
                       'harnessCommit': command('git', 'rev-parse', 'HEAD'), 'passed': False}

    def save(self):
        (self.out / 'report.json').write_text(json.dumps(self.report, indent=2) + '\n')

    def check(self, name, detail=None):
        self.report['checks'].append({'name': name, 'detail': detail})
        self.save()
        print(name, flush=True)

    def run(self, suffix, image, options=(), entry=()):
        name = self.name + '-' + suffix
        command('docker', 'run', '-d', '--name', name, '--hostname', suffix,
                '--network', self.name, '--network-alias', suffix,
                '--label', 'opcua.acceptance=' + self.name, *options, image, *entry)
        self.containers.append(name)
        return name

    def api(self, node, method, path, body=None):
        req = urllib.request.Request('http://127.0.0.1:' + self.ports[node] + '/nifi-api' + path,
              method=method, data=None if body is None else json.dumps(body).encode(),
              headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=20) as res:
                data = res.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as e:
            raise RuntimeError(f'{method} {path}: {e.code} {e.read().decode()[:1500]}') from None

    def wait(self, description, fn, timeout=180):
        end = time.monotonic() + timeout
        last = None
        while time.monotonic() < end:
            try:
                result = fn()
                if result:
                    return result
            except (OSError, ValueError, RuntimeError) as e:
                last = str(e)
            time.sleep(2)
        raise RuntimeError('Timed out: ' + description + '; ' + str(last))

    def metrics(self):
        return json.loads((self.security / 'metrics.json').read_text())

    def setup(self):
        command('docker', 'network', 'create', '--subnet', self.args.subnet, self.name)
        self.server = self.run('opcua', 'maven:3.9.11-eclipse-temurin-8',
             ['--user', str(__import__('os').getuid()) + ':' + str(__import__('os').getgid()),
              '-e', 'MAVEN_CONFIG=/tmp/maven', '-e', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4',
              '-e', 'DEMO_LOAD_TAGS=' + str(self.args.tags),
              '-v', str(self.args.demo_target.resolve()) + ':/app:ro', '-v', str(self.security) + ':/security'],
             ['java', '-cp', '/app/classes:/app/lib/*', 'io.github.paf91.nifi.opcua.demo.DemoServer', '/security'])
        self.wait('OPC server', lambda: (self.security / 'metrics.json').exists())
        cluster = self.args.profile == 'nifi1'
        if cluster:
            self.run('zk', 'zookeeper:3.9.4', ['-e', 'JVMFLAGS=-Xmx256m'])
        key = secrets.token_urlsafe(32)
        self.nodes = ['node1', 'node2'] if cluster else ['node1']
        self.names = {}
        for node in self.nodes:
            image = 'opcua-acceptance-nifi1:local' if cluster else 'apache/nifi:2.12.0'
            self.names[node] = self.run(node, image,
                ['--memory', '3g', '-e', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4',
                 '-e', 'NIFI_SENSITIVE_PROPS_KEY=' + key, '-e', 'ACCEPTANCE_CLUSTER=' + str(cluster).lower(),
                 '-p', '127.0.0.1::8080', '-v', str(self.security) + ':/security',
                 '-v', str(self.args.nar.resolve()) + ':/opt/nifi/nifi-current/lib/opcua-acceptance.nar:ro',
                 '-v', str(ROOT / 'acceptance/start-nifi.sh') + ':/acceptance/start.sh:ro',
                 '--entrypoint', '/bin/sh'], ['/acceptance/start.sh'])
            self.ports[node] = command('docker', 'port', self.names[node], '8080/tcp').rsplit(':', 1)[1]
        for node in self.nodes:
            self.wait(node + ' REST API', lambda n=node: self.api(n, 'GET', '/flow/processor-types'), 420)
        self.node = 'node1'
        if cluster:
            self.wait('two connected cluster nodes', lambda: len(self.connected()) == 2)
        self.report['runtimes'] = {node: {'imageId': json.loads(command('docker', 'inspect', self.names[node]))[0]['Image'], 'javaVersion': command('docker', 'exec', self.names[node], 'java', '-version')} for node in self.nodes}
        self.check('runtime-started', {'nodes': len(self.nodes)})

    def connected(self):
        return [n for n in self.api(self.node, 'GET', '/controller/cluster')['cluster']['nodes']
                if n['status'] == 'CONNECTED']

    def state(self, processor, state):
        entity = self.api(self.node, 'GET', '/processors/' + processor)
        self.api(self.node, 'PUT', '/processors/' + processor + '/run-status',
                 {'revision': entity['revision'], 'state': state, 'disconnectedNodeAcknowledged': True})

    def flow(self):
        root = self.api(self.node, 'GET', '/flow/process-groups/root')['processGroupFlow']['id']
        group = self.api(self.node, 'POST', '/process-groups/' + root + '/process-groups',
                    {'revision': {'version': 0}, 'component': {'name': 'Synthetic acceptance', 'position': {'x': 0, 'y': 0}}})
        self.gid = group['id']
        types = self.api(self.node, 'GET', '/flow/processor-types')['processorTypes']
        def add(kind, config):
            t = next(t for t in types if t['type'].endswith('.' + kind))
            return self.api(self.node, 'POST', '/process-groups/' + self.gid + '/processors',
                {'revision': {'version': 0}, 'component': {'type': t['type'], 'bundle': t['bundle'],
                 'name': kind, 'position': {'x': 0, 'y': 0}, 'config': config}})['id']
        self.source = add('SubscribeOpcUa', {'schedulingPeriod': '100 ms',
            'properties': {'Endpoint URL': 'opc.tcp://opcua:4840/demo',
                'Client Certificate File': '/security/client.der', 'Client Private Key File': '/security/client-key.der',
                'PKI Directory': '/security/nifi-pki', 'Username': 'demo', 'Password': 'demo-password',
                'Tags': '\n'.join(f'Tag{i} | ns=2;s=Load.Tag{i}' for i in range(self.args.tags)),
                'Default Sampling Interval (ms)': '1000', 'Notification Buffer Capacity': '5000',
                'Max Records Per FlowFile': '1000', 'Request Timeout (ms)': '5000', 'Reconnect Delay (ms)': '1000'}})
        self.sink = add('LogAttribute', {'autoTerminatedRelationships': ['success'],
                                       'properties': {'Log Payload': 'false'}})
        conn = self.api(self.node, 'POST', '/process-groups/' + self.gid + '/connections',
            {'revision': {'version': 0}, 'component': {'name': 'acceptance',
             'source': {'id': self.source, 'groupId': self.gid, 'type': 'PROCESSOR'},
             'destination': {'id': self.sink, 'groupId': self.gid, 'type': 'PROCESSOR'},
             'selectedRelationships': ['success'], 'backPressureObjectThreshold': 10,
             'backPressureDataSizeThreshold': '10 MB'}})
        self.connection = conn['id']
        for processor in [self.source, self.sink]:
            self.wait('valid processor', lambda p=processor: self.api(self.node, 'GET', '/processors/' + p)['component']['validationStatus'] == 'VALID')
        self.state(self.source, 'RUNNING')
        self.wait('queued notifications', lambda: self.queued() > 0)
        self.inspect_content()
        self.state(self.sink, 'RUNNING')
        self.wait('one active subscription', lambda: self.metrics()['subscriptions'] == 1 and self.metrics()['monitoredItems'] == self.args.tags)
        self.check('secure-delivery', self.metrics())

    def queued(self):
        status = self.api(self.node, 'GET', '/flow/connections/' + self.connection + '/status')['connectionStatus']
        return status['aggregateSnapshot']['flowFilesQueued']

    def inspect_content(self):
        base = '/flowfile-queues/' + self.connection
        listing = self.api(self.node, 'POST', base + '/listing-requests')['listingRequest']
        lid = listing['id']
        listing = self.wait('queue listing', lambda: self._listing(base, lid))
        rows = listing['flowFileSummaries']
        assert rows, 'No queued content to inspect'
        row = rows[0]
        query = ('?clusterNodeId=' + row['clusterNodeId']) if row.get('clusterNodeId') else ''
        request = urllib.request.Request('http://127.0.0.1:' + self.ports[self.node] + '/nifi-api' + base + '/flowfiles/' + row['uuid'] + '/content' + query)
        with urllib.request.urlopen(request, timeout=20) as response:
            records = json.load(response)
        assert records and all(r['quality'] == 'Good' and r['nodeId'].startswith('ns=2;s=Load.Tag') for r in records)
        self.api(self.node, 'DELETE', base + '/listing-requests/' + lid)
        freshness = validate_fresh_records(records)
        self.check('json-content-validated', {'sampleRecords': len(records), 'fields': sorted(records[0]), **freshness})

    def _listing(self, base, lid):
        result = self.api(self.node, 'GET', base + '/listing-requests/' + lid)['listingRequest']
        return result if result['finished'] else None

    def snapshot(self, phase):
        status = self.api(self.node, 'GET', '/flow/processors/' + self.source + '/status?nodewise=true')['processorStatus']
        diagnostics = self.api(self.node, 'GET', '/system-diagnostics?nodewise=true')['systemDiagnostics']
        resources = resource_snapshots(diagnostics, self.node)
        sample = {'at': time.time(), 'phase': phase, 'server': self.metrics(), 'resources': resources,
                  'processor': status, 'counters': self.api(self.node, 'GET', '/counters')}
        self.report['samples'].append(sample)
        self.save()
        return sample

    def stopped(self, processor):
        entity = self.api(self.node, 'GET', '/processors/' + processor)
        return (entity['component']['state'] == 'STOPPED'
                and entity['status']['aggregateSnapshot']['activeThreadCount'] == 0)

    def configure_source_period(self, period):
        entity = self.api(self.node, 'GET', '/processors/' + self.source)
        self.api(self.node, 'PUT', '/processors/' + self.source,
                 {'revision': entity['revision'], 'component': {
                     'id': self.source, 'config': {'schedulingPeriod': period,
                         'properties': {'Stop Drain Timeout (ms)': '5000'}}}})

    def queue_count_now(self):
        # A fresh listing avoids the periodic status cache when comparing stop-time delivery.
        base = '/flowfile-queues/' + self.connection
        request = self.api(self.node, 'POST', base + '/listing-requests')['listingRequest']
        lid = request['id']
        try:
            listing = self.wait('fresh stop-test queue listing', lambda: self._listing(base, lid))
            rows = listing['flowFileSummaries']
            assert len(rows) < 100, 'Stop test must not compare truncated listings'
            return len(rows)
        finally:
            self.api(self.node, 'DELETE', base + '/listing-requests/' + lid)

    def stop_warnings(self):
        board = self.api(self.node, 'GET', '/flow/bulletin-board?sourceId=' + self.source)
        result = {}
        for entry in board['bulletinBoard']['bulletins']:
            bulletin = entry['bulletin']
            if (bulletin.get('sourceId') == self.source
                    and 'Stopped with ' in bulletin.get('message', '')
                    and 'uncommitted notifications' in bulletin['message']):
                key = (bulletin.get('nodeAddress'), bulletin['id'])
                result[key] = bulletin['message']
        return result

    def graceful_stop(self):
        self.state(self.source, 'STOPPED')
        self.wait('source stopped before drain test', lambda: self.stopped(self.source), 15)
        self.wait('old subscription closed', lambda: self.metrics()['subscriptions'] == 0)
        self.wait('old output drained', lambda: self.queued() == 0)
        self.state(self.sink, 'STOPPED')
        self.wait('sink stopped before drain test', lambda: self.stopped(self.sink), 15)
        self.configure_source_period('30 sec')
        prior_warnings = self.stop_warnings()
        started = time.monotonic()
        self.state(self.source, 'RUNNING')
        self.wait('subscription ready for buffered stop', lambda: self.metrics()['subscriptions'] == 1)
        self.wait('first trigger returned', lambda: self.api(self.node, 'GET', '/processors/' + self.source)
                  ['status']['aggregateSnapshot']['activeThreadCount'] == 0)
        time.sleep(2.5)  # Receive updates without another scheduled trigger draining the buffer.
        before = self.queue_count_now()
        assert time.monotonic() - started < 20, 'Setup took too long to isolate the final drain'
        stopped_at = time.monotonic()
        self.state(self.source, 'STOPPED')
        self.wait('bounded graceful stop', lambda: self.stopped(self.source), 15)
        duration = time.monotonic() - stopped_at
        self.wait('subscription closed before drained stop', lambda: self.metrics()['subscriptions'] == 0)
        after = self.queue_count_now()
        assert after > before, 'Stop did not commit buffered notifications between scheduled triggers'
        warnings = self.stop_warnings()
        assert not set(warnings) - set(prior_warnings), 'Healthy stop emitted an uncommitted-data warning'
        self.check('graceful-stop-drained', {'seconds': duration, 'queuedBefore': before, 'queuedAfter': after})

        self.configure_source_period('100 ms')
        self.state(self.source, 'RUNNING')
        self.wait('backpressure before stop', lambda: self.queued() >= 10)
        time.sleep(2.5)
        prior_warnings = self.stop_warnings()
        stopped_at = time.monotonic()
        self.state(self.source, 'STOPPED')
        self.wait('stop despite backpressure', lambda: self.stopped(self.source), 15)
        duration = time.monotonic() - stopped_at
        self.wait('subscription closed under backpressure', lambda: self.metrics()['subscriptions'] == 0)
        def retained_warning():
            warnings = self.stop_warnings()
            return any('backpressured' in message for key, message in warnings.items() if key not in prior_warnings)
        self.wait('retained-data warning after blocked drain', retained_warning, 15)
        self.check('backpressured-stop-bounded', {'seconds': duration})
        self.state(self.sink, 'RUNNING')
        self.wait('output available after blocked drain', lambda: self.queued() == 0)
        self.state(self.source, 'RUNNING')
        self.wait('subscription restored after retained drain', lambda: self.metrics()['subscriptions'] == 1)
        self.delivery_progress('delivery-after-stop-drain')

    def exercise(self):
        self.graceful_stop()
        self.snapshot('baseline')
        self.state(self.sink, 'STOPPED')
        self.wait('backpressure reached', lambda: self.queued() >= 10)
        time.sleep(10)
        self.snapshot('backpressure')
        self.state(self.sink, 'RUNNING')
        self.wait('queue drained', lambda: self.queued() < 10)
        self.check('backpressure-recovery')
        self.state(self.source, 'STOPPED')
        self.wait('subscription closed after stop', lambda: self.metrics()['subscriptions'] == 0)
        self.state(self.source, 'RUNNING')
        self.wait('subscription restored after start', lambda: self.metrics()['subscriptions'] == 1)
        self.check('stop-start-recovery')
        command('docker', 'stop', '-t', '10', self.server)
        time.sleep(10)
        (self.security / 'metrics.json').unlink(missing_ok=True)
        command('docker', 'start', self.server)
        self.wait('server restart recovery', lambda: self.metrics()['subscriptions'] == 1, 120)
        self.delivery_progress('server-restart-delivery')
        self.check('server-restart-recovery')
        command('docker', 'pause', self.server)
        try:
            time.sleep(15)
        finally:
            command('docker', 'unpause', self.server)
        self.delivery_progress('network-stall-recovery')
        if len(self.nodes) == 2:
            self.failover()
        end = time.monotonic() + self.args.seconds
        while time.monotonic() < end:
            sample = self.snapshot('soak')
            assert sample['server']['subscriptions'] == 1, 'Unexpected subscription count during steady load'
            time.sleep(min(10, max(0, end - time.monotonic())))
        self.delivery_progress('soak-delivery')
        self.check('soak-completed', {'seconds': self.args.seconds})
        self.state(self.source, 'STOPPED')
        self.wait('final subscription cleanup', lambda: self.metrics()['subscriptions'] == 0)
        self.snapshot('stopped')
        self.check('final-stop-cleanup')

    def delivery_progress(self, name):
        self.wait('drain before freshness check', lambda: self.queued() == 0, 120)
        self.state(self.sink, 'STOPPED')
        try:
            self.wait(name, lambda: self.queued() > 0, 150)
            self.inspect_content()
        finally:
            self.state(self.sink, 'RUNNING')
        self.check(name)

    def primary_ownership(self):
        primary = next(n['address'] for n in self.connected() if 'Primary Node' in n.get('roles', []))
        before = self.snapshot('ownership-before')['processor']['nodeSnapshots']
        time.sleep(5)
        after = self.snapshot('ownership-after')['processor']['nodeSnapshots']
        prior = {n['address']: n['statusSnapshot']['flowFilesOut'] for n in before}
        delta = {n['address']: n['statusSnapshot']['flowFilesOut'] - prior[n['address']] for n in after}
        assert delta[primary] > 0, 'Primary did not emit data'
        assert all(v == 0 for k, v in delta.items() if k != primary), 'Non-primary emitted data'
        assert self.metrics()['subscriptions'] == 1, 'More than one subscription'
        self.check('primary-only-delivery', {'primary': primary, 'flowFileDelta': delta})

    def failover(self):
        self.primary_ownership()
        nodes = self.connected()
        primary = next(n for n in nodes if 'Primary Node' in n.get('roles', []))
        old = primary['address']
        survivor = next(n for n in self.nodes if n != old)
        self.snapshot('before-failover')
        command('docker', 'stop', '-t', '30', self.names[old])
        self.node = survivor
        self.wait('primary transferred', lambda: any(n['address'] == survivor and 'Primary Node' in n.get('roles', []) for n in self.connected()), 150)
        self.wait('subscription on new primary', lambda: self.metrics()['subscriptions'] == 1)
        self.delivery_progress('new-primary-delivery')
        self.snapshot('after-failover')
        command('docker', 'start', self.names[old])
        self.ports[old] = command('docker', 'port', self.names[old], '8080/tcp').rsplit(':', 1)[1]
        self.wait('old primary rejoined', lambda: len(self.connected()) == 2, 300)
        self.wait('single subscription after rejoin', lambda: self.metrics()['subscriptions'] == 1)
        self.primary_ownership()
        self.check('primary-failover-and-rejoin', {'oldPrimary': old, 'newPrimary': survivor})

    def cleanup(self):
        for name in reversed(self.containers):
            try:
                suffix = name.rsplit('-', 1)[1]
                (self.out / (suffix + '.log')).write_text(command('docker', 'logs', name))
                if suffix.startswith('node'):
                    subprocess.run(['docker', 'cp', name + ':/opt/nifi/nifi-current/logs/nifi-app.log', str(self.out / (suffix + '-app.log'))], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                command('docker', 'rm', '-f', '-v', name)
            except RuntimeError:
                pass
        subprocess.run(['docker', 'network', 'rm', self.name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=['nifi1', 'nifi2'], required=True)
    parser.add_argument('--demo-target', type=Path, default=ROOT / 'demo/target', help='Compiled demo directory containing classes/ and lib/')
    parser.add_argument('--nar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New directory under ignored dist/')
    parser.add_argument('--subnet', default='172.30.248.0/24', help='Unused Docker subnet; choose a different one for concurrent runs')
    parser.add_argument('--tags', type=int, default=1000)
    parser.add_argument('--seconds', type=int, default=1800)
    args = parser.parse_args()
    if not 1 <= args.tags <= 10000 or args.seconds < 0:
        parser.error('tags must be 1..10000 and seconds must be nonnegative')
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Acceptance run interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    harness = Harness(args)
    try:
        harness.setup()
        harness.flow()
        harness.exercise()
        harness.report['passed'] = True
    except BaseException as e:
        harness.report['error'] = str(e)
        raise
    finally:
        harness.report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        harness.save()
        harness.cleanup()


if __name__ == '__main__':
    main()
