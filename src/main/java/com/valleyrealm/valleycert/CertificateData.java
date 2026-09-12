package com.valleyrealm.valleycert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Represents a Valley Auth certificate.
 * 
 * Contains all information needed to verify a plugin's authorization.
 */
public class CertificateData {

    private final String pluginId;
    private final String certificateId;
    private final List<String> capabilities;
    private final Date issuanceDate;
    private final Date expirationDate;
    private final String issuer;
    private final String signature;
    private final String publicKey;
    private final CertificateStatus status;
    private final String encryptedRevocationTimestamp;

    public CertificateData(String pluginId, String certificateId, List<String> capabilities,
                          Date issuanceDate, Date expirationDate, String issuer,
                          String signature, String publicKey, CertificateStatus status,
                          String encryptedRevocationTimestamp) {
        this.pluginId = pluginId;
        this.certificateId = certificateId;
        this.capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        this.issuanceDate = issuanceDate != null ? new Date(issuanceDate.getTime()) : new Date();
        this.expirationDate = expirationDate != null ? new Date(expirationDate.getTime()) : new Date();
        this.issuer = issuer;
        this.signature = signature;
        this.publicKey = publicKey;
        this.status = status;
        this.encryptedRevocationTimestamp = encryptedRevocationTimestamp;
    }

    /**
     * Check if this certificate is currently valid.
     */
    public boolean isCurrentlyValid() {
        Date now = new Date();
        return status == CertificateStatus.ACTIVE &&
               !now.before(issuanceDate) &&
               !now.after(expirationDate);
    }

    /**
     * Check if this certificate is expired.
     */
    public boolean isExpired() {
        return new Date().after(expirationDate);
    }

    /**
     * Check if certificate needs renewal (within 7 days of expiry).
     */
    public boolean needsRenewal() {
        long daysUntilExpiry = (expirationDate.getTime() - System.currentTimeMillis()) / (1000 * 60 * 60 * 24);
        return daysUntilExpiry <= 7;
    }

    // Getters — defensive copies for mutable fields
    public String getPluginId() { return pluginId; }
    public String getCertificateId() { return certificateId; }
    public List<String> getCapabilities() { return capabilities; }
    public Date getIssuanceDate() { return new Date(issuanceDate.getTime()); }
    public Date getExpirationDate() { return new Date(expirationDate.getTime()); }
    public String getIssuer() { return issuer; }
    public String getSignature() { return signature; }
    public String getPublicKey() { return publicKey; }
    public CertificateStatus getStatus() { return status; }
    public String getEncryptedRevocationTimestamp() { return encryptedRevocationTimestamp; }

    // Enums
    public enum CertificateStatus {
        ACTIVE,
        EXPIRED,
        REVOKED,
        SUSPENDED
    }
}
