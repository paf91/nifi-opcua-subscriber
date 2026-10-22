# Troubleshooting

Start with `logs/nifi-app.log`, processor bulletins, validation errors and queue/backpressure status. Capture the NAR checksum, NiFi/JDK versions, sanitized endpoint and time of failure. Do not attach private keys, credentials, raw production flows or proprietary tag inventories to public issues.

| Symptom | Check |
|---|---|
| SubscribeOpcUa is missing | Correct library/autoload path, readable complete NAR, duplicate bundles and NAR loading errors; refresh UI |
| UnsupportedClassVersionError | Wrong NAR/JVM combination; modern needs Java 21 |
| Processor is restricted | Administrator authorization for filesystem read/write restricted components |
| Invalid Tags | Complete parseable NodeIds; unique aliases/NodeIds; JSON keys and numeric ranges |
| File not found / access denied | Absolute paths inside the NiFi host/container; service account permissions |
| Certificate URI error | During Loading client identity: client certificate must have exactly one absolute URI SAN; leave Application URI unset or match it exactly. During Opening authenticated session: also check that the server's advertised Application URI matches its server certificate URI SAN |
| Certificate/key mismatch | Use the matching RSA private key and leaf certificate, leaf first in a PEM chain |
| Bad_CertificateUntrusted | Client trust on the OPC server and server/CA trust in NiFi's PKI directory |
| Bad_CertificateHostNameInvalid | Host in the selected server-advertised endpoint must match its certificate DNS/IP SAN; a literal IP needs an IPAddress SAN. Certificate validation mode skips only this match while retaining trust and Application URI checks |
| Expiry or revocation failure | Clock, certificate validity and required issuer certificates/CRLs; Certificate mode retains these checks |
| Bad_UserAccessDenied / identity rejection | Username/password and access rights on the server; certificate trust alone is not user authorization |
| No OPC TCP endpoint for configured settings | Match Security Policy, Security Mode and Authentication to a server endpoint; selection never downgrades automatically |
| None/None login asks for PKI | The server encrypts username tokens separately from its channel; trust the server certificate in PKI Directory; no client certificate/key is required |
| Anonymous configuration asks for client certificate | Sign and SignAndEncrypt still authenticate the client application; choose None/None only for an endpoint that offers it |
| Connection to an unexpected hostname | OPC discovery may advertise another host; fix DNS/network/server configuration |
| RUNNING but no data | Bulletins, endpoint reachability, primary role, outbound backpressure and node permissions |
| Only initial values | Subscription emits changes, not periodic snapshots of unchanged values |
| Bad/Uncertain values | Inspect statusCode and the OPC server's node quality; those values are emitted deliberately |
| Growing queue | Downstream flow stopped or slower than source; stop source when not needed |
| Drop counter increasing | Local buffer overflow or encoding failure; inspect value sizes, rate and downstream throughput |
| Old values after Start | Pending memory records drain first, retaining old source timestamps and endpoint |
| Unexpected initial values after reconnect | New subscriptions can send current values again; no built-in deduplication |
| Demo cannot start | Missing legacy NAR/demo classes, Docker subnet conflict, UID 1000 permissions, or lost .env key |

To isolate a server issue, begin with one known readable NodeId and verified certificates. Add tags gradually. An invalid monitored item fails the connection attempt; retries will not fix a wrong NodeId. Prefer correcting server certificates/configuration. For an explicit hostname compatibility exception, use Server Certificate Validation = Certificate. None bypasses server certificate validation and permits impersonation; it does not override the server's rejection of a client certificate or credentials. See the [validation modes](configuration.md#certificates-and-trust).

For discovery failures, supply the server's advertised discovery URL, including its endpoint path. The client may try a `/discovery` suffix after a failed service attempt. Individual operations are bounded; multiple discovery/setup operations can take longer than a single Request Timeout.

If NiFi cannot decrypt existing flow properties after recreating a demo container, restore the original matching `.env` key. Do not overwrite encrypted configuration or delete repositories to hide the error.
