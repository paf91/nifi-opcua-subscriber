# Changelog

## 1.0.2 — Certificate validation and setup fixes (in preparation)

- Add Server Certificate Validation modes Full (default), Certificate (skip hostname/IP only) and None (insecure), for both NiFi adapters and encrypted username tokens. Existing flows retain full validation; None does not require a PKI directory.
- Report the selected validation mode in connection diagnostics and NiFi Verify, and warn when scheduling with reduced validation. Client identity, channel protection and user authentication remain independently configured.
- Add shared cryptographic and live-server regression matrices for certificate trust, hostname, URI, validity, usage, signatures and credential failures.
- Bound NiFi 2 initial monitored-item creation and lazy operation-limit reads by Request Timeout, including session acquisition. Each service call gets its own deadline so a healthy large subscription can take longer overall.
- Stop NiFi 2 monitored-item batching on cancellation or timeout and reject late read/create requests after cancellation. Preserve Milo's adaptive batching and automatic recovery error handling after initial setup.
- Preserve the full patch version in release archive/checksum filenames, reject existing output before building, and retain the INCOMPLETE marker if packaging fails.
- Version the reactor, demo, CI artifact paths and upgrade instructions as `1.0.2`. Exact Java 8/21 builds and live runtime acceptance are still required before publication; see the validation record.

## 1.0.1 — Operation-limit fixes and release versioning (2026-10-21)

- Include the NiFi 1 adaptive monitored-item batching fix and regression tests for missing/stale server limits, cancellation and callback routing.
- Include mandatory independent open62541 integration and recovery tests in GitHub CI, GitLab CI and the release builder.
- Version the reactor, demo and NiFi bundles as `1.0.1`; update CI, image builds and installation commands to the matching artifacts.
- Carry forward Preview 5 connection diagnostics, namespace URI tags, deadbands, byte bounds and configurable security. Delivery remains in memory.
- Existing flows using `1.0.0-SNAPSHOT` can migrate their processor bundle to `1.0.1`; see the installation guide before removing the old NAR.

## Preview 5 — Connection diagnostics and interoperability (2026-10-21)

### Added

- NiFi Verify connection diagnostics and optional health FlowFiles on the `diagnostics` relationship, disabled by default.
- Numeric `ns=` and namespace-URI `nsu=` tag addresses with session-specific resolution and duplicate detection.
- Per-tag Absolute/Percent server deadbands that preserve quality changes and reject unsupported filters.
- Serialized-byte limits for the memory buffer, individual records and success FlowFiles; retained buffers preserve their old limits across configuration changes.
- Configurable OPC TCP security policy/mode and anonymous or username/password authentication on both NiFi targets, including None/None without client certificate files. Existing secure defaults remain unchanged.
- Independent open62541 coverage for security, deadbands, namespace changes, keep-alives, silent-server recovery and unadvertised operation limits. GitHub CI, GitLab CI and the local release builder require these suites to pass without skips.

### Fixed

- NiFi 1 reduces monitored-item request sizes after a service-level `Bad_TooManyOperations`, including absent or stale advertised limits. Accepted chunks are preserved; cancellation, other failures and single-item rejection terminate normally.
- Buffered records preserve their original security policy/mode across stopped configuration changes.
- OpenSSL-exported PEM private keys with leading Bag Attributes are recognized correctly.
- Milo 1 multidimensional values serialize as nested JSON arrays instead of a display-text fallback.
- Subscription failure logs retain the rejected NodeId, OPC UA status and underlying cause, with configured passwords redacted; Milo 1 synchronization failures include item/service details.
- Primary-role notifications use NiFi's current ownership state, so delayed revocation or election events cannot leave a re-elected primary disabled or keep an unowned connection open.
- GitHub CI retains Maven exception logs even when dependency resolution fails before test reports or NARs exist.

### Migration

Existing flows receive the new defaults of 64 MiB buffered JSON, 1 MiB per record and 4 MiB per success FlowFile. Review these limits for large arrays or structures; oversized notifications raise drop counters. Diagnostics reports remain disabled by default. Delivery still uses a bounded in-memory queue; durable recovery across process restarts is deferred.

## Preview 4 — GitHub identity and runtime acceptance (2026-10-19)

- Rename the project and artifacts to `nifi-opcua-subscriber`; align Maven coordinates and Java packages with GitHub owner `paf91`: `io.github.paf91.nifi`. Earlier preview flows require the documented processor-type migration.
- Add disposable full-NiFi runtime, primary-failover and synthetic-load acceptance tooling with hash-linked evidence. Recovery acceptance requires fresh source timestamps for every sampled synthetic value.
- Add a clean-commit local release builder, GitHub Actions builds, NAR verification, dependency inventories and scheduled advisory checks.
- Align Jackson to 2.18.12, Netty to 4.1.139.Final and Bouncy Castle to 1.86 following dependency-advisory review.
- Add Apache-2.0 licensing, third-party notices, English guides, pinned formatting and publication export checks.
- Public examples use synthetic `DemoSwitch` / `ns=2;s=Demo.Boolean` identifiers. Production tag inventories and private development history are excluded from public source snapshots.

Existing demo flows are not overwritten by the seeder. After rebuilding the demo server, update their Tags to `ns=2;s=Demo.Boolean`. Production configurations using their own NodeIds are unaffected.

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

Preview entries describe historical development milestones built as `1.0.0-SNAPSHOT`. The current Maven version is `1.0.2`; validation limits remain documented per release.
