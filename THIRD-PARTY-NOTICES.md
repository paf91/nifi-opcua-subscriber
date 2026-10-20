# Third-party components

The Apache-2.0 license in this repository covers project code, not all bundled dependencies. Dependencies retain their own licenses, copyright notices and terms.

The NAR bundles unmodified Maven dependencies, including:

| Component family | Upstream | Principal license |
|---|---|---|
| Apache NiFi utilities and supporting Apache libraries | https://nifi.apache.org/ | Apache-2.0 |
| Eclipse Milo | https://github.com/eclipse-milo/milo | EPL-2.0 |
| DigitalPetri state-machine libraries | https://github.com/digitalpetri | Consult the exact artifact's license |
| Netty | https://netty.io/ | Apache-2.0; additional third-party notices may apply |
| Jackson | https://github.com/FasterXML/jackson | Apache-2.0 |
| Guava | https://github.com/google/guava | Apache-2.0 |
| Bouncy Castle | https://www.bouncycastle.org/ | Bouncy Castle license (MIT-style) |

This table is an orientation, not an exhaustive license bill of materials. The resolved dependency set differs between build profiles. Generate an inventory from each actual NAR using `scripts/nar-inventory.py`; it records bundled JAR checksums, Maven coordinates and embedded license/notice paths. Keep embedded JAR license/notice files when redistributing artifacts.

Milo source is available from the upstream tags matching the inventory (currently `v0.6.16` and `v1.1.8`), and published source artifacts are available through Maven Central. Each inventory entry with Maven coordinates includes a source-JAR URL. Preserve third-party source availability and license notices when distributing NARs; project licensing does not relicense those components.

Test-only dependencies and the demo server are not bundled in the processor NAR. Container images, operating systems and JDKs have separate licensing terms. The demo image is a development convenience, not a redistribution manifest for an entire operating system.

For known artifacts without embedded Maven metadata (including Bouncy Castle), the inventory records filename-derived coordinates separately as `knownFilenameCoordinates`. The advisory checker includes them; unknown JARs remain explicitly listed as exclusions.
