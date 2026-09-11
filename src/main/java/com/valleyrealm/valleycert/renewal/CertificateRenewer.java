package com.valleyrealm.valleycert.renewal;

import com.valleyrealm.valleycert.CertificateData;
import com.valleyrealm.valleycert.CertificateRequestResult;
import com.valleyrealm.valleycert.request.CertificateRequester;
import com.valleyrealm.valleycert.validation.CertificateValidator;

import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Manages automatic certificate renewal.
 * 
 * Renewal flow:
 * 1. Check if certificate needs renewal (within 7 days of expiry)
 * 2. Request renewal from ValleyAuth Core
 * 3. Validate new certificate
 * 4. Atomically replace old certificate
 * 5. Old certificate remains valid until replacement is verified
 */
public class CertificateRenewer {

    private final CertificateRequester requester;
    private final CertificateValidator validator;
    private final ScheduledExecutorService scheduler;

    // Renewal threshold: 7 days before expiry
    private static final long RENEWAL_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000;
    
    // Check interval: every hour
    private static final long CHECK_INTERVAL_MS = 60 * 60 * 1000;

    public CertificateRenewer(Object valleyCertInstance) {
        this.requester = new CertificateRequester();
        this.validator = new CertificateValidator();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ValleyCert-Renewer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule periodic renewal checks for a certificate.
     */
    public void scheduleRenewalCheck(CertificateData certificate, BiConsumer<CertificateData, CertificateData> onRenewal) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndRenew(certificate, onRenewal);
            } catch (Exception e) {
                System.err.println("[ValleyCert Renewer] Error during renewal check: " + e.getMessage());
            }
        }, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        System.out.println("[ValleyCert Renewer] Scheduled renewal check for: " + certificate.getPluginId());
    }

    /**
     * Check if renewal is needed and perform it.
     */
    private void checkAndRenew(CertificateData oldCert, BiConsumer<CertificateData, CertificateData> onRenewal) {
        if (!validator.isValid(oldCert)) {
            System.out.println("[ValleyCert Renewer] Certificate is no longer valid, skipping renewal.");
            return;
        }

        if (!validator.needsRenewal(oldCert)) {
            // Not time to renew yet
            return;
        }

        System.out.println("[ValleyCert Renewer] Certificate needs renewal. Days until expiry: " + 
            (validator.getMillisUntilExpiry(oldCert) / (24 * 60 * 60 * 1000)));

        // Request renewal
        CertificateRequestResult result = requester.requestRenewal(oldCert.getPluginId(), oldCert);

        if (result.isSuccess()) {
            CertificateData newCert = result.getCertificate();
            
            // Validate new certificate
            if (validator.isValid(newCert)) {
                // Notify callback with old and new certificates
                onRenewal.accept(oldCert, newCert);
            } else {
                System.err.println("[ValleyCert Renewer] Received invalid certificate during renewal!");
            }
        } else {
            System.err.println("[ValleyCert Renewer] Renewal failed: " + result.getErrorMessage());
        }
    }

    /**
     * Shutdown the renewer.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
