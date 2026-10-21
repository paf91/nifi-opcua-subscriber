# Security

## Scope

The defaults are Basic256Sha256 / SignAndEncrypt with an RSA client certificate/key, username/password authentication and server-certificate validation. Configurable policies also allow unencrypted or signed channels and anonymous login. Selection must exactly match the configured policy/mode/authentication; it never falls back to weaker security. Secured channels require mutual certificate trust. Unencrypted channels need no client identity, but encrypted username tokens still require validation of the server certificate. None with an unencrypted username token sends credentials without encryption. There is no trust-all option. See the [configuration reference](docs/configuration.md#security-and-authentication).

Private keys and passwords are configuration secrets. Use NiFi sensitive parameters, restrictive filesystem permissions and trusted deployment administrators. The processor is a restricted component because it reads identity files and maintains a PKI store. Public demo credentials and generated demo identities are not production credentials.

## Reporting

Do not open a public issue containing an exploit, credentials, private keys, raw flow configuration or production data. On the GitHub repository, use **Security → Report a vulnerability** if private vulnerability reporting is enabled. If that option is unavailable, open a minimal issue asking the maintainer to enable a private reporting channel without disclosing the vulnerability.

Maintainers must enable private vulnerability reporting when publishing. No private email address or guaranteed response SLA is configured by this repository.

## Supported scope

No long-term security support promise is made for any release. Exact build targets and validation limits are in the README. Supporting a legacy JVM target does not imply that its runtime, operating system or dependencies are up to date or secure.

Dependency inventories identify what is bundled; they are not vulnerability scans. Before a release, review current advisories for the resolved versions and record the result. Updates that affect Milo/NiFi APIs must pass both build targets and lifecycle tests.

## Publication hygiene

Run the publication checks before sharing source. They detect selected credential formats, private-key blocks and forbidden file paths, but do not prove the absence of all secrets. Review the actual export. Public snapshots must not contain private source history or customer documents.
