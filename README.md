# ValleyCert

A client certificate utility for the ValleyAuth ecosystem. ValleyCert is a **library**, not a certificate authority. It requests, validates, stores, and renews certificates issued by ValleyAuth Core. ValleyCert only issues certificates in ValleyAuth format and not SSL or other certificate types.

## What It Does

- **Requests certificates** from ValleyAuth Core on behalf of your plugin
- **Validates certificates** using ECDSA signature verification against the ValleyAuth CA
- **Stores certificates** locally with atomic writes and backup copies
- **Renews certificates** automatically before expiry (within a 7-day window)
- **Enforces capabilities** so plugins can gate operations behind specific permissions
- **Handles offline mode** with mock certificates when the CA is unreachable

## Architecture

ValleyCert sits between your plugin and ValleyAuth Core. Here is how the pieces connect:

```
Your Plugin
    |
    v
ValleyCert (this library)
    |
    v
ValleyAuth Core (plugin on the server)
    |
    v
ValleyCertAPI (Certificate Authority)
```

1. Your plugin instantiates `ValleyCert` and calls `initialize()`
2. ValleyCert checks for an existing valid certificate in `<plugin-data>/certs/<pluginId>/valley.cert`
3. If none exists or it is expired, ValleyCert requests one from ValleyAuth Core via HTTP
4. ValleyAuth Core forwards the request to ValleyCertAPI, which issues and signs the certificate
5. ValleyCert validates the received certificate (ECDSA signature, expiry, issuer, revocation status) then stores it
6. Your plugin calls `validateCapability()` to confirm the certificate grants the needed permission

## Requirements

- Java 21 or newer
- Paper 1.21+ server
- ValleyAuth plugin installed and running (provides the CA backend)

## Installation

1. Download the latest `ValleyCert-*.jar` from releases
2. Drop it into your server's `plugins/` folder
3. Restart the server

ValleyCert is a library plugin. Other ValleyRealm plugins and ValleyAuth addons depend on it, but you will not interact with it directly as a server administrator.

## Integration Guide

This section explains how to use ValleyCert as a library in your own Paper plugin.

### Step 1: Add ValleyCert as a Dependency

Add ValleyCert to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.valleyrealm:valleycert:<version>")
}
```

Use `compileOnly` so ValleyCert is not bundled into your JAR. It will be provided at runtime by the server.

### Step 2: Initialize ValleyCert

In your plugin's `onEnable()`, create a `ValleyCert` instance and call `initialize()`:

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.ValleyCert;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    private ValleyCert cert;

    @Override
    public void onEnable() {
        // Create a ValleyCert instance scoped to your plugin's data folder
        cert = new ValleyCert(getDataFolder().toPath());

        // Define the capabilities your plugin needs
        String[] capabilities = {"VLINK", "IDENTITY_LINK"};

        // Initialize (requests cert from CA if not cached, max 120 days)
        boolean success = cert.initialize("my-plugin", capabilities, 90);

        if (success) {
            getLogger().info("ValleyCert initialized. Certificate is valid.");
        } else {
            getLogger().severe("ValleyCert failed to initialize. Check CA connection.");
        }
    }
}
```

### Step 3: Validate Capabilities

Before performing protected operations, check that your certificate grants the required capability:

```java
if (cert.validateCapability("VLINK")) {
    // Perform VLink operation
} else {
    getLogger().warning("Certificate does not have VLINK capability.");
}
```

### Step 4: Get Certificate Details

Access the full certificate data when you need to inspect it:

```java
CertificateData certificate = cert.getCertificate();
if (certificate != null) {
    getLogger().info("Certificate ID: " + certificate.getCertificateId());
    getLogger().info("Expires: " + certificate.getExpirationDate());
    getLogger().info("Capabilities: " + certificate.getCapabilities());
}
```

### Step 5: Check if Valid

Query whether the certificate is still valid at any time:

```java
if (cert.isInitialized()) {
    // Certificate exists and passes all validation checks
}
```

