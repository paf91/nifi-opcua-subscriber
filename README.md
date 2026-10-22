# NiFi OPC UA Subscriber

An Apache NiFi processor that subscribes to OPC UA value changes and emits JSON arrays.

- Configurable OPC TCP security: **None**, **Sign** or **SignAndEncrypt**, with anonymous or username/password login. Secure defaults remain Basic256Sha256 / SignAndEncrypt.
- Bulk tag lists with aliases, numeric or namespace-URI addresses, and per-tag sampling, queue and deadband settings.
- Separate NARs for NiFi 1 / Java 8 and NiFi 2 / Java 21.
- Primary-node ownership, cancellable connections, bounded buffering and restart tests.
- NiFi Verify connection checks, optional health reports, and record/buffer/FlowFile byte limits.
- A local secured OPC UA server and NiFi UI for trying the processor.

Maintained under the GitHub identity **paf91**. Maven group: `io.github.paf91.nifi`; processor type: `io.github.paf91.nifi.opcua.SubscribeOpcUa`.

This is an independent community project, not an Apache Software Foundation product.

## Compatibility and status

| Build | NiFi target | Java target | Milo | Build |
|---|---|---|---|---|
| Legacy (default) | 1.23.2 | 8 | 0.6.16 | `mvn clean verify` |
| Modern | 2.12.0 | 21 | 1.1.8 | `mvn -Pnifi2 -Dnifi.version=2.12.0 clean verify` |

**Version 1.0.2 is in preparation.** It adds configurable server certificate validation, bounds NiFi 2 monitored-item setup waits, stops later batches after cancellation, and preserves patch versions in release archive names. See the [changelog](CHANGELOG.md) and [validation record](docs/validation.md) for completed checks and outstanding release validation. Each release bundle records results for its exact NAR hashes.

Preview 5 passed both Maven suites and standalone real-NiFi smoke tests, including encrypted authentication and username/password without client certificates. Historical Preview 4 binaries also passed a 1,000-tag, 30-minute load phase and legacy two-node failover. Those older load/cluster results do not certify newer binaries. NiFi 2 clustering, multi-day resource testing and representative production-server acceptance remain outstanding.

The versions above are build targets, not recommendations to deploy old NiFi/JDK releases. Do not install the Java 21 NAR into Java 8. Other NiFi versions are not implied to be supported.

## Build

Requirements: Maven 3.9.11, JDK 8 for the legacy build or JDK 21 for the modern build, and Python 3.9+ for repository utilities.

```sh
mvn -B -ntp clean verify
python3 scripts/verify-nar.py nar/target/nifi-opcua-subscriber-1.23.2-1.0.2.nar 52
```

For NiFi 2, use JDK 21:

```sh
mvn -B -ntp -Pnifi2 -Dnifi.version=2.12.0 clean verify
python3 scripts/verify-nar.py nar/target/nifi-opcua-subscriber-2.12.0-1.0.2.nar 65
```

Always clean when changing profiles. Copy the first artifact elsewhere before building the second; `clean` removes previous output. Dependencies are bundled in the NAR; no separate Milo installation is needed.

## Install and configure

1. Choose the NAR matching your NiFi target and JVM.
2. Install it using [autoload or a custom NAR directory](docs/installation.md).
3. Add **SubscribeOpcUa**, connect **success**, and configure the endpoint, security policy/mode and authentication. Supply certificate/key/trust files for secured channels; an unencrypted endpoint needs no client certificate.
4. Paste complete NodeIds into **Tags**, then start the processor:

```text
DemoSwitch | ns=2;s=Demo.Boolean
Server time | ns=0;i=2258
```

These addresses are for the included demo. Use your server's actual addresses. Leave **Application URI** unset to read it from the client certificate, or use the default application identity when no certificate is needed.

For a username/password endpoint without channel security, select **Security Policy = None**, **Security Mode = None**, and **Authentication = Username/Password**. Set Username, Password and Tags; leave client certificate/key unset. With Full or Certificate validation, PKI is needed if that endpoint encrypts its username token with a server certificate. See the [configuration combinations](docs/configuration.md#security-and-authentication).

**Server Certificate Validation** defaults to Full. Certificate skips only the endpoint hostname/IP match while retaining trust and Application URI checks; None bypasses server certificate validation and is insecure. See the [validation modes](docs/configuration.md#certificates-and-trust). These modes are independent of channel encryption and client authentication.

Example output:

```json
[{"tag":"DemoSwitch","nodeId":"ns=2;s=Demo.Boolean","value":true,"valueType":"Boolean","statusCode":"0x00000000","quality":"Good","sourceTimestamp":"2026-01-01T12:00:00Z","receivedTimestamp":"2026-01-01T12:00:00.125Z"}]
```

## Try it locally

Follow the [demo guide](docs/demo.md) to launch NiFi at **http://localhost:18080/nifi/** with generated test certificates. The subscription starts disabled, so it does not fill a queue until enabled.

## Delivery limits

Notifications first enter a bounded memory buffer. Stop/Start in the same JVM retains pending data, but JVM restart/crash does not. A full buffer drops newest values. Failover can produce gaps or duplicates; buffers are local to each node. This processor does not recover history or promise exactly-once delivery. See the [delivery contract](docs/output.md).

## Documentation

| Guide | Contents |
|---|---|
| [Namespace migration](docs/namespace-migration.md) | Moving existing private-preview flows to the GitHub-aligned processor identity |
| [Installation](docs/installation.md) | Autoload, custom libraries, upgrades, rollback, cluster deployment |
| [Configuration](docs/configuration.md) | Every property, tag formats, certificate/key and trust setup |
| [Output](docs/output.md) | JSON types, timestamps, buffering, backpressure, restart guarantees |
| [Diagnostics](docs/diagnostics.md) | NiFi Verify, health reports, negotiated settings and freshness limits |
| [Operations](docs/operations.md) | Startup, monitoring, sizing, failover acceptance checks |
| [Troubleshooting](docs/troubleshooting.md) | Symptoms, likely causes, diagnostic steps |
| [Local demo](docs/demo.md) | Build, launch, inspect, stop and clean up |
| [Architecture](docs/architecture.md) | Lifecycle, concurrency and module layout |
| [Server interoperability](docs/interoperability.md) | Opt-in tests against open62541 and your target server |
| [Development and releases](docs/development.md) | Tests, CI, formatting, inventory and publication |
| [Runtime acceptance](docs/acceptance.md) | Repeatable runtime, failover and synthetic-load checks |
| [Validation record](docs/validation.md) | Local test evidence and remaining acceptance work |
| [Changelog](CHANGELOG.md) | Changes and migration notes |
| [Contributing](CONTRIBUTING.md) | Reproductions, changes and review expectations |
| [Security](SECURITY.md) | Security scope and private reporting |

## License

Project code is licensed under [Apache-2.0](LICENSE). Bundled dependencies retain their own licenses; see [third-party notices](THIRD-PARTY-NOTICES.md). Apache NiFi and Eclipse Milo are trademarks of their respective owners.
