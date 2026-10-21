# Changelog

## Unreleased

- Rename the project and artifacts to `nifi-opcua-subscriber`; align Maven coordinates and Java packages with GitHub owner `paf91`: `io.github.paf91.nifi`. Existing preview flows require the documented processor-type migration.
- Add disposable full-NiFi runtime, primary-failover and synthetic-load acceptance tooling with hash-linked evidence.
- Add a clean-commit local release builder for both NAR targets, source, dependency reports and checksums.
- Run dependency advisory checks in CI and on a weekly schedule.
- Use generic installation paths throughout the public guide.

### Added

- GitHub Actions builds for both NiFi/JDK targets, artifact checks and dependency inventories.
- English installation, configuration, output, operations, troubleshooting, architecture and contributor guides.
- Apache-2.0 project licensing and third-party notices.
- Publication export/checks, pinned Java formatting and safe repeatable demo initialization.

### Changed

- Public examples use synthetic `DemoSwitch` / `ns=2;s=Demo.Boolean` identifiers.
- Production tag inventories and source-document references are excluded from the public tree.
- Java/XML formatting is normalized for review.
- Jackson is aligned to 2.18.12 and Netty to 4.1.139.Final using BOMs, with Bouncy Castle aligned to 1.86 following dependency-advisory review.

### Fixed

- Subscription failure logs retain the rejected NodeId, OPC UA status and underlying cause, with configured passwords redacted.
- NiFi 1 monitored-item requests respect the server's advertised operation limit below the local batch cap of 100.
- NiFi 2 subscription failure logs unpack Milo's synchronization results to report the failed NodeId and actual service or item status.

### Migration

Existing demo flows are not overwritten by the seeder. After rebuilding the demo server, update their Tags to `ns=2;s=Demo.Boolean`. Production configurations using their own NodeIds are unaffected. Public source snapshots start without private development history.

## Preview 3

- Primary-node connection ownership, cancellation on Stop/revocation and late-callback fencing.
- Explicit bounded discovery and connection-owned transport cleanup.
- Pending data retained across same-JVM Stop/Start and drained with its original endpoint.
- Regression coverage for lifecycle, cancellation, server restart and independent connections.

## Preview 2

- Bulk plaintext/JSON tag lists and certificate-derived Application URI.
- Removed per-record endpoint, schemaVersion and serverTimestamp.
- Demo source disabled by default.

## Preview 1

- Separate legacy and modern NARs, secured subscriptions, JSON output and local NiFi demo.

Preview entries describe development milestones, not a stable 1.0 release. The Maven version remains `1.0.0-SNAPSHOT`.
