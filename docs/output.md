# Output and delivery guarantees

Each success FlowFile contains a JSON array. Each notification includes `tag`, `nodeId`, `value`, `valueType`, `statusCode` (hex), `quality`, `sourceTimestamp`, and `receivedTimestamp`. An unavailable source timestamp is null. Records omit endpoint, schema version, and server timestamp. FlowFile attributes include `mime.type=application/json`, `record.count`, endpoint and security settings. Provenance records the source endpoint.

Booleans, ordinary numbers, strings and arrays retain their JSON types. Multidimensional arrays become nested JSON arrays on both Milo versions. Int64/UInt64 are strings to avoid precision loss; ByteStrings use Base64. Proprietary/unsupported structured values are explicitly marked `encoding: display` with their type and textual representation; they are not decoded into a lossless schema. Bad/Uncertain values are emitted with quality information so downstream flow logic can decide how to handle them. No coercion is applied based on the document's `STRING` column.

This is **data-change subscription**, not a periodic full snapshot, event/alarm subscription, historical read, or write client. Normally the server sends initial values and later changes; an unchanged value need not produce repeated FlowFiles.

Notifications wait in a bounded in-memory queue. A batch stays reserved until NiFi's asynchronous commit succeeds; a failed commit releases it for retry. A second trigger cannot claim the same in-flight batch. Full buffers drop **newest** notifications and report a bulletin/counter. Server notification-loss callbacks also produce a bulletin/counter. Callbacks never block waiting for NiFi storage.

Discovery uses explicit clients with bounded connect, handshake and GetEndpoints operations. Request Timeout applies per operation, not to the entire multi-step startup. Stop cancels the active attempt, including synchronous monitored-item creation. Each connection owns one transport event-loop thread; cleanup closes that group to abort incomplete handshakes. Orderly disconnect waits at most one second before transport shutdown.

Milo handles transport reconnects. Failed transfers, bad subscription status and watchdog expiry request subscription recreation on a later NiFi trigger, with a retry delay. Recovery is consequently delayed while NiFi scheduling is paused/backpressured. The processor closes sessions and PKI watchers when stopped and does not shut down Milo resources shared by other processors.

Stop retains already received, uncommitted notifications in memory. On Start they are drained before opening a new subscription, even if the server is unavailable or properties have changed. Retained records keep the old endpoint and security policy/mode in FlowFile attributes, and the old endpoint in provenance; new settings and buffer capacity apply after draining. Pending asynchronous commits retain their reservation across Stop/Start. The memory is released as batches commit or when the processor is removed. The buffer stays on its original node during primary failover and can be drained when that node becomes primary again; it is not replicated.

There is **no exactly-once or outage-lossless guarantee**: server queue overflow, unrecoverable republish gaps, crashes, stop/start, and cluster failover can lose values or produce repeats. Buffered, uncommitted notifications survive processor Stop/Start in the same JVM, but are not durable across a NiFi/JVM restart or crash. FlowFiles already committed to NiFi use its normal repository persistence. Changes occurring while the subscription is stopped are not recovered from OPC history. Applications needing historical recovery must add server historization and a separate catch-up design.
## Example

```json
[
  {
    "tag": "DemoSwitch",
    "nodeId": "ns=2;s=Demo.Boolean",
    "value": true,
    "valueType": "Boolean",
    "statusCode": "0x00000000",
    "quality": "Good",
    "sourceTimestamp": "2026-01-01T12:00:00Z",
    "receivedTimestamp": "2026-01-01T12:00:00.125Z"
  }
]
```

| Field | Meaning |
|---|---|
| tag | Configured alias, or the NodeId when no alias is supplied |
| nodeId | Milo's parseable NodeId representation |
| value | Typed JSON value, null, or documented fallback |
| valueType | Java/Milo value class simple name; null when no value is supplied |
| statusCode | Eight-digit hexadecimal OPC UA status; null if unavailable |
| quality | Good, Uncertain, Bad, or Unknown |
| sourceTimestamp | UTC source time; null if absent or zero |
| receivedTimestamp | UTC time the callback was encoded locally |

`valueType` describes the runtime representation; it is not a stable OPC UA DataType NodeId. Array type names are Java class names such as `Float[]`. Int64/UInt64, non-finite floating-point values and some complex values need downstream handling. Receipt time measures local observation, not event ordering or exactly-once delivery.

Attributes: `mime.type=application/json`, `record.count`, `opcua.endpoint`, `opcua.security.policy`, and `opcua.security.mode`. Security attributes reflect the selected channel settings (for example, `None` / `None` or `Basic256Sha256` / `SignAndEncrypt`), not the username-token encryption policy. Provenance records a RECEIVE event with the endpoint. Old buffered batches retain their original endpoint and channel security attributes after a configuration change.

## Lifecycle summary

| Event | Result |
|---|---|
| Processor Stop → Start, same JVM | Pending notifications drain before a new subscription opens |
| Properties edited while stopped | Old records drain with the old endpoint and security settings, then new settings apply |
| JVM restart, crash, processor removal | Uncommitted memory buffer is lost |
| Backpressure | NiFi stops triggering; callbacks can fill the bounded buffer |
| Full local buffer | Newest notifications are dropped; uncommitted batches are preserved |
| Primary role revoked | Startup is cancelled, connection closed, old callbacks fenced |
| Another node becomes primary | Fresh subscription; old node's buffer is not transferred |
| Server restart | Milo reconnects; failed transfer/watchdog can request resubscription |
| Subscription stopped for a period | Intermediate changes are not recovered from history |

No historical reads, writes, event/alarm subscriptions, automatic node discovery, deduplication or durable local journal are implemented. For consumers that need ordered or lossless data, design explicit timestamp handling, deduplication and server-side history recovery.