### Full Integration Example

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.ValleyCert;
import com.valleyrealm.valleycert.CertificateData;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    private ValleyCert cert;

    @Override
    public void onEnable() {
        cert = new ValleyCert(getDataFolder().toPath());

        String[] capabilities = {"VLINK", "MIGRATION_ACCESS"};

        if (!cert.initialize("my-plugin", capabilities, 90)) {
            getLogger().severe("Could not obtain certificate. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Certificate loaded: " + cert.getCertificate().getCertificateId());
    }

    public void performVLinkAction() {
        if (!cert.validateCapability("VLINK")) {
            getLogger().warning("Missing VLINK capability.");
            return;
        }

        // Safe to proceed with VLink operation
        CertificateData certData = cert.getCertificate();
        // Use certData to present to protected APIs
    }

    public void performMigration() {
        if (!cert.validateCapability("MIGRATION_PROVIDER")) {
            getLogger().warning("Missing MIGRATION_PROVIDER capability.");
            return;
        }

        // Safe to register as a migration provider
    }
}
```

## Certificate Flow

When you call `cert.initialize("my-plugin", capabilities, 90)`, the following happens step by step:

1. **Check for cached certificate.** ValleyCert looks for an existing certificate at `<plugin-data>/certs/<pluginId>/valley.cert`.
2. **Load and validate.** If a file exists, it is deserialized and checked: status must be ACTIVE, not expired, issuer must be "ValleyAuth Core", ECDSA signature must verify, and the revocation timestamp must be null.
3. **Use cached cert.** If the cached certificate is valid, it is loaded into memory and a background renewal check is scheduled.
4. **Request new certificate.** If no valid certificate exists, ValleyCert sends an HTTP POST to ValleyAuth Core at `/api/certificate/issue` with the plugin ID, requested capabilities, and desired lifetime.
5. **ValleyAuth Core forwards to CA.** ValleyAuth Core relays the request to ValleyCertAPI, which issues and signs a certificate with an ECDSA key.
6. **Validate received certificate.** ValleyCert verifies the certificate's ECDSA signature using the embedded public key, checks the issuer, expiry, and revocation status.
7. **Store locally.** The validated certificate is written to disk using atomic file operations: write to a temp file, back up the old file, then atomically move the temp file into place.
8. **Schedule renewal.** A background scheduler is started that checks the certificate every hour. When it enters the final 7 days before expiry, an automatic renewal is triggered.

## Capabilities

Capabilities are string identifiers that grant specific permissions to your plugin. Request only what you need.

| Capability | Description | When to Request |
|---|---|---|
| `VLINK` | Perform VLink operations between servers | Use for cross-server linking and data synchronization |
| `IDENTITY_LINK` | Link player identities across services | Use when your plugin maps player accounts between platforms |
| `RANK_SHARE` | Share rank data between servers | Use for rank synchronization across a network |
| `MIGRATION_PROVIDER` | Register as a migration provider | Use when your plugin offers to migrate data for other plugins |
| `MIGRATION_ACCESS` | Access migration APIs | Use when your plugin consumes migration data from providers |
| `CERTIFICATE_MANAGEMENT` | Manage certificates (issue, revoke, renew) | Use for administrative tools that manage the certificate lifecycle |

You can request multiple capabilities at once:

```java
String[] capabilities = {"VLINK", "IDENTITY_LINK", "RANK_SHARE"};
cert.initialize("my-network-plugin", capabilities, 90);
```

## Configuration

ValleyCert has no config file. All behavior is controlled through the Java API.

### API Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `pluginDataFolder` | `Path` | *(required, constructor)* | Your plugin's data folder for certificate storage |
| `pluginId` | `String` | *(required)* | Unique identifier for your plugin |
| `capabilities` | `String[]` | *(required)* | Capabilities your plugin needs |
| `requestedLifetimeDays` | `int` | 90 | Certificate lifetime in days (max 120) |
| `apiUrl` | `String` | `null` | ValleyAuth Core endpoint URL. Omit for mock mode |

The API URL is configured in the ValleyAuth plugin's config. By default it contacts the public API at `https://cert.strawberry.dpdns.org`. It is recommended that you host your own certificate API. See the [ValleyCertAPI repository](https://github.com/SabeeirSharrma/ValleyCertAPI) for details.

## Offline Mode

When no API URL is configured (or ValleyAuth Core is unreachable), ValleyCert falls back to mock certificates. A mock certificate:

- Has a random UUID as its certificate ID
- Contains the requested capabilities
- Is signed with a mock signature (skips ECDSA verification)
- Is valid for the requested lifetime
- Has issuer set to "ValleyAuth Core"

Mock certificates are useful for local development and testing. They are not accepted by production ValleyAuth APIs that verify signatures server-side.

```java
// ValleyCert automatically uses mock mode when apiUrl is null
ValleyCert cert = new ValleyCert(getDataFolder().toPath());
cert.initialize("dev-plugin", new String[]{"VLINK"}, 90);
// This works offline without a CA connection
```

