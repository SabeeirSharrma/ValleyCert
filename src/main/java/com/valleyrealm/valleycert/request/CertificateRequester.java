package com.valleyrealm.valleycert.request;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.valleyrealm.valleycert.CertificateData;
import com.valleyrealm.valleycert.CertificateRequestResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Handles certificate requests to ValleyAuth Core.
 *
 * Flow:
 * 1. Plugin -> ValleyCert -> request certificate
 * 2. ValleyCert -> ValleyAuth Core -> request certificate
 * 3. ValleyAuth Core -> ValleyCertAPI (CA) -> issue certificate
 * 4. ValleyAuth Core -> ValleyCert -> return certificate
 */
public class CertificateRequester {

    private static final int DEFAULT_LIFETIME_DAYS = 90;
    private static final int MAX_LIFETIME_DAYS = 120;
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    private final Gson gson;
    private final HttpClient httpClient;
    private String apiUrl;

    public CertificateRequester() {
        this.gson = new GsonBuilder().create();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .build();
    }

    public CertificateRequester(String apiUrl) {
        this();
        this.apiUrl = apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public CertificateRequestResult requestCertificate(String pluginId, String[] capabilities, int requestedLifetimeDays) {
        int lifetimeDays = Math.min(requestedLifetimeDays, MAX_LIFETIME_DAYS);
        lifetimeDays = Math.max(lifetimeDays, 1);

        if (apiUrl == null || apiUrl.isBlank()) {
            System.out.println("[ValleyCert] No API URL configured, using mock certificate.");
            return CertificateRequestResult.success(createMockCertificate(pluginId, capabilities, lifetimeDays));
        }

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("pluginId", pluginId);
            requestBody.add("capabilities", gson.toJsonTree(List.of(capabilities)));
            requestBody.addProperty("requestedValidityDays", lifetimeDays);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/certificate/issue"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .timeout(java.time.Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                if (respBody.get("success").getAsBoolean()) {
                    JsonObject certJson = respBody.getAsJsonObject("certificate");
                    CertificateData cert = parseCertificate(certJson);
                    return CertificateRequestResult.success(cert);
                } else {
                    return CertificateRequestResult.failure(respBody.get("message").getAsString());
                }
            } else {
                return CertificateRequestResult.failure("HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[ValleyCert] API request failed: " + e.getMessage());
            return CertificateRequestResult.failure("Connection failed: " + e.getMessage());
        }
    }

    public CertificateRequestResult requestRenewal(String pluginId, CertificateData oldCert) {
        System.out.println("[ValleyCert] Requesting certificate renewal for: " + pluginId);

        if (apiUrl == null || apiUrl.isBlank()) {
            return requestCertificate(pluginId,
                oldCert.getCapabilities().toArray(new String[0]),
                DEFAULT_LIFETIME_DAYS);
        }

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("certificateId", oldCert.getCertificateId());
            requestBody.addProperty("validityDays", DEFAULT_LIFETIME_DAYS);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/certificate/renew"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .timeout(java.time.Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                if (respBody.get("success").getAsBoolean()) {
                    JsonObject certJson = respBody.getAsJsonObject("certificate");
                    CertificateData cert = parseCertificate(certJson);
                    return CertificateRequestResult.success(cert);
                } else {
                    return CertificateRequestResult.failure(respBody.get("message").getAsString());
                }
            } else {
                return CertificateRequestResult.failure("HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            return CertificateRequestResult.failure("Renewal failed: " + e.getMessage());
        }
    }

    private CertificateData parseCertificate(JsonObject certJson) {
        String pluginId = certJson.get("pluginId").getAsString();
        String certificateId = certJson.get("certificateId").getAsString();
        List<String> capabilities = gson.fromJson(certJson.get("capabilities"), 
            new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
        Date issuanceDate = new Date(certJson.get("issuanceDate").getAsLong());
        Date expirationDate = new Date(certJson.get("expirationDate").getAsLong());
        String issuer = certJson.get("issuer").getAsString();
        String signature = certJson.has("signature") ? certJson.get("signature").getAsString() : "";
        String status = certJson.has("status") ? certJson.get("status").getAsString() : "ACTIVE";

        CertificateData.CertificateStatus certStatus = CertificateData.CertificateStatus.valueOf(status);
        String encryptedRevocationTimestamp = certJson.has("encryptedRevocationTimestamp") ?
            certJson.get("encryptedRevocationTimestamp").getAsString() : null;

        return new CertificateData(pluginId, certificateId, capabilities, issuanceDate, expirationDate,
            issuer, signature, "", certStatus, encryptedRevocationTimestamp);
    }

    private CertificateData createMockCertificate(String pluginId, String[] capabilities, int lifetimeDays) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + (long) lifetimeDays * 24 * 60 * 60 * 1000);

        return new CertificateData(
            pluginId,
            UUID.randomUUID().toString(),
            List.of(capabilities),
            now,
            expiry,
            "ValleyAuth Core",
            "mock-signature-" + UUID.randomUUID(),
            "mock-public-key",
            CertificateData.CertificateStatus.ACTIVE,
            null
        );
    }
}
