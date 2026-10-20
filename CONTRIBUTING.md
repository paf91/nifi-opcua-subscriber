# Contributing

Read the [architecture](docs/architecture.md), [delivery contract](docs/output.md), and [development guide](docs/development.md) before changing lifecycle behavior.

For bugs, include NiFi/JDK versions, artifact checksum or source commit, sanitized configuration, expected/actual behavior and a minimal reproduction. Report security issues privately as described in [SECURITY.md](SECURITY.md).

For changes:

1. Keep shared sources compatible with Java 8; version-specific Milo APIs belong in their adapter.
2. Add regression tests for changed behavior, especially cancellation, commit callbacks and primary ownership.
3. Run both clean build profiles and NAR checks; keep their artifacts separate.
4. Format Java with the pinned formatter and validate repository documentation/tools.
5. Update configuration/output/operations documentation and the Unreleased changelog as appropriate.

Use synthetic nodes and generated test certificates. Never commit customer documents, private keys, production credentials or extracted inventories. Explain the concrete problem, changed behavior, tests and remaining limitations in the pull request. Do not claim a cluster/throughput test that was not actually run.

Contributions are accepted under the project's Apache-2.0 license. Please be respectful and discuss substantial behavior changes before making them.
