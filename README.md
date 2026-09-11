# ValleyCert

Client certificate utility for ValleyAuth. ValleyCert is **not** a certificate authority. It requests, validates, stores, and renews certificates issued by ValleyAuth Core. **ValleyCert only issues certificates of ValleyAuth format and NOT ANY SSL/other CERTIFICATES**.

## What It Does

- **Requests certificates** from ValleyAuth Core on behalf of your plugin
- **Validates certificates** using ECDSA signature verification against the ValleyAuth CA
- **Stores certificates** locally with atomic writes and backup copies
- **Renews certificates** automatically before expiry (within 7 days)
- **Enforces capabilities** so plugins can gate operations behind specific permissions (e.g. `VLINK`, `MIGRATION_PROVIDER`)

## Requirements

- Java 21+
- Paper 1.21+ server
- ValleyAuth plugin (provides the CA backend)

## Installation

1. Download the latest `ValleyCert-1.0.0-SNAPSHOT.jar` from releases or modrinth
2. Drop it into your server's `plugins/` folder
3. Restart the server

ValleyCert is a library plugin. Other ValleyRealm plugins and ValleyAuth addons depend on it, but you won't interact with it directly.

## How It Works

ValleyCert sits between your plugin and ValleyAuth Core. The flow looks like this:

```
Your Plugin  →  ValleyCert  →  ValleyAuth Core  →  ValleyCertAPI (CA)
```

1. Your plugin calls `ValleyCert.initialize(pluginId, capabilities, lifetimeDays)`
2. ValleyCert checks for an existing valid certificate in `<plugin-data>/certs/<pluginId>/valley.cert`
3. If none exists (or it's expired/revoked), ValleyCert requests one from ValleyAuth Core via HTTP
4. ValleyAuth Core forwards the request to ValleyCertAPI, which issues and signs the certificate
5. ValleyCert validates the received certificate (ECDSA signature, expiry, issuer, revocation status), then stores it
6. Your plugin calls `validateCapability("VLINK")` to confirm the certificate grants the needed permission

**Offline mode:** If no API URL is configured, ValleyCert falls back to mock certificates for local development.

**Revocation:** Certificates carry an encrypted revocation timestamp. Since the client can't decrypt it, any non-null revocation field means the certificate is invalid. Revocation is checked passively on every validation.

**Renewal:** A background scheduler checks certificates hourly. When a certificate enters its final 7 days, ValleyCert automatically requests a renewal and atomically replaces the old file (keeping a `.backup` until the new cert is verified).

## Configuration

ValleyCert has no config file. Behavior is controlled through the API:

| Parameter | Default | Description |
|---|---|---|
| `pluginId` | *(required)* | Unique identifier for your plugin |
| `capabilities` | *(required)* | String array of requested permissions |
| `requestedLifetimeDays` | 90 | Certificate lifetime (max 120 days) |
| `apiUrl` | `null` | ValleyAuth Core endpoint. Omit for mock mode |

## Building from Source

```bash
git clone https://github.com/SabeeirSharrma/ValleyCert.git
cd ValleyCert
./gradlew build
```

The output JAR lands in `build/libs/`.

## License

MIT
