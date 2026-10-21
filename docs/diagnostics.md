# Connection diagnostics and health reports

NiFi **Verify** uses the configured endpoint, security/authentication and tag list to open a temporary session, resolve namespaces and create every monitored item. It then closes the connection. Results show the failure stage and cause, advertised endpoints/token policies, selected endpoint, certificate expiry/SHA-256, namespace mapping and requested/revised publishing, sampling and queue settings. Verification uses normal trust validation and never automatically trusts a discovered certificate.

Verification can consume an extra server session and subscription, even if the processor is running. It does not emit measurements, alter the running connection, or drain its buffer. Success confirms connection and monitored-item creation, not that every sensor currently has Good quality; some servers report read/access problems through subsequent data-change status codes. Inspect actual records as well.

Set **Diagnostics Interval (seconds)** to a positive value to enable periodic health FlowFiles. The default is **0**, disabled. Connect `diagnostics` to your monitoring flow; it is automatically terminated by default so existing flows remain valid. Each report is a JSON object with `mime.type=application/json` and `opcua.type=diagnostics`. Success FlowFiles continue to contain measurement arrays.

| Field | Meaning |
|---|---|
| observedAt | Local UTC report time |
| state | SUBSCRIBED, RECONNECTING, RETRYING or WAITING at the trigger; other lifecycle states are internal snapshots |
| connectionAttempts / successfulConnections | Processor-owned connection attempts and successful subscription setups |
| subscriptionRecreations | Successful setups after the first, including Stop/Start; not a count of every TCP reconnect |
| notificationsDropped | Cumulative local count/byte overflow, oversized records and encoding failures |
| oversizedNotifications / encodingFailures | Subsets of the local drop count |
| serverDataLossEvents | Milo-reported notification/republish loss events, not a count of lost values |
| lastFailure | Most recent connection failure, even if a later attempt succeeded |
| connection | Current attempt's metadata, activity and negotiated settings |
| buffer | Record/byte usage, capacity, peak bytes, reservation state, originating endpoint/security and retained size limits |

Counters last for the processor instance and survive same-JVM Stop/Start; recreating the instance resets them. Connection activity counters/timestamps describe the current connection attempt. Buffer peak usage resets when an empty buffer is replaced. Buffer bytes count serialized notification objects, including reserved records, but exclude FlowFile array punctuation and Java/Milo overhead. The report is captured before its accompanying measurement batch commits, so a reported reservation can already be committed when a downstream consumer reads the report.

The connection section distinguishes:

- `sessionActive`: Milo's session activity, not a fresh round-trip probe.
- `lastKeepAliveReceivedAt`: a subscription keep-alive received locally; quiet subscriptions can still be healthy.
- `lastNotificationReceivedAt`: any data-change callback, including Bad/Uncertain or locally dropped records.
- `lastGoodValueReceivedAt`: a callback with Good status; this does not prove recent sampling at the source.
- `sessionActivations`: activations within this connection, including reactivation of the same session.
- `settingsMayBeStale`: Milo opened a different session after the initial metadata was read. Numeric-only subscriptions can continue through SDK transfer/recreation, so their original namespace/negotiated snapshot is marked stale. Use Verify to inspect a fresh independent snapshot, or establish a new processor connection to replace the periodic snapshot. URI-configured subscriptions request a full rebuild on a different session.

A stable value need not produce repeated notifications. Neither an old source timestamp nor a missing new value alone proves disconnection. Use keep-alives, session state, quality, expected process behavior and elapsed time together. Timestamps measure local receipt unless explicitly named as source timestamps.

Reports are emitted only on a connected primary's NiFi trigger. Stopped processors, standby nodes, or backpressure on either relationship cannot keep emitting reports. Monitor missing reports externally; an old SUBSCRIBED report is not a current liveness guarantee. Retry delays and slow connection operations can also delay emission. A failed NiFi commit retries the report and retains its accompanying data batch.

Metadata is bounded to 50 advertised endpoints, 20 identity-token policies per endpoint, 128 namespace entries and 100 monitored-item details. Counts expose larger collections; `itemDetailsTruncated` marks truncated item detail. Strings are capped at 512 characters, and connection failure text at 4096 characters. Reports contain no sensor values, passwords, or private keys. Configured passwords are redacted before string truncation. Endpoint user information, query strings and fragments are removed. Tags, namespace URIs, endpoint paths and certificate identities remain operational metadata: route reports to an appropriate internal destination.

Certificate details from discovery may describe an untrusted server if connection setup fails. A displayed fingerprint is diagnostic information, not an instruction to trust that certificate. Negotiated settings describe what the server accepted, not a guarantee that source acquisition follows that exact frequency.
