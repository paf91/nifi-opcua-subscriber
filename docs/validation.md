# Validation record

Historical Preview 4 publication preparation checked on 2026-10-19 (Europe/Amsterdam).
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

## Preview 4 full-runtime acceptance

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


## Five-feature validation — 21 October 2026

These newer working-tree builds add Verify diagnostics, namespace URI tags, periodic health reports, byte limits and deadbands. Their focused results are separate from the historical load/cluster results above.

| Target | Maven result | Live server cases included | Runtime smoke test |
|---|---|---|---|
| NiFi 1.23.2 / Temurin 8u472 | 236 passed, 1 optional external endpoint test skipped | 41 Milo + 29 open62541 | Passed on Java 8u472 |
| NiFi 2.12.0 / Temurin 21.0.9 build | 231 passed, 1 optional external endpoint test skipped | 41 Milo + 29 open62541 | Passed on Java 21.0.12.1 |

Both profiles ran Maven verification in isolated containers with open62541 1.5.9 built from its checksum-verified source. The independent suite exercises every supported policy/mode/login combination, encrypted username tokens on a None channel, Absolute/Percent deadbands and quality changes, rejected filters, URI movement after restart, and close/callback races. Shared regression tests include retained byte limits across Stop/Start, pending commit failures, missing/duplicate namespace resolution, bounded/redacted diagnostics, and delayed report commits across configuration changes. Modern diagnostics also have a regression test for reordered SDK monitored items.

Disposable real NiFi runtimes loaded the corresponding NARs and checked default diagnostics auto-termination, successful and failed Verify requests through NiFi's REST API, URI-addressed Good measurements, and separate health FlowFiles. These smoke tests used a local open62541 None/None anonymous endpoint. They are not a full security matrix through the NiFi runtime, a cluster test or a prolonged-load run. The interactive preview was left unchanged.

Additional checks passed: 21 Python tests, Java formatting, repository checks, GitHub Actions workflow lint, and NAR dependency/bytecode verification (52 and 65). The revised GitHub workflow builds and enables the independent fixture; no remote GitHub run was initiated during this work.

| Earlier five-feature artifact | SHA-256 |
|---|---|
| `nifi-opcua-subscriber-1.23.2-1.0.0-SNAPSHOT.nar` | `ed2a15c299aced67c14e28be76f4ad41635e40d2a728ef42739b4cb5e676fe94` |
| `nifi-opcua-subscriber-2.12.0-1.0.0-SNAPSHOT.nar` | `ccd2b3639191c556a3f2e3b13ece815e8b6b2e0ccabe46a39cb9ef017546566f` |

Local evidence and source-file hashes are stored in ignored `dist/features/qa-report.json` and `dist/features/source-sha256.json`, alongside build/runtime reports and the two NARs. These builds were captured before committing; their source-file manifest subsequently matched commit `d31815282e5009ea60e811884f390fdbd9d44617`. They are not the Preview 5 release binaries. Test keys are excluded. Delivery remains in-memory; durable recovery is reserved for a separate design.


## Preview 5 release validation

Preview 5 adds the NiFi 1 fallback for unadvertised or stale monitored-item limits. Regression tests cover accepted-chunk preservation, callback routing, cancellation, single-item rejection and failures that must not be retried. Both adapters additionally run independent-server tests for quiet keep-alives and recovery after an 18-second server pause with TCP left open, using numeric and URI-addressed tags.

GitHub CI, GitLab CI and the local release builder now require the independent-server suites to execute without skips. A separate opt-in external endpoint test may still be skipped when no endpoint is configured. GitLab's configuration is linted locally against its API; no runner is currently assigned, so this is not a claim of remote CI execution.

The Preview 5 release bundle records the exact committed source, NAR hashes, JDK/container identities, build and independent-server results, and separate runtime smoke-test evidence. Use that evidence for Preview 5. The older artifact hashes and Preview 4 cluster/load results above do not certify the newer binaries. Delivery remains a bounded in-memory queue; production-server, full-cluster and prolonged-load acceptance must be repeated for the new release where required.


## Version 1.0.1

Version 1.0.1 includes both verified fixes from Preview 5: adaptive NiFi 1 requests for missing/stale monitored-item limits, and required independent-server execution in both CI providers and release builds. It uses explicit `1.0.1` Maven/NAR coordinates. The matching release bundle records the exact source commit, rebuilt NAR hashes, build results and real NiFi smoke checks; historical results above retain their original versions and hashes.

## Unreleased certificate validation modes — 22 October 2026

