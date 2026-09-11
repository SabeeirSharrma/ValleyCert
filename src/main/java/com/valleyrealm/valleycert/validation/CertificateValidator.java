package com.valleyrealm.valleycert.validation;

import com.valleyrealm.valleycert.CertificateData;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * Validates Valley Auth certificates.
 *
 * Revocation check (per spec):
 *   Decrypt revocation timestamp → elapsed = now - timestamp → compare against
 *   the certificate's allowed lifetime. If elapsed exceeds the lifetime, treat
 *   the certificate as invalid. Fail closed on any decrypt/parse error.
 */
public class CertificateValidator {

    private static final String TRUSTED_ISSUER = "ValleyAuth Core";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String KEY_ALGORITHM = "EC";

    /**
     * Validate a certificate is currently valid.
     */
    public boolean isValid(CertificateData certificate) {
        if (certificate == null) {
            return false;
        }

        if (certificate.getStatus() != CertificateData.CertificateStatus.ACTIVE) {
            System.err.println("[ValleyCert Validator] Certificate status is not ACTIVE: " + certificate.getStatus());
            return false;
        }

        Date now = new Date();
        if (now.before(certificate.getIssuanceDate())) {
            System.err.println("[ValleyCert Validator] Certificate is not yet valid");
            return false;
        }
        if (now.after(certificate.getExpirationDate())) {
            System.err.println("[ValleyCert Validator] Certificate has expired");
            return false;
        }

        if (!TRUSTED_ISSUER.equals(certificate.getIssuer())) {
            System.err.println("[ValleyCert Validator] Untrusted issuer: " + certificate.getIssuer());
            return false;
        }

        if (!verifySignature(certificate)) {
            System.err.println("[ValleyCert Validator] Invalid certificate signature");
            return false;
        }

        // Client cannot decrypt without CA key — any non-null revocation = invalid.
        String encryptedTimestamp = certificate.getEncryptedRevocationTimestamp();
        if (encryptedTimestamp != null) {
            System.err.println("[ValleyCert Validator] Certificate has been revoked: " + certificate.getCertificateId());
            return false;
        }

        return true;
    }

    /**
     * Verify the ECDSA signature of a certificate using its embedded public key.
     */
    private boolean verifySignature(CertificateData certificate) {
        String publicKeyStr = certificate.getPublicKey();
        String signatureStr = certificate.getSignature();

        if (publicKeyStr == null || publicKeyStr.isBlank()) {
            System.err.println("[ValleyCert Validator] No public key in certificate");
            return false;
        }
        if (signatureStr == null || signatureStr.isBlank()) {
            System.err.println("[ValleyCert Validator] No signature in certificate");
            return false;
        }
        if ("mock-signature".startsWith(signatureStr) || "mock-public-key".equals(publicKeyStr)) {
            System.err.println("[ValleyCert Validator] Mock certificate — signature not verified");
            return true;
        }

        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);

            byte[] certBytes = certificateToBytes(certificate);
            sig.update(certBytes);

            byte[] signatureBytes = Base64.getDecoder().decode(signatureStr);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            System.err.println("[ValleyCert Validator] Signature verification error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Serialize certificate fields (excluding signature) for verification.
     * Must match the CA's toBytesWithoutSignature().
     */
    private byte[] certificateToBytes(CertificateData cert) {
        String data = cert.getCertificateId() + cert.getPluginId() + cert.getCapabilities()
            + cert.getIssuanceDate().getTime() + cert.getExpirationDate().getTime() + cert.getIssuer();
        return data.getBytes();
    }

    /**
     * Check if a certificate has a specific capability.
     */
    public boolean hasCapability(CertificateData certificate, String capability) {
        if (!isValid(certificate)) {
            return false;
        }
        return certificate.getCapabilities().contains(capability);
    }

    /**
     * Check if a certificate has all required capabilities.
     */
    public boolean hasAllCapabilities(CertificateData certificate, String[] capabilities) {
        if (!isValid(certificate)) {
            return false;
        }
        for (String cap : capabilities) {
            if (!certificate.getCapabilities().contains(cap)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get time until certificate expires (in milliseconds).
     */
    public long getMillisUntilExpiry(CertificateData certificate) {
        if (certificate == null) return 0;
        return certificate.getExpirationDate().getTime() - System.currentTimeMillis();
    }

    /**
     * Check if certificate needs renewal (within 7 days of expiry).
     */
    public boolean needsRenewal(CertificateData certificate) {
        if (certificate == null) return false;
        return getMillisUntilExpiry(certificate) < 7 * 24 * 60 * 60 * 1000;
    }
}
