# Troubleshooting

Start with `logs/nifi-app.log`, processor bulletins, validation errors and queue/backpressure status. Capture the NAR checksum, NiFi/JDK versions, sanitized endpoint and time of failure. Do not attach private keys, credentials, raw production flows or proprietary tag inventories to public issues.

| Symptom | Check |
|---|---|
| SubscribeOpcUa is missing | Correct library/autoload path, readable complete NAR, duplicate bundles and NAR loading errors; refresh UI |
| UnsupportedClassVersionError | Wrong NAR/JVM combination; modern needs Java 21 |
| Processor is restricted | Administrator authorization for filesystem read/write restricted components |
| Invalid Tags | Complete parseable NodeIds; unique aliases/NodeIds; JSON keys and numeric ranges |
| File not found / access denied | Absolute paths inside the NiFi host/container; service account permissions |
| Certificate URI error | Client certificate must have exactly one absolute URI SAN; leave override unset or match exactly |
| Certificate/key mismatch | Use the matching RSA private key and leaf certificate, leaf first in a PEM chain |
| Bad_CertificateUntrusted | Client trust on the OPC server and server/CA trust in NiFi's PKI directory |
| Hostname, expiry or revocation failure | Advertised server hostname, clock, SAN, validity and required issuer certificates/CRLs |
| Bad_UserAccessDenied / identity rejection | Username/password and access rights on the server; certificate trust alone is not user authorization |
| No suitable endpoint | Server must offer Basic256Sha256, SignAndEncrypt and a username token policy |
| Connection to an unexpected hostname | OPC discovery may advertise another host; fix DNS/network/server configuration |
| RUNNING but no data | Bulletins, endpoint reachability, primary role, outbound backpressure and node permissions |
| Only initial values | Subscription emits changes, not periodic snapshots of unchanged values |
| Bad/Uncertain values | Inspect statusCode and the OPC server's node quality; those values are emitted deliberately |
| Growing queue | Downstream flow stopped or slower than source; stop source when not needed |
| Drop counter increasing | Local buffer overflow or encoding failure; inspect value sizes, rate and downstream throughput |
| Old values after Start | Pending memory records drain first, retaining old source timestamps and endpoint |
| Unexpected initial values after reconnect | New subscriptions can send current values again; no built-in deduplication |
| Demo cannot start | Missing legacy NAR/demo classes, Docker subnet conflict, UID 1000 permissions, or lost .env key |

To isolate a server issue, begin with one known readable NodeId and verified certificates. Add tags gradually. An invalid monitored item fails the connection attempt; retries will not fix a wrong NodeId. Do not disable certificate validation as a workaround.

For discovery failures, supply the server's advertised discovery URL, including its endpoint path. The client may try a `/discovery` suffix after a failed service attempt. Individual operations are bounded; multiple discovery/setup operations can take longer than a single Request Timeout.

If NiFi cannot decrypt existing flow properties after recreating a demo container, restore the original matching `.env` key. Do not overwrite encrypted configuration or delete repositories to hide the error.
