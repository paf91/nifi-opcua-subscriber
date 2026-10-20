# Migrating the private-preview processor identity

The public project uses GitHub owner **paf91**:

| Identity | Public value |
|---|---|
| Project | `nifi-opcua-subscriber` |
| Maven group | `io.github.paf91.nifi` |
| Processor type | `io.github.paf91.nifi.opcua.SubscribeOpcUa` |
| Display name | `SubscribeOpcUa` |

The earlier private preview used Maven group `ai.paf91.nifi` and processor type `ai.paf91.nifi.opcua.SubscribeOpcUa`. NiFi stores the full type and bundle coordinates in its flow configuration. Replacing a NAR does not rename existing processors automatically.

## Existing flows

1. Back up the flow configuration and old NAR. Plan a maintenance window and the expected data gap. Drain pending data before shutdown where possible; restarting NiFi loses the processor's in-memory buffer.
2. Stop the old subscription. Keep its NAR available temporarily so NiFi can resolve the old processor type. Keep the old and new NARs under their distinct filenames during migration. Install the new NAR and restart NiFi.
3. Add a new `SubscribeOpcUa` using group `io.github.paf91.nifi`. Copy the endpoint, tag list, certificate/trust paths, parameter references, scheduling and buffer settings. Reuse sensitive parameter references rather than exporting passwords into examples or source control.
4. Connect the new processor to the intended downstream flow and configure backpressure. Keep the old source stopped; start and validate the new source. Check fresh values, quality, timestamps and bulletins.
5. After old downstream queues are drained, remove the old processor. Once no flow references the old bundle, remove its NAR during a restart.

Use a new connection for the new source; existing queued FlowFiles remain on the old connection until drained. The new processor has a new component ID. Node-local pending memory and custom counters are not migrated. Historical replay, lossless failover and exactly-once delivery are not provided by this rename.

For a fresh installation, use only the new bundle. Do not edit encrypted flow files by performing a blind text replacement. Rollback requires the old NAR and the matching backed-up flow configuration.
