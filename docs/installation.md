# Installation, upgrade and rollback

[Configuration reference](configuration.md) · [Troubleshooting](troubleshooting.md)

## Pick the artifact

| Target | Artifact |
|---|---|
| NiFi 1.23.2 / Java 8 | `nifi-opcua-subscriber-1.23.2-1.0.0-SNAPSHOT.nar` |
| NiFi 2.12.0 / Java 21 | `nifi-opcua-subscriber-2.12.0-1.0.0-SNAPSHOT.nar` |

Build from source or obtain a tested CI/release artifact. Check the SHA-256 against the accompanying checksum manifest. A filename containing SNAPSHOT is not a unique release identity: keep the source commit and checksum with every deployment. Do not unpack the NAR or install individual dependency JARs.

## First installation using autoload

If `conf/nifi.properties` already contains, for example:

```properties
nifi.nar.library.directory=/opt/nifi/lib
nifi.nar.library.autoload.directory=/opt/nifi/extensions
nifi.nar.working.directory=/opt/nifi/work/nar/
```

copy the complete NAR into `/opt/nifi/extensions`. Stage it outside the watched directory, then move the completed file into place (use the same filesystem for an atomic rename). NiFi's service account needs read access and directory traversal permission.

Wait for NiFi to load the extension, refresh the browser, and search for **SubscribeOpcUa** in Add Processor. First-time autoload does not require restart. Do not put files into `work/nar`: it is managed by NiFi. Do not keep duplicate copies of this bundle across library directories.

## Installation using a custom library

Create an administrator-owned directory, for example `/opt/nifi/custom-nars`, and add:

```properties
nifi.nar.library.directory.opcua=/opt/nifi/custom-nars
```

Keep the existing `nifi.nar.library.directory` property. During a maintenance window, stop NiFi, place the NAR in the custom directory, and restart using the same service manager/account used for the installation. Relative paths are resolved against NiFi home.

These mechanisms are documented in the [NiFi 1.23.2 administration guide](https://github.com/apache/nifi/blob/rel/nifi-1.23.2/nifi-docs/src/main/asciidoc/administration-guide.adoc#processor-locations).

## Before starting a subscription

- Supply the production endpoint, complete NodeIds, and separate production credentials.
- Provision client certificate/key and server trust material on the NiFi host or container.
- Grant the operator the restricted-component permissions for filesystem read/write.
- Connect `success` to a downstream processor; keep the source stopped until ready.
- Verify initial values, types, timestamps, quality and the absence of unexpected bulletins.
- Set downstream backpressure and monitor drops. A RUNNING state alone does not prove an OPC session is established.

## Upgrade

1. Record the installed NAR checksum, processor properties and flow configuration using your normal secure backup procedure.
2. Stop the source. Pending memory data remains in the running JVM; restarting NiFi will lose it. Drain pending data beforehand if required by your ingestion policy.
3. Stop NiFi; replace this bundle's NAR in its existing configured library location. Keep the previous file outside all scanned library directories.
4. Start NiFi and verify extension loading, configuration validation and new data.
5. Install the identical artifact on every cluster node before using the updated flow.

Replacing a loaded SNAPSHOT NAR via autoload is not a reliable update strategy. Stop/restart for replacements. Do not delete repositories or NiFi's working directories as a routine upgrade step.

## Rollback

Stop the source and NiFi, restore the previous NAR, and start NiFi. Restore compatible flow/properties from the secure backup if the processor contract changed. NiFi repositories and flow backups must remain consistent; do not roll them back independently without the installation's recovery procedure. A NAR rollback does not restore notifications lost from memory.

## Cluster requirements

Deploy the same NAR and certificate/trust paths on all eligible nodes. The source is restricted to the primary node. A new primary creates a fresh subscription; the old node's memory queue is not replicated. Confirm certificate identity and trust requirements with the OPC server administrator when provisioning multiple clients. Complete the [cluster acceptance checks](operations.md#cluster-acceptance) before production failover use.