Local verification used OpenJDK 17.0.20.1 and Maven 3.6.3 with the `nifi1` profile and Java 8 compilation target: 211 Java tests passed, with one optional external endpoint test skipped. These include 30 real certificate/CA-chain checks, property/default validation, Verify settings propagation and client identity rejection in insecure mode. The NiFi 1 NAR packaged successfully, its generated property metadata includes Full/Certificate/None with Full as default, and bytecode validation passed. Python utilities (24 tests), Java formatting and repository checks passed.

Live server tests could not run in this session: the sandbox denied loopback socket creation (`Operation not permitted`). Docker and network access were unavailable, and only Java 17 was installed. The 59-case live validation matrix was added to both profiles' normal test builds, but its connection outcomes remain unverified here. Exact Java 8 and Java 21 runs, the NiFi 2 build, independent open62541 tests and full NiFi runtime acceptance must run before releasing this change. The local Java 17 build is development evidence, not a tested Java 8 release; it does not replace the published 1.0.1 artifacts or inherit their acceptance results.

## Version 1.0.2 preparation — 22 October 2026

This source version combines the certificate-validation modes above with the three confirmed review fixes: NiFi 2 setup waits that exceeded Request Timeout during session recovery, later monitored-item batches submitted after cancellation, and release archives that lost the patch version. Initial NiFi 2 service calls now include session acquisition in their individual deadlines. Timeout and cancellation stop partition processing; normal adaptive batching and established-session recovery retain Milo's handling.

Available checks passed on Java 17 with the NiFi 1 profile targeting Java 8 bytecode: **216 tests passed and one optional external-endpoint test was skipped**. Live suites were explicitly excluded because socket creation is denied in this environment. The offline `clean verify` build produced a development NAR with 31 bundled JARs and 15,707 base classes, all at class-file version 52 or lower. Formatting, project checks and **29 Python tests** passed.

The five new shared request tests cover session-wait timeout, late session completion, cancellation/interrupt cleanup, independent batch deadlines and service-error handling. Five release-packaging tests cover distinct patch versions, checksums/manifests, existing-output rejection, packaging failure, and publication failure/races. Eight additional Milo 1 SDK tests cover partition boundaries, lazy operation-limit reads, late sends, adaptive batching and recovery handling. Two new open62541 outage cases exercise initial setup immediately after disconnection and after eight seconds of reconnect attempts. The Milo 1 and live tests are written but have not been executed here.

**1.0.2 has not been published.** Required before release: run the complete Java 8 and Java 21 builds, including the live Milo certificate matrix and independent open62541 suites, verify both NARs, and run NiFi runtime acceptance against their exact hashes. Java 21 compilation, real Java 8 execution, live connections and Docker acceptance were unavailable in this session. Older release acceptance does not validate these changes.

### Target-runtime build follow-up — 22 October 2026

Docker build containers became available during artifact preparation. The complete suites then ran on Temurin Java 8 and Java 21, with the independent open62541 fixture enabled. The live tests revealed two incorrect expectations, which were corrected: bypassing the certificate validator still leaves mandatory Java session-signature key-usage checks in place, and the modern test server returns `Bad_IdentityTokenInvalid` for an incorrect password. No production security checks were removed.

| Build target | Passed | Skipped | Live coverage included |
|---|---:|---:|---|
| NiFi 1.23.2 / Java 8 | 349 | 1 optional external endpoint | 41 secure-session, 59 certificate-mode and 33 open62541 cases |
| NiFi 2.12.0 / Java 21 | 348 | 1 optional external endpoint | The same live suites, plus two setup-outage regressions and eight SDK setup regressions |

Modern NAR packaging also exposed the documentation plugin's dependency-POM reconstruction without active profiles. The documented modern build now passes `-Dnifi.version=2.12.0`; CI and release commands pass the selected target explicitly. This makes the plugin load the correct NiFi API and preserves enforced documentation generation. Both NARs passed packaging and base-bytecode verification.

These results supersede the earlier Java-only validation limits for build candidates. Full NiFi runtime acceptance against the new hashes remains outstanding: creating its isolated Docker network was denied in this environment. GitLab has no assigned runner, so local build results are not GitLab CI results. Candidate artifacts must retain their source commit, checksums and validation evidence; stable 1.0.2 publication remains pending runtime acceptance.

To repeat the network-free subset on an available JDK, use `mvn -o -B -ntp -Pnifi1 -Dtest='!SecureSubscriptionTest,!ServerCertificateValidationTest,!Open62541Test,!Open62541RecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false verify`. Normal CI uses the complete test suites without these exclusions. Local evidence is retained in ignored `dist/certificate-validation-2026-10-22/`.
