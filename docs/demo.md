# Local demo

The demo runs NiFi 1.23.2 on Java 8 and a Milo OPC UA server using generated certificates, Basic256Sha256 / SignAndEncrypt, and test username/password authentication. It exposes a toggling Boolean at `ns=2;s=Demo.Boolean` plus standard server time.

Requirements: Docker Engine with Compose v2, Maven 3.9.11, JDK 8, Python 3.9+, and enough memory for NiFi (configured heap: 512 MiB–1 GiB, plus native/container overhead). Run commands from the repository root.

## Build and start

```sh
mvn -B -ntp clean verify
mvn -B -ntp -f demo/pom.xml clean package
python3 scripts/init-demo.py
docker compose up -d --build
```

The initializer creates `.env` only if absent and preserves the existing sensitive-properties key. Do not regenerate that key while reusing NiFi volumes. The demo server runs as UID/GID 1000; ensure `demo/runtime/security` is writable by that account. Configure ownership for your Docker host rather than making keys world-readable.

Wait until **http://localhost:18080/nifi/** responds, then:

```sh
python3 scripts/seed-preview.py
```

This creates a demo process group once. Re-running the seeder does not overwrite an existing flow. The returned URL opens that group.

## Inspect values

1. Configure the source and inspect **Tags** and the connection properties.
2. Enable and start the source. The downstream LogAttribute processor remains stopped.
3. Right-click the connection → **List queue** → inspect a FlowFile's content and attributes.
4. Stop the source when finished. Start the sink to drain the queue or explicitly empty the demo queue if its records are disposable.

The source is initially disabled. The queue expires demo FlowFiles after ten minutes and has backpressure limits; it is not a storage example.

## Stop and resume

```sh
docker compose stop
docker compose start
```

Configuration and repositories remain in named volumes. `docker compose down` removes containers/network but preserves named volumes unless `--volumes` is explicitly supplied. Deleting volumes is a destructive reset, not a routine shutdown. Preserve the matching `.env` and generated security directory when retaining volumes.

## Boundaries

Only the NiFi port is published, bound to `127.0.0.1:18080`; this HTTP UI has no login and is for local development. The OPC server is internal to the Compose network. The public test login is `demo` / `demo-password`; never reuse it or generated demo keys for production.

The default subnet is `172.30.252.0/24`. If it conflicts with an existing network, set `NIFI_DEMO_SUBNET` in `.env` before creating the network. The preview image expects the legacy NAR in `nar/target`; rebuild legacy after building the modern profile.

For an old persisted demo flow from before the public examples were renamed, stop the source and change Tags to the addresses above after rebuilding the demo server. The seeder intentionally does not migrate an existing flow.

The Compose project identifier remains `nifi-opcua-preview` so existing local demo volumes are reused after the repository rename. The public project and Maven artifacts are named `nifi-opcua-subscriber`.
