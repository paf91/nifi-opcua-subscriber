# Runtime, cluster and load acceptance

The acceptance harness runs disposable Docker containers with generated test identities and synthetic `Load.Tag0` through `Load.Tag999` nodes. It creates its own network, uses random loopback HTTP ports, and removes its containers and network when finished. It does not modify the interactive preview.

Requirements: Docker, Python 3.9+, two unused Docker subnets, and approximately 8 GB of free memory for concurrent runs. The acceptance HTTP configuration has no login and is only for these isolated local containers. It is not a production deployment template. Content archival is disabled in these disposable containers so unrelated host disk usage does not throttle the test repository.

## Prepare

Build a release using [the release script](development.md#automated-local-release). Build the demo server with Java 8:

```sh
mvn -B -ntp -f demo/pom.xml clean package
docker build -f acceptance/Dockerfile.nifi1 -t opcua-acceptance-nifi1:local .
docker pull apache/nifi:2.12.0
docker pull zookeeper:3.9.4
```

## Run

Use the NARs from one release directory. Each output directory must be new:

```sh
python3 scripts/acceptance.py --profile nifi1 \
  --nar dist/release-1.0.2/nifi1/nifi-opcua-subscriber-1.23.2-1.0.2.nar \
  --output dist/acceptance/legacy --subnet 172.30.248.0/24 --tags 1000 --seconds 1800

python3 scripts/acceptance.py --profile nifi2 \
  --nar dist/release-1.0.2/nifi2/nifi-opcua-subscriber-2.12.0-1.0.2.nar \
  --output dist/acceptance/modern --subnet 172.30.249.0/24 --tags 1000 --seconds 1800
```

NiFi 1 runs two nodes with ZooKeeper; NiFi 2 runs one node. Both use secure OPC UA subscriptions. `--seconds` is the steady-load duration after the recovery scenarios, not total runtime. Increase it to 86400 for a 24-hour soak; a 30-minute run does not establish long-term leak freedom.

The harness checks NAR discovery and validation, sampled JSON quality, active subscription and monitored-item counts, backpressure recovery, Stop/Start cleanup, server restart, a stalled server process, and delivery recovery. On the legacy cluster it stops the primary, checks election and delivery on the survivor, restarts the old primary, and checks single-subscription ownership after rejoin.

For these once-per-second synthetic tags, every sampled record must have a source timestamp and receipt timestamp less than 30 seconds old, with no timestamp more than 5 seconds in the future. Recent receipt of an old source value is rejected. This freshness rule is specific to the synthetic acceptance server; production subscription values may legitimately remain unchanged for longer periods.

The simulated network stall pauses the server container; it is not an asymmetric cluster network partition. Graceful primary shutdown is not a simultaneous coordinator/ZooKeeper failure. These scenarios require additional deployment testing.

## Evidence and cleanup

`report.json` contains the exact NAR SHA-256, harness commit, checks, timestamps, per-node JVM heap/thread samples, NiFi status/counters and server subscription metrics. A passing run requires all assertions to complete; failed runs retain an error and `passed: false`. Runtime logs and generated keys remain in the ignored output directory. Do not publish the entire directory: share the sanitized JSON report after review, not the generated security directory.

The host directory must be writable by the container UID used for the demo and readable by NiFi's UID 1000. The Linux development environment uses UID 1000. Adjust permissions deliberately for other hosts.

On normal exit or an ordinary exception the harness removes its containers and their anonymous volumes. If the process is forcibly killed, identify leftovers by the `opcua.acceptance` Docker label and remove only that run's containers/network. Avoid blanket Docker cleanup commands.

For container-wide memory (including more than the Java heap), CPU and PID samples, run the optional collector alongside an active acceptance test. Obtain the exact names with `docker ps --filter label=opcua.acceptance --format '{{.Names}}'`, then pass only the desired NiFi containers:

```sh
python3 scripts/collect-container-stats.py --output dist/acceptance/container-resources.json \
  --containers RUN-node1 RUN-node2 --seconds 2400
```

The collector checks the acceptance label, records Docker cgroup accounting, and finishes when the selected containers stop or the duration expires. It does not alter containers. Native memory attribution and multi-day leak analysis require additional profiling.

Use `--demo-target` to point to a separate compiled demo directory when another test is using the default `demo/target`. Do not rebuild a directory mounted by a running test server.
