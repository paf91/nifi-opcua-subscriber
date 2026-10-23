# Output and delivery guarantees

Each success FlowFile contains a JSON array. Each notification includes `tag`, `nodeId`, `value`, `valueType`, `statusCode` (hex), `quality`, `sourceTimestamp`, and `receivedTimestamp`. For URI-configured tags only, `configuredNodeId` and `namespaceUri` are also included; `nodeId` remains the numeric address used in that session. An unavailable source timestamp is null. Records omit endpoint, schema version, and server timestamp. FlowFile attributes include `mime.type=application/json`, `record.count`, endpoint and security settings. Provenance records the source endpoint.

Booleans, ordinary numbers, strings and arrays retain their JSON types. Multidimensional arrays become nested JSON arrays on both Milo versions; the modern adapter streams the flat matrix without allocating nested arrays. Int64/UInt64 are strings to avoid precision loss; ByteStrings use Base64. Bad/Uncertain values are emitted with quality information so downstream flow logic can decide how to handle them. No coercion is applied based on the document's `STRING` column.

Starting in **1.0.3**, an opaque `ExtensionObject` uses one of the following representations. Its body is streamed through the record byte limit without automatic decoding or display-string expansion. XML and JSON bodies are quoted strings, not parsed or embedded as executable/raw JSON. Null bodies remain JSON null.

| Body | `value` representation |
|---|---|
| OPC UA Binary | `{"encoding":"opcua-binary","encodingId":"ns=2;i=999","body":"AQID"}` (Base64 body) |
| OPC UA XML | `{"encoding":"opcua-xml","encodingId":"ns=2;i=999","body":"<custom/>"}` |
| OPC UA JSON (Milo 1) | `{"encoding":"opcua-json","typeId":"ns=2;i=999","body":"{\"custom\":1}"}` |

Other unsupported Java/Milo objects use `{"encoding":"unsupported","type":"fully.qualified.ClassName"}`. Their value is omitted rather than invoking an arbitrary `toString()` that could allocate or block. UUID and StatusCode values retain their bounded `encoding: display`, `type`, and `text` representation. Consumers of the previous ExtensionObject/unsupported `text` fallback must migrate to the encoded body or explicitly handle the unsupported marker. This is a representation change, not a decoded schema for proprietary structures.

This is **data-change subscription**, not a periodic full snapshot, event/alarm subscription, historical read, or write client. Normally the server sends initial values and later changes; an unchanged value need not produce repeated FlowFiles.

Notifications wait in a bounded in-memory queue. A batch stays reserved until NiFi's asynchronous commit succeeds; a failed commit releases it for retry. A second trigger cannot claim the same in-flight batch. Count or byte capacity exhaustion drops **newest** notifications and reports a bulletin/counter. Full buffers reject before encoding; partial byte capacity limits the encoder to the remaining space. These drops count as overflow; oversized-record counts apply when the record limit itself is the binding limit. Final admission rechecks both capacities atomically. Server notification-loss callbacks also produce a bulletin/counter. Callbacks never block waiting for NiFi storage.

Discovery uses explicit clients with bounded connect, handshake and GetEndpoints operations. Request Timeout applies per operation, not to the entire multi-step startup. Stop cancels the active attempt, including synchronous monitored-item creation. Each connection owns one transport event-loop thread; cleanup closes that group to abort incomplete handshakes. Orderly disconnect waits at most one second before transport shutdown.

Milo handles transport reconnects. Failed transfers, bad subscription status and watchdog expiry request subscription recreation on a later NiFi trigger, with a retry delay. Recovery is consequently delayed while NiFi scheduling is paused/backpressured. The processor closes sessions and PKI watchers when stopped and does not shut down Milo resources shared by other processors.

Stop first closes ingress, then attempts to commit already buffered notifications within **Stop Drain Timeout (ms)**, default **5000**; zero disables this attempt. The drain uses the normal batch count/byte limits and requires the success relationship to be available and the node to retain ownership. Backpressure, failure, interruption or deadline expiry ends the attempt. A warning reports the remaining count and reason only if records are still uncommitted. A submitted asynchronous commit can finish after that warning; its reservation is never released merely because waiting timed out.

Any remaining records are retained in memory. On Start they are drained before opening a new subscription, even if the server is unavailable or properties have changed. Retained records keep the old endpoint and security policy/mode in FlowFile attributes, and the old endpoint in provenance; new settings and buffer capacity apply after draining. The buffer also retains its original maximum record and FlowFile byte limits until drained. Pending asynchronous commits retain their reservation across Stop/Start. The memory is released as batches commit or when the processor is removed. The buffer stays on its original node during primary failover and can be drained when that node becomes primary again; it is not replicated.

There is **no exactly-once or outage-lossless guarantee**: server queue overflow, unrecoverable republish gaps, crashes, stop/start, and cluster failover can lose values or produce repeats. Buffered, uncommitted notifications survive processor Stop/Start in the same JVM, but are not durable across a NiFi/JVM restart or crash. FlowFiles already committed to NiFi use its normal repository persistence. Changes occurring while the subscription is stopped are not recovered from OPC history. Applications needing historical recovery must add server historization and a separate catch-up design.
Periodic health reports use a separate `diagnostics` relationship and `opcua.type=diagnostics`, with one JSON object per FlowFile. They are disabled by default and automatically terminated unless routed. The success FlowFile byte limit applies to measurement arrays, not these bounded metadata reports. See [diagnostics](diagnostics.md).

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
| nodeId | Numeric namespace NodeId actually used in the originating session |
| configuredNodeId / namespaceUri | Original URI address and resolved URI; present only for URI-configured tags |
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
