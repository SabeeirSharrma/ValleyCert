package com.valleyrealm.valleycert;

import com.valleyrealm.valleycert.request.CertificateRequester;
import com.valleyrealm.valleycert.storage.CertificateStorage;
import com.valleyrealm.valleycert.validation.CertificateValidator;
import com.valleyrealm.valleycert.renewal.CertificateRenewer;

import java.nio.file.Path;

/**
 * ValleyCert - Client-side certificate management utility.
 * 
 * ValleyCert is NOT a certificate authority.
 * It requires ValleyAuth Core for certificate issuance.
 * 
 * Responsibilities:
 * - Request certificates from ValleyAuth Core
 * - Receive and validate issued certificates
 * - Securely store certificates
 * - Manage certificate lifecycle
 * - Automatically renew certificates
 * - Present certificates to protected APIs
 * 
 * Flow:
 * Plugin X init -> ValleyCert requests cert from ValleyAuth Core
 * -> ValleyAuth Core gets cert from ValleyCertAPI (CA)
 * -> ValleyAuth Core gives to ValleyCert
 * -> ValleyCert stores cert + sets expiry
 * -> Plugin validates cert -> continues init
 */
public class ValleyCert {

    private final Path pluginDataFolder;
    private final CertificateRequester requester;
    private final CertificateStorage storage;
    private final CertificateValidator validator;
    private final CertificateRenewer renewer;

    // Certificate state
    private CertificateData currentCertificate;
    private boolean initialized = false;

    /**
     * Create a new ValleyCert instance.
     * 
     * @param pluginDataFolder The plugin's data folder for certificate storage
     */
    public ValleyCert(Path pluginDataFolder) {
        this.pluginDataFolder = pluginDataFolder;
        this.requester = new CertificateRequester();
        this.storage = new CertificateStorage(pluginDataFolder);
        this.validator = new CertificateValidator();
        this.renewer = new CertificateRenewer(this);
    }

    /**
     * Initialize ValleyCert and request a certificate.
     * 
     * @param pluginId Unique identifier for the plugin
     * @param capabilities Required capabilities (e.g., "VLINK", "MIGRATION_PROVIDER")
     * @param requestedLifetimeDays Requested certificate lifetime (default 90, max 120)
     * @return true if initialization succeeded
     */
    public boolean initialize(String pluginId, String[] capabilities, int requestedLifetimeDays) {
        System.out.println("[ValleyCert] Initializing for plugin: " + pluginId);

        // Try to load existing certificate
        currentCertificate = storage.loadCertificate(pluginId);

        // Check if we have a valid certificate
        if (currentCertificate != null && validator.isValid(currentCertificate)) {
            System.out.println("[ValleyCert] Valid certificate found.");
            initialized = true;
            
            // Schedule renewal check
            renewer.scheduleRenewalCheck(currentCertificate, this::handleRenewal);
            return true;
        }

        // Request new certificate from ValleyAuth Core
        System.out.println("[ValleyCert] Requesting new certificate...");
        CertificateRequestResult result = requester.requestCertificate(pluginId, capabilities, requestedLifetimeDays);

        if (result.isSuccess()) {
            currentCertificate = result.getCertificate();
            
            // Validate received certificate
            if (!validator.isValid(currentCertificate)) {
                System.err.println("[ValleyCert] Received invalid certificate!");
                return false;
            }

            // Store certificate
            storage.saveCertificate(pluginId, currentCertificate);
            
            System.out.println("[ValleyCert] Certificate obtained and stored.");
            System.out.println("[ValleyCert] Expires: " + currentCertificate.getExpirationDate());
            
            initialized = true;
            
            // Schedule renewal check
            renewer.scheduleRenewalCheck(currentCertificate, this::handleRenewal);
            return true;
        } else {
            System.err.println("[ValleyCert] Failed to obtain certificate: " + result.getErrorMessage());
            return false;
        }
    }

    /**
     * Validate that the current certificate has the required capability.
     */
    public boolean validateCapability(String capability) {
        if (currentCertificate == null) {
            System.err.println("[ValleyCert] No certificate loaded.");
            return false;
        }

        return validator.hasCapability(currentCertificate, capability);
    }

    /**
     * Get the current certificate data.
     */
    public CertificateData getCertificate() {
        return currentCertificate;
    }

    /**
     * Check if ValleyCert is initialized with a valid certificate.
     */
    public boolean isInitialized() {
        return initialized && currentCertificate != null && validator.isValid(currentCertificate);
    }

    /**
     * Handle certificate renewal.
     */
    private void handleRenewal(CertificateData oldCert, CertificateData newCert) {
        System.out.println("[ValleyCert] Renewing certificate...");
        
        if (newCert != null && validator.isValid(newCert)) {
            currentCertificate = newCert;
            storage.saveCertificate(oldCert.getPluginId(), newCert);
            System.out.println("[ValleyCert] Certificate renewed successfully.");
        } else {
            System.err.println("[ValleyCert] Certificate renewal failed!");
        }
    }

    /**
     * Get the certificate storage path.
     */
    public Path getCertificatePath(String pluginId) {
        return storage.getCertificatePath(pluginId);
    }
}
