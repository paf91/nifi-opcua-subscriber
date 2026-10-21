# Configuration reference

[Documentation index](../README.md#documentation)

| Property | What to supply |
|---|---|
| Endpoint URL | Server discovery URL, e.g. `opc.tcp://server:4840/endpoint` |
| Security Policy | None, Basic128Rsa15, Basic256, Basic256Sha256 (default), Aes128_Sha256_RsaOaep, Aes256_Sha256_RsaPss |
| Security Mode | None, Sign, SignAndEncrypt (default) |
| Authentication | Username/Password (default), Anonymous |
| Application URI | Optional override; derived from the client certificate for secured channels, otherwise defaults to `urn:nifi:opcua:subscriber` |
| Client Certificate File | Absolute path to PEM certificate chain, leaf first, or a DER leaf certificate |
| Client Private Key File | Absolute path to RSA PEM key (PKCS#1/PKCS#8) or unencrypted PKCS#8 DER |
| Private Key Password | Sensitive property for encrypted PEM keys; unset for unencrypted keys |
| PKI Directory | Persistent directory containing approved server/issuer certificates and CRLs |
| Username / Password | OPC UA user credentials; Password is sensitive |

## Security and authentication

These are OPC UA security settings for `opc.tcp`, independent of HTTPS/TLS on the NiFi UI. Match the endpoint advertised by the server; discovery never silently downgrades the selected policy, mode or authentication.

| Channel | Authentication | Client certificate/key | PKI Directory | Credentials |
|---|---|---|---|---|
| None / None | Anonymous | Not needed | Not needed | Not needed |
| None / None | Username/Password, unencrypted token | Not needed | Not needed | Username and Password |
| None / None | Username/Password, encrypted token | Not needed | Required for the server certificate | Username and Password |
| Any supported non-None policy / Sign | Anonymous or Username/Password | Required | Required | Only for Username/Password |
| Any supported non-None policy / SignAndEncrypt | Anonymous or Username/Password | Required | Required | Only for Username/Password |

**Username/password without certificates:** select None, None and Username/Password, set your credentials and Tags, and leave certificate/key/PKI properties unset. If the server advertises an encrypted username-token policy, startup asks for a PKI Directory containing the trusted server certificate. This protects the password token even though channel data is unencrypted; no client certificate is required. The server advertises token protection separately from channel protection, and the client follows that token policy.

**Anonymous without certificates:** select None, None and Anonymous. Set Endpoint URL and Tags. Leave certificate/key/PKI and username/password unset.

**Secured channels:** the existing Basic256Sha256 / SignAndEncrypt / Username/Password defaults are unchanged. Anonymous login on a secured channel still requires the application certificate and mutual trust. Sign authenticates messages without encrypting their contents. None with an unencrypted username token sends credentials without encryption; choose it only when that matches the intended network configuration. Basic128Rsa15 and Basic256 are explicit legacy compatibility options. This processor supports the six listed RSA/None policies, not every OPC UA profile or identity-token type; X.509 user authentication, issued tokens and HTTPS transport are outside this implementation.

Policy None requires mode None. Other policies require Sign or SignAndEncrypt. Unavailable policy/mode/login combinations fail with a configuration-specific message. Stop, edit and restart to apply settings. Old buffered records keep their original endpoint and security attributes until drained.

## Certificates and trust

Use sensitive NiFi parameters for passwords. Paths refer to the **NiFi server filesystem**, not your browser's filesystem. The NiFi service account needs read access to the certificate/key and access to the PKI directory. When channel security is enabled, the certificate must be valid, have an RSA key of at least 2048 bits, and match the private key. Its single URI subject alternative name identifies this OPC UA client; it is not the server endpoint. Application URI is derived from that certificate, and any explicit override must match.

The PKI Directory holds trusted server certificates and certificate authorities, separately from your client certificate and key. It is not another username/password credential. Trust is mutual: the OPC UA administrator must trust the client certificate, and NiFi must trust the server certificate or its CA. Populate the PKI structure before starting:

```text
pki/
  trusted/certs/    approved server certificates / trusted CA certificates
  trusted/crl/     applicable trusted CA revocation lists
  issuers/certs/    intermediate CA certificates
  issuers/crl/     applicable intermediate CA revocation lists
```

All Milo validation checks are enabled, including server hostname, Application URI, validity, certificate usage and revocation checks. A CA-signed chain may require its CRLs. Obtain certificates/CRLs from the server administrator; verify identity before trusting them. There is no trust-all switch. Discovery itself may use an unsecured discovery endpoint; the selected session endpoint must still use the required security settings. If discovery advertises an unreachable hostname, fix server endpoint configuration or network/DNS routing.

NiFi administrators must grant this processor's filesystem permissions in secured deployments. It reads private-key material and initializes the PKI store; those files are never put in FlowFiles.

## Tags

Set **Tags** to one complete NodeId per line, optionally prefixed with an alias and `|`:

```text
# Blank lines and comment lines are ignored
DemoSwitch | ns=2;s=Demo.Boolean
Server time | ns=0;i=2258
```

A bare NodeId also works; its alias defaults to the NodeId. For per-tag settings, paste a JSON array:

```json
[
  {"alias":"DemoSwitch","nodeId":"ns=2;s=Demo.Boolean","samplingIntervalMs":500,"queueSize":100},
  {"alias":"Server time","nodeId":"ns=0;i=2258"}
]
```

JSON arrays may also contain plain NodeId strings. Omitted settings use processor defaults. See `examples/tags.txt` and `examples/tags.json`. Duplicate aliases or NodeIds, including duplicates across the list and individual properties, fail validation. Stop the processor before editing, then start it to subscribe to the updated list.

## Individual properties

Minimal property:

```text
Name:  DemoSwitch
Value: ns=2;s=Demo.Boolean
```

With overrides:

```json
{
  "nodeId": "ns=2;s=Demo.Boolean",
  "samplingIntervalMs": 500,
  "queueSize": 100,
  "discardOldest": true
}
```

`samplingIntervalMs` is requested sampling on the server (0 requests its fastest supported rate). `queueSize` and `discardOldest` apply to that server-side monitored item's queue, not NiFi's local buffer. Unsupported options and malformed values fail configuration validation. The server may revise requested intervals/queue sizes. The connection attempt fails if any configured monitored item cannot be created. NiFi scheduling remains active and retries according to Reconnect Delay.

Processor-wide defaults are publishing interval **1000 ms**, sampling **1000 ms**, monitored item queue **100**, local notification capacity **10000**, batch size **500**, request timeout **10000 ms**, and reconnect delay **5000 ms**. Resource consumption also depends on the size of individual values; the buffer capacity counts notifications, not bytes.


NodeIds are case-sensitive. Whitespace around a list entry is trimmed; punctuation within the NodeId is preserved. Namespace indexes belong to the target server and can change after reconfiguration. Obtain the complete NodeId from that server's address-space browser. This processor does not browse nodes or resolve namespace URIs.

## Required and optional properties

| Property | Required | Default / range |
|---|---|---|
| Endpoint URL | Yes | An `opc.tcp` URL; no embedded credentials, query or fragment |
| Tags | At least one bulk or individual tag | Unset |
| Security Policy | Yes | Basic256Sha256 |
| Security Mode | Yes | SignAndEncrypt |
| Authentication | Yes | Username/Password |
| Client Certificate File | For Sign / SignAndEncrypt | Absolute host path |
| Client Private Key File | For Sign / SignAndEncrypt | Absolute host path |
| Private Key Password | No | Unset; sensitive |
| PKI Directory | For secured channels or encrypted username tokens | Absolute host path; directory may be initialized by Milo |
| Username | For Username/Password | Unset |
| Password | For Username/Password | Unset; sensitive |
| Application URI | No | Certificate URI SAN, or `urn:nifi:opcua:subscriber` without channel security |
| Publishing Interval (ms) | Yes | 1000; 1–86400000 |
| Default Sampling Interval (ms) | Yes | 1000; 1–86400000 |
| Default Monitored Item Queue Size | Yes | 100; 1–1000000 |
| Notification Buffer Capacity | Yes | 10000 records; 1–1000000 |
| Max Records Per FlowFile | Yes | 500; 1–100000 |
| Request Timeout (ms) | Yes | 10000; 1–300000 per operation |
| Reconnect Delay (ms) | Yes | 5000; 1–300000 |

A per-tag sampling override may be 0 (fastest supported); the processor-wide default must be positive. Per-tag queue size is 1–1000000; `discardOldest` defaults to `true`. JSON arrays may mix strings and objects. Unknown JSON options, duplicate JSON keys, duplicate aliases and duplicate normalized NodeIds are rejected.

All properties are literal values or NiFi parameter references; NiFi Expression Language is not enabled. Use sensitive parameters for sensitive fields. Certificate paths must be accessible inside the NiFi container when running in Docker. Private-key passwords apply to encrypted PEM; encrypted DER keys are not supported.

## From a tag table to configuration

These examples are fictional and are also included in the processor’s embedded NiFi documentation. Replace the addresses with those provided by your server administrator:

```text
Temperature | ns=2;s=Example.Tank.Temperature
Pressure | ns=2;s=Example.Pipe.Pressure
PumpRunning | ns=2;s=Example.Pump.Running
```

Copy the full address, not only its display name. You do not configure `Float`, `Boolean`, or another value type: the processor uses the server's typed value. A table of NodeIds does not contain the endpoint URL, credentials or trust material; obtain those separately. The supplied demo exposes `Demo.Boolean`, not every illustrative node in this guide.
