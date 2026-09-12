<!-- ValleyCert Documentation -->
<!-- Version: v0.2.0-alpha -->

[![Version](https://img.shields.io/badge/version-v0.2.0--alpha-blue)]()
[![Java](https://img.shields.io/badge/Java-21%2B-orange)]()
[![Paper](https://img.shields.io/badge/Paper-1.21%2B-green)]()
[![License](https://img.shields.io/badge/license-MIT-lightgrey)]()

# ValleyCert

**A client certificate utility library for the ValleyAuth ecosystem.**

ValleyCert is a library that other Paper plugins use to request, validate, store, and renew certificates from the ValleyAuth Certificate Authority. It is not a standalone plugin, not a CA, and not an authentication system. It is a single, focused tool: give your plugin a certificate, then let it prove that certificate is legitimate.

ValleyCert only issues certificates in the ValleyAuth format. It does not handle SSL/TLS, X.509, or any other certificate type.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Installation](#installation)
- [API Reference](#api-reference)
  - [ValleyCert (Main Entry Point)](#valleycert-main-entry-point)
  - [CertificateData (Certificate Model)](#certificatedata-certificate-model)
  - [CertificateRequestResult (Request Result)](#certificaterequestresult-request-result)
  - [CertificateRequester (HTTP Client)](#certificaterequester-http-client)
  - [CertificateStorage (File Storage)](#certificatestorage-file-storage)
  - [CertificateValidator (Validation)](#certificatevalidator-validation)
  - [CertificateRenewer (Auto-Renewal)](#certificaterenewer-auto-renewal)
- [Capabilities Reference](#capabilities-reference)
- [Certificate Flow](#certificate-flow)
- [Storage Layout](#storage-layout)
- [Security Model](#security-model)
- [Offline Mode](#offline-mode)
- [Revocation](#revocation)
- [Renewal](#renewal)
- [Integration Guide](#integration-guide)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Building from Source](#building-from-source)
- [Project Structure](#project-structure)
- [License](#license)

---

## Quick Start

Add ValleyCert as a `compileOnly` dependency, create an instance, call `initialize()`, and check capabilities before performing protected operations.

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.valleyrealm:valleycert:0.2.0-alpha")
}
```

```java
package com.example.myplugin;

import com.valleyrealm.valleycert.ValleyCert;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    private ValleyCert cert;

    @Override
    public void onEnable() {
        cert = new ValleyCert(getDataFolder().toPath());

        String[] capabilities = {"VLINK", "IDENTITY_LINK"};

        if (!cert.initialize("my-plugin", capabilities, 90)) {
            getLogger().severe("Certificate initialization failed. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Certificate loaded: " + cert.getCertificate().getCertificateId());
    }

    public void doVLinkWork() {
        if (!cert.validateCapability("VLINK")) {
            getLogger().warning("Missing VLINK capability.");
            return;
        }
        // proceed with VLink operation
    }
}
```

That is the entire integration. The rest of this page covers every class, method, and detail.

---

## Architecture

ValleyCert sits between your plugin and the rest of the ValleyAuth trust chain. It does not talk to the Certificate Authority directly. Instead, it talks to ValleyAuth Core, which proxies the request to ValleyCertAPI.

```
+-------------------+       +-------------------+       +-------------------+       +-------------------+
|                   |       |                   |       |                   |       |                   |
|   Your Plugin     | HTTP  |   ValleyCert      | HTTP  |  ValleyAuth Core  | HTTP  |  ValleyCertAPI    |
|                   |------>|   (this library)  |------>|  (Paper plugin)   |------>|  (CA service)     |
|                   |       |                   |       |                   |       |                   |
+-------------------+       +-------------------+       +-------------------+       +-------------------+
        |                           |                           |                           |
        |  1. Initialize            |  2. Check local cache     |                           |
        |                           |  3. Request cert          |  4. Forward to CA          |  5. Issue + sign
        |                           |  6. Validate cert         |  8. Return signed cert     |  7. ECDSA sign
        |                           |  9. Store locally         |                           |
        | 10. Check capability      |                           |                           |
        +---------------------------+---------------------------+---------------------------+
```

**What each layer does:**

| Layer | Role | Communication |
|---|---|---|
| Your Plugin | Calls ValleyCert to get and use a certificate | In-process method calls |
| ValleyCert | Requests, validates, stores, and renews certificates | HTTP to ValleyAuth Core |
| ValleyAuth Core | Proxies certificate requests to the CA, enforces trust on the server | HTTP to ValleyCertAPI |
| ValleyCertAPI | Issues, signs, revokes, and validates certificates (the CA) | Spark HTTP server |

**Key design decisions:**

- ValleyCert never contacts the CA directly. All traffic goes through ValleyAuth Core. This means ValleyAuth Core can validate plugin identity before forwarding requests.
- ValleyCert is a runtime dependency only. Your plugin declares it as `compileOnly` in Gradle. The server provides it at runtime.
- ValleyCert fails closed. If ValleyAuth is not installed, every method returns `false` or `null`. No operations proceed.

---

## Requirements

| Requirement | Version | Notes |
|---|---|---|
| Java | 21 or newer | Uses `java.net.http.HttpClient`, modern language features |
| Paper | 1.21+ | Depends on Paper API for plugin lifecycle |
| ValleyAuth | Installed on the server | ValleyCert checks for it at runtime via `Bukkit.getPluginManager().getPlugin("ValleyAuth")` |

ValleyCert does not bundle ValleyAuth or ValleyCertAPI. The server operator must install ValleyAuth separately. ValleyCertAPI runs outside Minecraft as a standalone service.

---

## Installation

### For Server Operators

1. Download `ValleyCert-0.2.0-alpha.jar` from the releases page.
2. Place it in your server's `plugins/` directory.
3. Ensure ValleyAuth is also installed in `plugins/`.
4. Restart the server.

ValleyCert is a library plugin. You will not interact with it directly as a server admin. Other plugins (VLink, migration tools, etc.) depend on it.

### For Plugin Developers

Add ValleyCert to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.valleyrealm:valleycert:0.2.0-alpha")
}
```

Use `compileOnly`, not `implementation`. ValleyCert is provided at runtime by the server. Bundling it into your JAR will cause classloader conflicts.

---

## API Reference

### ValleyCert (Main Entry Point)

**Package:** `com.valleyrealm.valleycert.ValleyCert`

The main class your plugin interacts with. It coordinates all certificate operations: requesting, validating, storing, and renewing.

#### Constructor

```java
public ValleyCert(Path pluginDataFolder)
```

| Parameter | Type | Description |
|---|---|---|
| `pluginDataFolder` | `Path` | Your plugin's data folder. Pass `getDataFolder().toPath()`. |

Creates a ValleyCert instance scoped to your plugin's data directory. This does not initialize anything. Call `initialize()` to request or load a certificate.

```java
ValleyCert cert = new ValleyCert(getDataFolder().toPath());
```

#### initialize()

```java
public boolean initialize(String pluginId, String[] capabilities, int requestedLifetimeDays)
```

| Parameter | Type | Description |
|---|---|---|
| `pluginId` | `String` | Unique identifier for your plugin. Use a consistent, lowercase, hyphenated string (e.g., `"my-plugin"`). |
| `capabilities` | `String[]` | Array of capability strings your plugin needs. |
| `requestedLifetimeDays` | `int` | Desired certificate lifetime in days. Capped at 120, minimum 1. |

**Returns:** `true` if initialization succeeded, `false` otherwise.

**Behavior:**

1. Checks that ValleyAuth is installed on the server. If not, prints a fatal error and returns `false`. All subsequent calls to any ValleyCert method will also return `false` or `null`.
2. Attempts to load an existing certificate from `<pluginDataFolder>/certs/<pluginId>/valley.cert`.
3. If a valid certificate is found, loads it into memory, schedules a background renewal check, and returns `true`.
4. If no valid certificate exists, sends an HTTP POST to ValleyAuth Core at `/api/certificate/issue` with the plugin ID, capabilities, and lifetime.
5. Validates the received certificate (ECDSA signature, issuer, expiry, revocation).
6. Stores the validated certificate locally.
7. Schedules a background renewal check.
8. Returns `true` on success, `false` on any failure.

```java
// Simple initialization
cert.initialize("my-plugin", new String[]{"VLINK"}, 90);

// Multiple capabilities
cert.initialize("my-network-plugin", new String[]{"VLINK", "IDENTITY_LINK", "RANK_SHARE"}, 90);
```

**Failure modes:**

- ValleyAuth not installed: returns `false`, disables all operations
- CA unreachable: returns `false`, logs the HTTP error
- Received certificate fails validation: returns `false`, logs the reason
- Invalid parameters (blank pluginId, empty capabilities): returns `false`

#### validateCapability()

```java
public boolean validateCapability(String capability)
```

| Parameter | Type | Description |
|---|---|---|
| `capability` | `String` | The capability name to check (e.g., `"VLINK"`). |

**Returns:** `true` if the current certificate is valid and grants the specified capability.

Checks that the certificate is loaded, valid (status, expiry, issuer, signature, revocation), and contains the requested capability string. Call this before every protected operation.

```java
if (cert.validateCapability("VLINK")) {
    // safe to perform VLink operations
} else {
    // certificate missing or lacks VLINK capability
}
```

#### getCertificate()

```java
public CertificateData getCertificate()
```

**Returns:** The current `CertificateData` object, or `null` if ValleyCert is disabled or no certificate is loaded.

Returns the full certificate data. The returned object is not a copy; `CertificateData` itself uses defensive copies on mutable fields (`Date`, `List<String>`), so the internal state is protected.

```java
CertificateData certificate = cert.getCertificate();
if (certificate != null) {
    String id = certificate.getCertificateId();
    Date expires = certificate.getExpirationDate();
    List<String> caps = certificate.getCapabilities();
}
```

#### isInitialized()

```java
public boolean isInitialized()
```

**Returns:** `true` if ValleyCert is enabled, a certificate is loaded, and that certificate passes all validation checks.

Use this to confirm your plugin is ready to perform protected operations.

```java
if (cert.isInitialized()) {
    // certificate exists and is valid
}
```

#### getCertificatePath()

```java
public Path getCertificatePath(String pluginId)
```

| Parameter | Type | Description |
|---|---|---|
| `pluginId` | `String` | The plugin ID to get the path for. |

**Returns:** The `Path` to the certificate file for the given plugin ID, or `null` if ValleyCert is disabled.

Returns `<pluginDataFolder>/certs/<pluginId>/valley.cert`.

```java
Path path = cert.getCertificatePath("my-plugin");
// /path/to/server/plugins/MyPlugin/certs/my-plugin/valley.cert
```

---

### CertificateData (Certificate Model)

**Package:** `com.valleyrealm.valleycert.CertificateData`

An immutable data class representing a ValleyAuth certificate. All `Date` and `List` fields use defensive copies in the constructor and getters to prevent external mutation.

#### Constructor

```java
public CertificateData(
    String pluginId,
    String certificateId,
    List<String> capabilities,
    Date issuanceDate,
    Date expirationDate,
    String issuer,
    String signature,
    String publicKey,
    CertificateStatus status,
    String encryptedRevocationTimestamp
)
```

| Parameter | Type | Description |
|---|---|---|
| `pluginId` | `String` | The plugin this certificate was issued to. |
| `certificateId` | `String` | Unique identifier for this certificate (UUID format). |
| `capabilities` | `List<String>` | Capabilities granted by this certificate. Stored as an immutable copy. |
| `issuanceDate` | `Date` | When the certificate was issued. Stored as a defensive copy. |
| `expirationDate` | `Date` | When the certificate expires. Stored as a defensive copy. |
| `issuer` | `String` | The issuer. Must be `"ValleyAuth Core"` for valid certificates. |
| `signature` | `String` | Base64-encoded ECDSA signature. |
| `publicKey` | `String` | Base64-encoded ECDSA public key. |
| `status` | `CertificateStatus` | The certificate status (ACTIVE, EXPIRED, REVOKED, SUSPENDED). |
| `encryptedRevocationTimestamp` | `String` | AES-GCM encrypted revocation timestamp, or `null` if not revoked. |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `isCurrentlyValid()` | `boolean` | Returns `true` if status is ACTIVE and current time is between issuance and expiration. |
| `isExpired()` | `boolean` | Returns `true` if current time is past the expiration date. |
| `needsRenewal()` | `boolean` | Returns `true` if the certificate has 7 or fewer days until expiry. |
| `getPluginId()` | `String` | Returns the plugin ID. |
| `getCertificateId()` | `String` | Returns the certificate ID. |
| `getCapabilities()` | `List<String>` | Returns the capabilities list (immutable). |
| `getIssuanceDate()` | `Date` | Returns a defensive copy of the issuance date. |
| `getExpirationDate()` | `Date` | Returns a defensive copy of the expiration date. |
| `getIssuer()` | `String` | Returns the issuer string. |
| `getSignature()` | `String` | Returns the Base64-encoded signature. |
| `getPublicKey()` | `String` | Returns the Base64-encoded public key. |
| `getStatus()` | `CertificateStatus` | Returns the certificate status. |
| `getEncryptedRevocationTimestamp()` | `String` | Returns the encrypted revocation timestamp, or `null`. |

#### CertificateStatus Enum

```java
public enum CertificateStatus {
    ACTIVE,
    EXPIRED,
    REVOKED,
    SUSPENDED
}
```

| Status | Meaning |
|---|---|
| `ACTIVE` | Certificate is valid and operational. |
| `EXPIRED` | Certificate has passed its expiration date. |
| `REVOKED` | Certificate was revoked by the CA. |
| `SUSPENDED` | Certificate is temporarily suspended. |

---

### CertificateRequestResult (Request Result)

**Package:** `com.valleyrealm.valleycert.CertificateRequestResult`

A result wrapper for certificate request operations. Uses a factory pattern to create success or failure results.

#### Factory Methods

```java
public static CertificateRequestResult success(CertificateData certificate)
public static CertificateRequestResult failure(String errorMessage)
```

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `isSuccess()` | `boolean` | Whether the request succeeded. |
| `getCertificate()` | `CertificateData` | The certificate on success, `null` on failure. |
| `getErrorMessage()` | `String` | The error message on failure, `null` on success. |

```java
CertificateRequestResult result = requester.requestCertificate("my-plugin", caps, 90);
if (result.isSuccess()) {
    CertificateData cert = result.getCertificate();
} else {
    String error = result.getErrorMessage();
}
```

---

### CertificateRequester (HTTP Client)

**Package:** `com.valleyrealm.valleycert.request.CertificateRequester`

Handles HTTP communication with ValleyAuth Core to request and renew certificates. This class never talks to the CA directly.

#### Constructor

```java
public CertificateRequester()
public CertificateRequester(String apiUrl)
```

| Constructor | Description |
|---|---|
| `CertificateRequester()` | Creates a requester with no API URL. Falls back to mock certificates. |
| `CertificateRequester(String apiUrl)` | Creates a requester pointed at a ValleyAuth Core endpoint. |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `setApiUrl(String apiUrl)` | `void` | Sets or updates the ValleyAuth Core endpoint URL. |
| `requestCertificate(String pluginId, String[] capabilities, int requestedLifetimeDays)` | `CertificateRequestResult` | Requests a new certificate from ValleyAuth Core. |
| `requestRenewal(String pluginId, CertificateData oldCert)` | `CertificateRequestResult` | Requests renewal of an existing certificate. |

#### requestCertificate()

```java
public CertificateRequestResult requestCertificate(String pluginId, String[] capabilities, int requestedLifetimeDays)
```

Sends an HTTP POST to `<apiUrl>/api/certificate/issue` with the following JSON body:

```json
{
    "pluginId": "my-plugin",
    "capabilities": ["VLINK", "IDENTITY_LINK"],
    "requestedValidityDays": 90
}
```

The lifetime is clamped to a minimum of 1 day and a maximum of 120 days. If no API URL is configured, returns a mock certificate instead.

**Timeout:** 10 seconds for both connection and response.

#### requestRenewal()

```java
public CertificateRequestResult requestRenewal(String pluginId, CertificateData oldCert)
```

Sends an HTTP POST to `<apiUrl>/api/certificate/renew` with:

```json
{
    "certificateId": "uuid-of-old-cert",
    "validityDays": 90
}
```

The old certificate's capabilities are preserved in the renewal. If no API URL is configured, falls back to requesting a brand new certificate with the old certificate's capabilities.

#### Mock Certificates

When `apiUrl` is `null` or blank, `requestCertificate()` returns a mock certificate:

- Certificate ID: random UUID
- Capabilities: whatever was requested
- Issuer: `"ValleyAuth Core"`
- Signature: `"mock-signature-<uuid>"`
- Public Key: `"mock-public-key"`
- Status: ACTIVE
- Lifetime: as requested

Mock certificates pass ValleyCert's local validation (the validator detects `mock-signature` and skips ECDSA verification). They are not accepted by production ValleyAuth APIs that verify signatures server-side.

---

### CertificateStorage (File Storage)

**Package:** `com.valleyrealm.valleycert.storage.CertificateStorage`

Manages certificate persistence on the local filesystem. Uses atomic writes to prevent partial corruption and keeps backups of previous certificates.

#### Constructor

```java
public CertificateStorage(Path pluginDataFolder)
```

Creates a storage directory at `<pluginDataFolder>/certs/` and initializes the JSON serializer.

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `loadCertificate(String pluginId)` | `CertificateData` | Loads and deserializes a certificate from disk. Returns `null` if not found or malformed. |
| `saveCertificate(String pluginId, CertificateData certificate)` | `void` | Saves a certificate using atomic writes (temp file, backup, atomic move). |
| `deleteCertificate(String pluginId)` | `boolean` | Deletes the certificate and its backup. Returns `true` on success. |
| `hasCertificate(String pluginId)` | `boolean` | Checks whether a certificate file exists for the plugin. |
| `getCertificatePath(String pluginId)` | `Path` | Returns the full path to the certificate file. |

#### Atomic Write Process

When saving a certificate, `CertificateStorage` follows these steps:

1. Serialize the certificate to JSON.
2. Write the JSON to a temporary file (`valley.cert.tmp`).
3. Move the existing certificate file (if any) to a backup (`valley.cert.backup`).
4. Atomically move the temporary file to the final location (`valley.cert`).
5. If any step fails, clean up the temporary file and log the error.

This prevents partial writes from corrupting your certificate. If the server crashes mid-write, the old certificate (or backup) remains intact.

#### Certificate File Location

```
<your-plugin-data-folder>/
    certs/
        <pluginId>/
            valley.cert           # current certificate (JSON)
            valley.cert.backup    # previous certificate (if renewed)
```

#### JSON Format

The certificate file is pretty-printed JSON:

```json
{
  "pluginId": "my-plugin",
  "certificateId": "550e8400-e29b-41d4-a716-446655440000",
  "capabilities": ["VLINK", "IDENTITY_LINK"],
  "issuanceDate": 1700000000000,
  "expirationDate": 1707776000000,
  "issuer": "ValleyAuth Core",
  "signature": "MEUCIQD...",
  "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
  "status": "ACTIVE",
  "encryptedRevocationTimestamp": null
}
```

---

### CertificateValidator (Validation)

**Package:** `com.valleyrealm.valleycert.validation.CertificateValidator`

Performs all validation checks on certificates: status, expiry, issuer, ECDSA signature, and revocation.

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `isValid(CertificateData certificate)` | `boolean` | Runs all validation checks. Returns `true` only if every check passes. |
| `hasCapability(CertificateData certificate, String capability)` | `boolean` | Checks if a valid certificate has a specific capability. |
| `hasAllCapabilities(CertificateData certificate, String[] capabilities)` | `boolean` | Checks if a valid certificate has all specified capabilities. |
| `getMillisUntilExpiry(CertificateData certificate)` | `long` | Returns milliseconds until the certificate expires. |
| `needsRenewal(CertificateData certificate)` | `boolean` | Returns `true` if the certificate has fewer than 7 days until expiry. |

#### isValid() Checks

The `isValid()` method runs these checks in order. Any failure causes an immediate `return false`:

1. **Null check:** Certificate must not be null.
2. **Status check:** Must be `ACTIVE`. Certificates with status `EXPIRED`, `REVOKED`, or `SUSPENDED` are rejected.
3. **Issuance check:** Current time must not be before the issuance date.
4. **Expiry check:** Current time must not be after the expiration date.
5. **Issuer check:** Must be exactly `"ValleyAuth Core"`.
6. **Signature check:** ECDSA signature verification using the embedded public key.
7. **Revocation check:** The `encryptedRevocationTimestamp` field must be null. Any non-null value means the certificate was revoked.

#### Signature Verification

ValleyCert uses ECDSA with the `SHA256withECDSA` algorithm and the `EC` key algorithm (P-256 curve).

The signed data is a concatenation of these fields (no delimiters):

```
certificateId + pluginId + capabilities + issuanceDate.getTime() + expirationDate.getTime() + issuer
```

This must match the CA's `toBytesWithoutSignature()` format exactly.

Mock certificates (detected by the `"mock-signature"` prefix in the signature field) skip ECDSA verification but still require all other checks to pass.

#### hasCapability() and hasAllCapabilities()

```java
// Check for a single capability
boolean canDoVLink = validator.hasCapability(certificate, "VLINK");

// Check for all capabilities
boolean hasAll = validator.hasAllCapabilities(certificate, 
    new String[]{"VLINK", "IDENTITY_LINK"});
```

Both methods call `isValid()` internally. An invalid certificate always returns `false` regardless of its capability list.

---

### CertificateRenewer (Auto-Renewal)

**Package:** `com.valleyrealm.valleycert.renewal.CertificateRenewer`

Manages automatic certificate renewal on a background scheduler.

#### Constructor

```java
public CertificateRenewer(Object valleyCertInstance)
```

Creates a renewer with a single-threaded `ScheduledExecutorService` running on a daemon thread named `"ValleyCert-Renewer"`.

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `scheduleRenewalCheck(CertificateData certificate, BiConsumer<CertificateData, CertificateData> onRenewal)` | `void` | Starts periodic renewal checks for the given certificate. |
| `shutdown()` | `void` | Stops the renewal scheduler. |

#### Renewal Parameters

| Parameter | Value |
|---|---|
| Check interval | Every 1 hour (3,600,000 ms) |
| Renewal threshold | 7 days before expiry (604,800,000 ms) |
| Thread type | Daemon thread (will not prevent JVM shutdown) |

#### Renewal Flow

1. The scheduler runs every hour.
2. On each tick, it checks if the certificate is still valid. If not, it stops.
3. It checks if the certificate needs renewal (fewer than 7 days until expiry). If not, it returns.
4. It sends a renewal request to ValleyAuth Core via `CertificateRequester.requestRenewal()`.
5. If the new certificate is valid, it calls the `onRenewal` callback with both the old and new certificates.
6. The callback (implemented in `ValleyCert.handleRenewal()`) validates the new cert, saves it to storage, and updates the in-memory certificate.
7. If renewal fails, the old certificate remains in use until it expires.

#### Callback Signature

```java
BiConsumer<CertificateData, CertificateData> onRenewal = (oldCert, newCert) -> {
    // oldCert: the certificate being replaced
    // newCert: the newly issued certificate
};
```

The old certificate is only replaced after the new one has been validated and stored. This ensures there is always a valid certificate available, even if the renewal process encounters an error.

---

## Capabilities Reference

Capabilities are string identifiers that grant specific permissions to your plugin. Each certificate carries a list of capabilities that were requested at issuance time. Capabilities cannot be added after issuance; you must request a new certificate.

| Capability | Constant | Description | Use Case |
|---|---|---|---|
| `VLINK` | `Capability.VLINK` | Allows VLink operations for cross-server identity linking | Cross-server data synchronization, player identity mapping |
| `IDENTITY_LINK` | `Capability.IDENTITY_LINK` | Allows linking player identities across services | Mapping player accounts between platforms or servers |
| `RANK_SHARE` | `Capability.RANK_SHARE` | Allows sharing rank data between linked identities | Rank synchronization across a server network |
| `MIGRATION_PROVIDER` | `Capability.MIGRATION_PROVIDER` | Allows registering as a data migration provider | Plugins that offer to migrate data for other plugins |
| `MIGRATION_ACCESS` | `Capability.MIGRATION_ACCESS` | Allows accessing protected migration APIs | Plugins that consume migration data from providers |
| `CERTIFICATE_MANAGEMENT` | `Capability.CERTIFICATE_MANAGEMENT` | Allows certificate management operations (issue, revoke, renew) | Administrative tools that manage certificate lifecycles |

**Least privilege:** Possessing one capability does not grant another. Request only what your plugin needs.

**Capability validation:**

```java
// Single capability check
if (cert.validateCapability("VLINK")) {
    // proceed
}

// Multiple capability check
String[] required = {"VLINK", "MIGRATION_ACCESS"};
CertificateData certData = cert.getCertificate();
CertificateValidator validator = new CertificateValidator();
if (validator.hasAllCapabilities(certData, required)) {
    // all capabilities present
}
```

---

## Certificate Flow

Here is exactly what happens when your plugin calls `cert.initialize("my-plugin", new String[]{"VLINK"}, 90)`:

```
Step 1: Plugin calls ValleyCert.initialize()
        |
        v
Step 2: ValleyCert checks for ValleyAuth
        +-- ValleyAuth not found --> disable, return false
        +-- ValleyAuth found -----> continue
        |
        v
Step 3: ValleyCert checks local storage
        +-- Valid cert found -----> load into memory, schedule renewal, return true
        +-- No valid cert --------> continue
        |
        v
Step 4: CertificateRequester sends HTTP POST to ValleyAuth Core
        POST /api/certificate/issue
        Body: {"pluginId":"my-plugin","capabilities":["VLINK"],"requestedValidityDays":90}
        |
        v
Step 5: ValleyAuth Core forwards to ValleyCertAPI (the CA)
        |
        v
Step 6: ValleyCertAPI issues and signs the certificate
        - Generates ECDSA P-256 key pair
        - Signs the certificate data
        - Encrypts a revocation timestamp (initially null)
        - Stores the certificate
        |
        v
Step 7: Signed certificate returns to ValleyCert
        |
        v
Step 8: CertificateValidator validates the received certificate
        - Status must be ACTIVE
        - Not expired
        - Issuer must be "ValleyAuth Core"
        - ECDSA signature must verify
        - Encrypted revocation timestamp must be null
        |
        v
Step 9: CertificateStorage saves to disk
        - Write to valley.cert.tmp
        - Backup existing valley.cert
        - Atomic move to valley.cert
        |
        v
Step 10: CertificateRenewer schedules hourly checks
        |
        v
Step 11: Plugin calls validateCapability("VLINK") --> true
```

---

## Storage Layout

ValleyCert stores certificates in your plugin's data directory:

```
plugins/
    YourPlugin/
        certs/
            my-plugin/
                valley.cert            # current certificate
                valley.cert.backup     # previous certificate (after renewal)
            my-other-plugin/
                valley.cert
```

Each plugin gets its own subdirectory under `certs/`, named by the `pluginId` you pass to `initialize()`. The certificate file is pretty-printed JSON, safe to inspect manually.

---

## Security Model

ValleyCert validates every certificate against five checks. All must pass.

### Check 1: Status

The certificate status must be `ACTIVE`. The four possible statuses are:

| Status | Behavior |
|---|---|
| ACTIVE | Passes this check |
| EXPIRED | Fails |
| REVOKED | Fails |
| SUSPENDED | Fails |

### Check 2: Time Window

The current time must fall between the issuance date and the expiration date. A certificate used before its issuance date or after its expiration date is rejected.

### Check 3: Issuer

The issuer field must be exactly `"ValleyAuth Core"`. Any other value is rejected. This prevents certificates from unknown or untrusted sources from being used.

### Check 4: ECDSA Signature

The certificate signature is verified using the embedded public key. The algorithm is `SHA256withECDSA` on the `EC` (P-256) key type.

The signed data is a concatenation of:

```
certificateId + pluginId + capabilities + issuanceDate.getTime() + expirationDate.getTime() + issuer
```

Mock certificates (signature starts with `"mock-signature"`) skip this check but still require all other checks to pass.

### Check 5: Revocation

ValleyCert uses a fail-closed revocation model. The certificate carries an `encryptedRevocationTimestamp` field that is AES-GCM encrypted by the CA. The client cannot decrypt this field without the CA's private key.

If the field is `null`, the certificate is not revoked. If the field is non-null (any string value), the certificate is considered revoked and all operations fail immediately.

This design means:

- The CA can revoke a certificate by populating the encrypted field.
- The client does not need to contact the CA to check revocation status.
- Any tampering or corruption that sets this field will cause rejection.

### Mock Certificate Handling

Mock certificates are detected by their signature prefix (`"mock-signature-"`). The validator skips ECDSA verification for mock certificates but enforces all other checks (status, time window, issuer, revocation).

---

## Offline Mode

When ValleyCert cannot reach the CA, it falls back to mock certificates. This happens when:

- No API URL is configured
- ValleyAuth Core is unreachable
- The HTTP request times out (10-second timeout)

Mock certificates:

- Have a random UUID as their certificate ID
- Contain the capabilities you requested
- Are signed with a mock signature (bypasses ECDSA verification)
- Have the issuer set to `"ValleyAuth Core"`
- Are valid for the lifetime you requested
- Have no encrypted revocation timestamp

Mock certificates are useful for local development and testing. They are not accepted by production ValleyAuth APIs that verify signatures on the server side.

```java
// ValleyCert automatically uses mock mode when ValleyAuth Core is unreachable
ValleyCert cert = new ValleyCert(getDataFolder().toPath());
cert.initialize("dev-plugin", new String[]{"VLINK"}, 90);
// Works offline with a mock certificate
```

---

## Revocation

Certificate revocation in the ValleyAuth ecosystem uses encrypted timestamps, not Certificate Revocation Lists (CRL) or Online Certificate Status Protocol (OCSP).

### How It Works

1. When the CA issues a certificate, the `encryptedRevocationTimestamp` field is `null`.
2. To revoke a certificate, the CA encrypts the revocation timestamp using AES-256-GCM and stores it in the field.
3. The client checks the field on every validation. If it is non-null, the certificate is rejected.
4. The client cannot decrypt the timestamp. Only the CA's private key can decrypt it.

### Where Revocation Is Checked

- During `initialize()` when loading a cached certificate
- During `validateCapability()` on every call
- During `isInitialized()` on every call
- During the background renewal check before attempting renewal

### What Happens on Revocation

When a certificate is revoked:

- `isValid()` returns `false`
- `validateCapability()` returns `false`
- `isInitialized()` returns `false`
- The certificate is effectively dead. Your plugin should handle this gracefully.

---

## Renewal

ValleyCert automatically renews certificates before they expire.

### Parameters

| Parameter | Value |
|---|---|
| Check interval | Every 1 hour |
| Renewal window | 7 days before expiry |
| Renewal lifetime | 90 days (renewed certificate gets a fresh 90-day lifetime) |
| Thread | Daemon thread named `"ValleyCert-Renewer"` |

### How Renewal Works

1. A `ScheduledExecutorService` checks the certificate every hour.
2. If the certificate has 7 or fewer days until expiry, renewal is triggered.
3. An HTTP POST is sent to ValleyAuth Core at `/api/certificate/renew` with the current certificate ID.
4. The CA revokes the old certificate and issues a new one with the same capabilities.
5. The new certificate is validated (same five checks as initial issuance).
6. The old certificate file is backed up (`.backup` suffix).
7. The new certificate is atomically written to disk.
8. The in-memory certificate is updated.

### What Happens If Renewal Fails

If the CA is unreachable or the renewal fails for any reason, the old certificate remains in use. The scheduler will try again on the next hourly check. If the certificate expires before a successful renewal, all operations will fail.

### Shutdown

```java
cert.renewer.shutdown();
```

The renewer's `shutdown()` method calls `shutdownNow()` on the scheduled executor, immediately stopping all pending tasks.

---

## Integration Guide

### Step 1: Add the Dependency

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.valleyrealm:valleycert:0.2.0-alpha")
}
```

### Step 2: Initialize in onEnable()

```java
@Override
public void onEnable() {
    cert = new ValleyCert(getDataFolder().toPath());

    String[] capabilities = {"VLINK", "IDENTITY_LINK"};

    if (!cert.initialize("my-plugin", capabilities, 90)) {
        getLogger().severe("Certificate initialization failed. Disabling plugin.");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }

    getLogger().info("ValleyCert initialized. Certificate ID: " 
        + cert.getCertificate().getCertificateId());
}
```

### Step 3: Gate Operations Behind Capabilities

```java
public void performVLinkAction() {
    if (!cert.validateCapability("VLINK")) {
        getLogger().warning("Missing VLINK capability. Skipping operation.");
        return;
    }

    // safe to perform VLink operation
    CertificateData certData = cert.getCertificate();
    // present certData to protected APIs
}
```

### Step 4: Inspect Certificate Details

```java
CertificateData certData = cert.getCertificate();
if (certData != null) {
    getLogger().info("Certificate ID: " + certData.getCertificateId());
    getLogger().info("Issuer: " + certData.getIssuer());
    getLogger().info("Expires: " + certData.getExpirationDate());
    getLogger().info("Capabilities: " + String.join(", ", certData.getCapabilities()));
    getLogger().info("Status: " + certData.getStatus());
}
```

### Step 5: Check Initialization Status

```java
// Check at any point
if (cert.isInitialized()) {
    // certificate is loaded and valid
} else {
    // certificate is missing, expired, or revoked
}
```

### Full Example

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

    public void checkCertificateHealth() {
        CertificateData certData = cert.getCertificate();
        if (certData == null) {
            getLogger().warning("No certificate loaded.");
            return;
        }

        long millisUntilExpiry = certData.getExpirationDate().getTime() - System.currentTimeMillis();
        long daysUntilExpiry = millisUntilExpiry / (1000 * 60 * 60 * 24);
        getLogger().info("Certificate expires in " + daysUntilExpiry + " days.");

        if (certData.needsRenewal()) {
            getLogger().info("Certificate is within the 7-day renewal window.");
        }
    }
}
```

---

## Troubleshooting

### ValleyCert Fails to Initialize

**Symptom:** `cert.initialize()` returns `false`. Server logs show `[ValleyCert] FATAL: ValleyAuth plugin not found.`

**Cause:** ValleyAuth is not installed or not loaded.

**Fix:** Install ValleyAuth in your server's `plugins/` directory and restart. ValleyAuth must be present for ValleyCert to function.

---

### Certificate Request Fails

**Symptom:** `cert.initialize()` returns `false`. Logs show `[ValleyCert] Failed to obtain certificate: Connection failed: ...`

**Cause:** ValleyAuth Core is unreachable, or the CA endpoint is misconfigured.

**Fix:**
1. Verify ValleyAuth Core is running on the server.
2. Check ValleyAuth's `config.yml` for the `certificate.api-url` setting.
3. Ensure the CA (ValleyCertAPI) is running and accessible at that URL.
4. Check firewall rules if the CA is on a different machine.

---

### Certificate Is Not Valid

**Symptom:** `cert.isInitialized()` returns `false` even though a certificate file exists.

**Cause:** One or more validation checks are failing.

**Fix:**
1. Check the certificate's expiration date: `cert.getCertificate().getExpirationDate()`.
2. Verify the issuer is `"ValleyAuth Core"`.
3. Look for revocation: if `getEncryptedRevocationTimestamp()` is non-null, the certificate was revoked.
4. Check logs for `[ValleyCert Validator]` messages to identify the specific failure.

---

### Capabilities Are Missing

**Symptom:** `cert.validateCapability("VLINK")` returns `false` even though you requested VLINK.

**Cause:** Capabilities are set at issuance and cannot be changed.

**Fix:**
1. Delete the cached certificate file at `<plugin-data>/certs/<pluginId>/valley.cert`.
2. Restart your plugin or server.
3. Reinitialize with the correct capabilities.

---

### Renewal Is Not Happening

**Symptom:** The certificate expires without being renewed.

**Cause:** The renewal scheduler may have stopped, or the CA is unreachable during renewal.

**Fix:**
1. Check logs for `[ValleyCert Renewer]` messages.
2. Renewal only triggers within 7 days of expiry. If the certificate has more than 7 days left, renewal will not happen.
3. If the CA is unreachable, the scheduler retries every hour. The old certificate stays valid until it expires.
4. Ensure the renewal thread is not being interrupted. It runs as a daemon thread.

---

### Mock Certificate in Production

**Symptom:** Certificates are being issued with `"mock-signature"` and not accepted by production APIs.

**Cause:** No API URL is configured, or ValleyAuth Core's certificate endpoint is not set.

**Fix:**
1. Check ValleyAuth Core's `config.yml` for `certificate.api-url`.
2. Ensure ValleyCertAPI is running at the configured URL.
3. Delete any existing mock certificates before reinitializing.

---

### Server Logs Flooded with ValleyCert Messages

**Symptom:** Repeated `[ValleyCert] WARNING: ... called but ValleyCert is disabled` messages.

**Cause:** A plugin is calling ValleyCert methods repeatedly after ValleyAuth was not found.

**Fix:** ValleyCert only prints the first disabled warning per method. If you see many, it means multiple different methods are being called. Check your plugin's code to ensure you are not calling ValleyCert methods when `isInitialized()` returns `false`.

---

### Atomic Write Failed

**Symptom:** `[ValleyCert Storage] Error saving certificate` in logs.

**Cause:** Filesystem permissions issue or disk full.

**Fix:**
1. Ensure the server process has write permissions to the plugin's data directory.
2. Check available disk space.
3. The old certificate (or backup) should still be intact if the atomic write failed partway through.

---

## FAQ

### Is ValleyCert a certificate authority?

No. ValleyCert is a client library. It requests certificates from ValleyAuth Core, which proxies to ValleyCertAPI (the actual CA). ValleyCert never issues certificates itself.

---

### Can I use ValleyCert without ValleyAuth?

No. ValleyCert checks for ValleyAuth at initialization. If ValleyAuth is not installed, ValleyCert disables itself entirely. Every method returns `false` or `null`.

---

### Can I use ValleyCert for SSL/TLS certificates?

No. ValleyCert only handles ValleyAuth certificates. It does not issue, validate, or manage SSL/TLS, X.509, or any other certificate type.

---

### What happens if the CA goes down after I have a certificate?

Your existing certificate remains valid until it expires. ValleyCert stores certificates locally and validates them without contacting the CA. The only time ValleyCert needs the CA is for initial issuance and renewal.

---

### Can I request capabilities I do not need?

You can, but you should not. Capabilities follow the principle of least privilege. Request only what your plugin actually uses. Extra capabilities increase your attack surface if the certificate is compromised.

---

### How long do certificates last?

You request a lifetime (default 90 days, maximum 120 days). The CA issues the certificate with that lifetime. Renewal happens automatically within 7 days of expiry, giving you a fresh 90-day certificate.

---

### Can I manually renew a certificate?

Not through ValleyCert's API. The renewer runs automatically. If you need to force a renewal, delete the cached certificate file and restart your plugin. ValleyCert will request a new certificate from the CA.

---

### What is the difference between EXPIRED and REVOKED?

An expired certificate has passed its natural expiration date. A revoked certificate was actively invalidated by the CA before its expiry. Both are treated as invalid, but revocation is immediate and intentional while expiry is expected and scheduled.

---

### Can multiple plugins share a certificate?

No. Each plugin gets its own certificate, scoped by `pluginId`. The `pluginId` you pass to `initialize()` determines the storage path and the certificate identity.

---

### Does ValleyCert work in offline mode servers?

Yes, but only with mock certificates. If the CA is unreachable, ValleyCert falls back to mock certificates that bypass ECDSA verification. These are useful for development but not for production.

---

### How do I know if my certificate is a mock certificate?

Check the signature field: `cert.getCertificate().getSignature()`. If it starts with `"mock-signature"`, it is a mock. You can also check by attempting to contact a production API, which will reject mock-signed certificates.

---

### Can I use ValleyCert in a BungeeCord or Velocity proxy?

ValleyCert depends on Paper API (Bukkit). It is designed for Paper servers. It will not work on BungeeCord or Velocity without significant modification.

---

### What happens if I call initialize() twice?

The second call will attempt to load the existing certificate (which was stored by the first call). If it is still valid, it loads it and returns `true`. If it is expired or invalid, it requests a new one.

---

## Building from Source

### Prerequisites

- Java 21 or newer
- Gradle 8.x (or use the included Gradle wrapper)

### Build

```bash
git clone https://github.com/SabeeirSharrma/ValleyCert.git
cd ValleyCert
./gradlew build
```

The output JAR lands in `build/libs/ValleyCert-0.2.0-alpha.jar`.

### Publish to Maven Local

```bash
./gradlew publishToMavenLocal
```

This publishes ValleyCert to your local Maven repository at `~/.m2/repository/com/valleyrealm/valleycert/`. Other projects can then depend on it using `compileOnly("com.valleyrealm:valleycert:0.2.0-alpha")`.

---

## Project Structure

```
ValleyCert/
    build.gradle.kts                          # Build config (Java 21, Paper API, Gson, BouncyCastle)
    src/main/java/com/valleyrealm/valleycert/
        ValleyCert.java                       # Main entry point. Initialize, validate, get cert.
        CertificateData.java                  # Immutable certificate model with defensive copies.
        CertificateRequestResult.java         # Success/failure wrapper for request operations.
        request/
            CertificateRequester.java         # HTTP client for ValleyAuth Core communication.
        storage/
            CertificateStorage.java           # File-based storage with atomic writes and backups.
        validation/
            CertificateValidator.java         # ECDSA signature verification and all validation checks.
        renewal/
            CertificateRenewer.java           # Scheduled background renewal on a daemon thread.
```

### Dependencies

| Dependency | Version | Scope | Purpose |
|---|---|---|---|
| Paper API | 1.21.4-R0.1-SNAPSHOT | `compileOnly` | Plugin lifecycle, Bukkit API |
| Gson | 2.10.1 | `implementation` | JSON serialization for certificates |
| BouncyCastle | 1.77 | `implementation` | Cryptographic provider for ECDSA |

---

## License

MIT

---

*ValleyCert v0.2.0-alpha. Part of the ValleyRealm ecosystem.*
