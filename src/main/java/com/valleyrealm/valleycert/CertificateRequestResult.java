package com.valleyrealm.valleycert;

/**
 * Result of a certificate request.
 */
public class CertificateRequestResult {

    private final boolean success;
    private final CertificateData certificate;
    private final String errorMessage;

    private CertificateRequestResult(boolean success, CertificateData certificate, String errorMessage) {
        this.success = success;
        this.certificate = certificate;
        this.errorMessage = errorMessage;
    }

    public static CertificateRequestResult success(CertificateData certificate) {
        return new CertificateRequestResult(true, certificate, null);
    }

    public static CertificateRequestResult failure(String errorMessage) {
        return new CertificateRequestResult(false, null, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public CertificateData getCertificate() { return certificate; }
    public String getErrorMessage() { return errorMessage; }
}
