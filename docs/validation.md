# Validation record

Publication preparation checked on 2026-10-19 (Europe/Amsterdam).
These are local results, not a GitHub Actions run or production certification.

| Check | Result |
|---|---|
| NiFi 1.23.2 / Temurin Java 8, Maven 3.9.11 | Clean build; 36 tests passed |
| NiFi 2.12.0 / Temurin Java 21, Maven 3.9.11 | Clean build; 36 tests passed |
| Legacy NAR packaging | 30 bundled JARs; 15,662 base classes checked against Java 8 |
| Modern NAR packaging | 22 bundled JARs; 13,646 base classes checked against Java 21 |
| Python repository utilities | 15 tests passed |
| Java formatting | Pinned Google Java Format 1.24.0, AOSP style passed |
| GitHub workflow | actionlint 1.7.7 passed; remote execution pending |
| Demo server | Clean build and isolated startup with Basic256Sha256 / SignAndEncrypt |
| Dependency advisories | OSV: 41 package/version pairs, zero matches, zero JARs without coordinates |
| Repository checks | Local documentation links, forbidden publication files and selected credential patterns passed |

The secure OPC UA integration tests exercise actual encrypted connections with generated identities. Test cases include subscription delivery, authentication/trust rejection and lifecycle cancellation. The two profiles run their own adapter tests; their totals do not imply 72 distinct scenarios.

Bundled dependency patches include Jackson 2.18.12, Netty 4.1.139.Final and Bouncy Castle 1.86. Test/demo certificate builders explicitly set a valid two-letter country code for the newer certificate validation.

The advisory result is a dated version lookup, not a reachability assessment or a guarantee of security. It excludes the NiFi runtime, JDK, containers and this project's SNAPSHOT code. Keep the generated dependency inventories and advisory report with release artifacts; repeat the lookup before a later release.

## Full-runtime acceptance

The renamed NARs passed the [disposable acceptance harness](acceptance.md) on 2026-10-19. The legacy run used a real two-node NiFi 1.23.2 cluster with ZooKeeper 3.9.4 and Temurin 8u472. The modern run used standalone NiFi 2.12.0 with the official image's OpenJDK 21.0.12.1 runtime. Maven builds used Temurin 8 and 21; build and runtime JDKs are recorded separately in the release evidence.

Both connected with Basic256Sha256 / SignAndEncrypt, generated client/server certificates, strict server trust and username/password. Each server updated 1,000 synthetic scalar Double tags once per second. The local buffer held 5,000 records; batches held at most 1,000 records; the downstream backpressure threshold was 10 FlowFiles.

| Scenario | NiFi 1.23.2, two nodes | NiFi 2.12.0, one node |
|---|---|---|
| NAR discovery, configuration and sampled JSON quality | Passed | Passed |
| Backpressure and subsequent delivery recovery | Passed | Passed |
| Same-JVM Stop/Start | Passed | Passed |
| OPC server restart and fresh delivery | Passed | Passed |
| Paused server process and delivery recovery | Passed | Passed |
| Primary-only output, primary shutdown, survivor delivery and old-node rejoin | Passed | Not exercised |
| 1,800-second monotonic-timed steady load | Passed | Passed |
| Fresh delivery after load and zero subscriptions/items/sessions after Stop | Passed | Passed |

Every steady-load sample contained one subscription and 1,000 monitored items. Each run collected 180 JVM samples. The loop duration is measured with `time.monotonic()`; report timestamps use the host wall clock. In this environment the first-to-last sample timestamps span about 28 minutes, so they should not be used to derive elapsed time or throughput. The workload is a nominal generator rate, not an end-to-end throughput benchmark.

During steady load, reported local-drop and server-data-loss counters did not increase. The deliberate fault scenarios were **not lossless**: both runs reported 5,000 local drops during forced backpressure; the legacy run also reported one server data-loss event during recovery. Legacy counters reset when the former primary's JVM restarted. A zero counter after that restart does not erase earlier losses. These tests check recovery and sampled freshness, not delivery of every notification.

## Resource observations

The following are sampled minimum–maximum values during steady load, with memory bounds rounded outward to MiB. Heap and thread counts are per-node NiFi diagnostics, not cluster aggregates. Container memory and PID counts come from Docker stats over the same sample window.

| Runtime/node | Used heap, MiB | JVM threads | Container memory, MiB | Container PIDs |
|---|---:|---:|---:|---:|
| Legacy node 1, primary after failover | 140–375 | 148–156 | 1,021–1,853 | 178–187 |
| Legacy node 2, rejoined standby | 73–329 | 79–83 | 858–1,738 | 115–117 |
| Modern node 1 | 185–476 | 85–89 | 979–1,245 | 100–104 |

Heap usage cycled and container memory fell below its earlier peak during the observation window. These observations did not show sustained resource growth, but do not establish leak freedom. Docker memory includes more than Java heap and is not a native-memory attribution. The test JVMs used a 512 MiB initial heap, 1 GiB maximum heap and four active processors; each NiFi container had a 3 GiB memory limit. This is a local synthetic test, not a production sizing recommendation.

## Artifact identity and evidence

The tested processor type is `io.github.paf91.nifi.opcua.SubscribeOpcUa`. Both artifacts were built from source commit `98c4a563c7dc587addebaa045090a615c9f1e601`; the acceptance harness commit was `3c4ac731c3c2aa7972203f1a12e6b897e694a30e`.

| Artifact | SHA-256 |
|---|---|
| `nifi-opcua-subscriber-1.23.2-1.0.0-SNAPSHOT.nar` | `d113c9ac0d71369d468367a55669ee813f49da3949d6a014913f47fb46c524af` |
| `nifi-opcua-subscriber-2.12.0-1.0.0-SNAPSHOT.nar` | `4bac136a1cab7ebf7b489ec15438e63c68516a61bcbd0ee76f69de0a76d01b5a` |

A later documentation-only source snapshot may rebuild these same hashes using output timestamp `1792372694`. Runtime evidence applies to those exact binary hashes; it must not be silently transferred to different binaries. Release metadata identifies the source commit actually packaged.

The local release bundle retains reviewed `runtime-acceptance/nifi1.json`, `nifi2.json` and `container-resources.json` reports alongside source, NARs, dependency inventories, build tests and checksums. Generated private test keys and runtime security directories are excluded. Reports include image IDs, runtime Java versions, assertions and raw resource/counter samples.

## Remaining acceptance work

- Exercise NiFi 2 clustering, asymmetric partitions, coordinator/ZooKeeper failure and role changes during connection setup.
- Test representative production servers, tag types, rates, certificate policies and prolonged outages.
- Run multi-day resource tests and native-memory profiling before making long-term leak claims.
- Run GitHub Actions after importing the clean source snapshot and review its actual results. GitLab currently has no assigned runner; local results do not establish remote CI success.

The existing interactive preview was not upgraded or started collecting data during these checks; its processor remained disabled with an empty queue. Acceptance used separate disposable containers and generated identities. Neither source nor binaries have been published to GitHub.
