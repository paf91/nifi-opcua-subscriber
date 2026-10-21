# OPC UA server interoperability

Use three layers: the two Milo server fixtures for repeatable regression tests, an independent implementation such as open62541, and the actual target server. open62541 supports the RSA policies used here and separate session authentication; enable the needed policies and credentials in its server configuration. Its available policies depend on the crypto backend and certificates. See the official [security policies](https://open62541.org/doc/v1.5.5/security/policies.html) and [security configuration](https://open62541.org/doc/v1.5.6/security/index.html).

An independent implementation can reveal assumptions hidden when the client and test server share the same SDK. Passing a smoke test does not establish compatibility with every server, address space, firmware version or deployment.

## Repeatable built-in tests

Run `mvn -Pnifi1 clean verify` on JDK 8 and `mvn -Pnifi2 clean verify` on JDK 21. `SecureSubscriptionTest` uses local temporary Milo servers and covers all 22 valid combinations of the six policies, three modes and two authentication types. It also tests a None channel with encrypted username tokens and trusted, missing or untrusted PKI, and rejects an incorrect password without channel encryption. These tests require permission to bind loopback ports. The normal CI build includes them.

Socket-free tests check all 36 combinations, reject incompatible policy/mode pairs, validate real username-token encryption and server trust, preserve buffered security attributes, load PEM/DER keys, and decode binary OPC UA matrices into JSON.

## Built-in open62541 fixture

CI builds the pinned open62541 1.5.9 source with OpenSSL, verifies its source archive SHA-256, and enables `Open62541Test` on both Java targets. The fixture is only a test executable, never a bundled NAR dependency. It creates fresh certificates, trusts only the generated client, and binds inside the test environment. It explicitly enables legacy policies and plaintext username tokens to test the supported compatibility settings; it is not a production server configuration.

The independent suite covers all 22 policy/mode/login combinations, separately encrypted username tokens on a None channel, Absolute/Percent deadbands including quality transitions, rejection of missing EURange, verification failures, callbacks after close, and a server restart that moves a URI to a new index while placing a different value at the old index.

To reproduce locally with Docker:

```sh
docker build --tag opcua-interop --file tests/interop/Dockerfile tests/interop
mkdir -p dist/open62541
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/dist/open62541:/output" opcua-interop
export OPCUA_OPEN62541_BINARY="$PWD/dist/open62541/open62541"
mvn -Pnifi1 clean verify
```

Use a Linux runtime compatible with the Ubuntu 22.04 test binary, or run Maven in the same Temurin Docker images as CI, mounting the repository at `/work` and setting `OPCUA_OPEN62541_BINARY=/work/dist/open62541/open62541`. Switch to Java 21 and `-Pnifi2` for the modern adapter. Both independent-server suites are skipped locally when this variable is unset; do not count a skip as independent-server coverage. Server logs are retained under `processors/target/interop-logs/`. GitHub CI, GitLab CI and `scripts/release.py` require both suites to execute without skips. The fixture also exercises a missing operation-limit property, quiet keep-alives and a server that temporarily stops responding while keeping TCP open. The tests use isolated local endpoints and never contact your configured production server.

## Test your target server

Start a test instance of open62541, or use a designated test endpoint on your target server. Obtain its exact endpoint URL, advertised policy/mode, identity token policy, and a readable NodeId. For a None/None anonymous endpoint:

```sh
export OPCUA_TEST_ENDPOINT='opc.tcp://localhost:4840'
export OPCUA_TEST_NODE_ID='ns=0;i=2258'
export OPCUA_TEST_POLICY='None'
export OPCUA_TEST_MODE='None'
export OPCUA_TEST_AUTH='Anonymous'
mvn -Pnifi1 -pl processors -Dtest=ExternalServerTest test
```

Use the server's actual NodeId if it does not expose that standard server-time node. To test username/password, set `OPCUA_TEST_AUTH='Username/Password'` and provide `OPCUA_TEST_USERNAME` and `OPCUA_TEST_PASSWORD` through the environment. Keep passwords out of Maven command-line arguments. Set `OPCUA_TEST_PKI` when the username token is encrypted, even with a None channel.

For secured channels, set `OPCUA_TEST_POLICY` and `OPCUA_TEST_MODE` to the advertised settings, then set absolute paths in `OPCUA_TEST_CERTIFICATE`, `OPCUA_TEST_KEY` and `OPCUA_TEST_PKI`. Optional variables are `OPCUA_TEST_KEY_PASSWORD` and `OPCUA_TEST_APPLICATION_URI`. Trust must be configured at both ends. See the [certificate requirements](configuration.md#certificates-and-trust).

Repeat on JDK 21 with `-Pnifi2`; clean the build when switching profiles. The external test creates a subscription, requires a Good initial notification, closes it, and reconnects for a second subscription. It is skipped unless `OPCUA_TEST_ENDPOINT` is set. It does not start or reconfigure the external server.

## Focused acceptance matrix

| Scenario | Evidence to collect |
|---|---|
| None/None, Anonymous | Good values with no client files, PKI or credentials |
| None/None, Username/Password, plain token | Good values with credentials only; incorrect credentials rejected |
| None/None, Username/Password, encrypted token | Good values with server trust only; untrusted certificate rejected |
| Basic256Sha256, Sign and SignAndEncrypt | Both login types where advertised; mutual trust enforced |
| AES policies / legacy RSA policies | Test only the policies offered and required by the target deployment |
| Unsupported identity or policy | Clear failure, without security or authentication fallback |
| Scalars, arrays, matrices, Bad/Uncertain quality | Correct JSON types, dimensions, timestamps and status values |
| Stop/Start and server restart | Delivery resumes; old sessions/subscriptions eventually disappear |
| Backpressure and primary failover | Bounded buffer, truthful drop reporting, ownership and recovery |

The external smoke test covers connection and initial delivery. Use the NiFi UI with the built NAR for JSON/output checks, and the [runtime acceptance procedure](acceptance.md) for lifecycle and load scenarios. Record server product/version, configured policies, NiFi/JDK versions, NAR hash and results. Independent-server results and live tests must be recorded as unrun when the execution environment cannot open sockets; compilation or unit-test success is not a live-server pass.