## Revocation

Certificates carry an encrypted revocation timestamp. Since the client cannot decrypt it without the CA's private key, any non-null revocation field means the certificate has been revoked.

ValleyCert enforces this passively on every validation call. If the `encryptedRevocationTimestamp` field is present and non-null, the certificate is treated as invalid immediately. This is a fail-closed design: any corruption, tampering, or presence of the revocation field blocks all operations.

Revocation is checked in these places:

- During `initialize()` when loading a cached certificate
- During `validateCapability()` on every call
- During `isInitialized()` on every call
- During the background renewal check before attempting renewal

## Renewal

A background scheduler runs on a daemon thread and checks certificates every hour.

**Renewal window:** When a certificate enters its final 7 days before expiry, the scheduler triggers a renewal request to ValleyAuth Core.

**Renewal flow:**

1. The scheduler detects the certificate needs renewal (within 7 days of expiry)
2. A renewal request is sent to ValleyAuth Core at `/api/certificate/renew` with the current certificate ID
3. The new certificate is validated (ECDSA signature, issuer, expiry, revocation)
4. The old certificate file is backed up (`.backup` suffix)
5. The new certificate is atomically written to disk
6. The old certificate is replaced in memory only after the new one is verified
7. If renewal fails, the old certificate remains valid until it expires

The renewal check does not run if the current certificate is already invalid.

## Security

ValleyCert validates every certificate against these checks:

1. **Status:** Must be `ACTIVE`. Certificates with status `EXPIRED`, `REVOKED`, or `SUSPENDED` are rejected.
2. **Expiry:** The current time must be between the issuance date and expiration date.
3. **Issuer:** Must be exactly `ValleyAuth Core`. Certificates from other issuers are rejected.
4. **ECDSA Signature:** The certificate's signature is verified using its embedded public key with the `SHA256withECDSA` algorithm. The signed data consists of the certificate ID, plugin ID, capabilities, issuance timestamp, expiration timestamp, and issuer string.
5. **Revocation:** Any non-null `encryptedRevocationTimestamp` field causes immediate rejection.

**Key storage:** Certificate private keys and material are stored in your plugin's data folder at `<plugin-data>/certs/<pluginId>/valley.cert`. The file is JSON and uses atomic writes to prevent partial corruption.

**Mock certificates** bypass ECDSA signature verification (detected by the `mock-signature` prefix) but still require all other checks to pass.

## Troubleshooting

### Certificate is not valid

- Check that ValleyAuth Core is running and accessible
- Verify the certificate has not expired (`cert.getCertificate().getExpirationDate()`)
- Confirm the issuer is "ValleyAuth Core"
- Look for revocation: if the encrypted revocation timestamp is present, the certificate was revoked by the CA

### CA is unreachable

- ValleyCert falls back to mock certificates when the API URL is not configured
- If the API URL is set but the CA is down, initialization fails and `initialize()` returns `false`
- Check the server logs for `[ValleyCert]` messages to see the HTTP error
- Ensure ValleyAuth Core is installed and its config points to a valid certificate API

### Certificate capabilities are missing

- Capabilities are set at issuance time and cannot be changed after the fact
- Request a new certificate with the needed capabilities
- Delete the cached certificate at `<plugin-data>/certs/<pluginId>/valley.cert` and reinitialize

### Renewal is not happening

- The renewal scheduler runs on a daemon thread and checks hourly
- Renewal only triggers when the certificate enters its final 7 days
- If the CA is unreachable during renewal, the old certificate stays valid until it expires
- Check logs for `[ValleyCert Renewer]` messages

### Mock certificate in production

- This means no API URL is configured
- Check ValleyAuth Core's config for the certificate API endpoint
- Mock certificates skip signature verification and are not accepted by production APIs

## Building from Source

```bash
git clone https://github.com/SabeeirSharrma/ValleyCert.git
cd ValleyCert
./gradlew build
```

The output JAR lands in `build/libs/`.

### Project Structure

```
src/main/java/com/valleyrealm/valleycert/
    ValleyCert.java                 # Main entry point
    CertificateData.java            # Certificate data model
    CertificateRequestResult.java   # Request result wrapper
    request/
        CertificateRequester.java   # HTTP requests to ValleyAuth Core
    storage/
        CertificateStorage.java     # Local file storage with atomic writes
    validation/
        CertificateValidator.java   # ECDSA signature and expiry checks
    renewal/
        CertificateRenewer.java     # Background renewal scheduler
```

## License

MIT
