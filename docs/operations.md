# Operations

## Startup and shutdown

Scheduling prepares state without opening a session. A connected primary node opens the subscription from its trigger. Therefore a full outbound queue can delay initial connection and recovery. Observe successful data and bulletins, not only the NiFi RUNNING indicator.

Stop cancels startup, fences old callbacks, closes owned transports and trust-store watchers, and retains pending records in memory. Start drains them before opening a new subscription. Request Timeout bounds each OPC operation; it is not a deadline for the entire startup of a large tag list. NiFi/JVM restart loses the memory queue.

A server restart may preserve/transfer a session or require a new subscription. Failed transfer, bad status and watchdog events request recovery. The server can repeat current values when a subscription is recreated.

## Monitoring

Monitor NiFi bulletins/logs and these counters:

- `OPC UA notifications dropped`: local overflow or JSON encoding failure.
- `OPC UA server data loss events`: reported notification/republish gaps.

Counters and error messages are emitted when the processor is triggered; backpressure can delay their visibility. On Stop, pending local drops are logged. Inspect each record's `quality` and `statusCode` as well: service-level success is not proof of good sensor quality.

Watch outbound queue size, source and receipt timestamps, process heap/native memory, certificate expiry and OPC server session limits. Source clocks and NiFi clocks must be synchronized before using timestamp differences as latency measurements.

## Capacity planning

Let R be the incoming notification rate, D the blocked duration and B the typical encoded record size. A rough buffer requirement is `R × D` records, consuming at least `R × D × B` bytes plus Java objects, queued batch references, Milo and transport overhead. This is a sizing estimate, not a throughput claim.

The local capacity counts records, not bytes. Large arrays or structured values can exhaust heap even below the record limit. Server-side item queues and the local buffer solve different problems. Increasing either does not provide historical recovery or durable storage. Measure with representative values, tag counts, change rates and outages before choosing limits.

## Cluster acceptance

Before enabling production failover:

1. Install identical NARs and appropriate certificate/trust material on both nodes.
2. Confirm only the connected primary has an active subscription.
3. Revoke the primary role during normal operation and during connection setup.
4. Confirm the old connection closes, no late callbacks enter a new generation, and the new primary produces fresh values.
5. Repeat with downstream backpressure, server outage and pending commits.
6. Confirm the destination's policy for old-node buffered records, duplicate initial values and missing intervals.

Unit tests model role changes through NiFi mocks. The disposable acceptance harness also exercises a real two-node NiFi 1.23.2 cluster: primary shutdown, election, fresh delivery on the survivor, old-primary rejoin and single-subscription ownership. The [validation record](validation.md) identifies the tested artifacts and measured limits. This does not cover every step above: role revocation during connection setup, asymmetric network partitions, coordinator/ZooKeeper failures, NiFi 2 clustering and production OPC server limits still require deployment-level validation.
