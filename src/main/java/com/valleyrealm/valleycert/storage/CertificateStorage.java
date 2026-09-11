package com.valleyrealm.valleycert.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.valleyrealm.valleycert.CertificateData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

/**
 * Manages certificate storage on the local filesystem.
 * 
 * Storage requirements:
 * - Avoid exposing certificate material unnecessarily
 * - Validate certificates before use
 * - Prevent partially written replacement certificates
 * - Safely replace renewed certificates
 * - Preserve previous valid certificate until replacement is verified
 * - Detect malformed certificate files
 */
public class CertificateStorage {

    private final Path storageDir;
    private final Gson gson;

    // File naming
    private static final String CERT_FILE_NAME = "valley.cert";
    private static final String BACKUP_SUFFIX = ".backup";

    public CertificateStorage(Path pluginDataFolder) {
        this.storageDir = pluginDataFolder.resolve("certs");
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
        
        // Create storage directory
        createStorageDir();
    }

    /**
     * Load a certificate for a plugin.
     */
    public CertificateData loadCertificate(String pluginId) {
        Path certPath = getCertificatePath(pluginId);
        
        if (!Files.exists(certPath)) {
            return null;
        }

        try {
            String json = Files.readString(certPath);
            CertificateData cert = gson.fromJson(json, CertificateData.class);
            
            // Validate loaded certificate
            if (cert == null || cert.getPluginId() == null) {
                System.err.println("[ValleyCert Storage] Malformed certificate file: " + certPath);
                return null;
            }
            
            return cert;
        } catch (Exception e) {
            System.err.println("[ValleyCert Storage] Error loading certificate: " + e.getMessage());
            return null;
        }
    }

    /**
     * Save a certificate for a plugin.
     * Uses atomic write to prevent partial writes.
     */
    public void saveCertificate(String pluginId, CertificateData certificate) {
        Path certPath = getCertificatePath(pluginId);
        Path tempPath = certPath.resolveSibling(certPath.getFileName() + ".tmp");
        Path backupPath = certPath.resolveSibling(certPath.getFileName() + BACKUP_SUFFIX);

        try {
            // Write to temp file first
            String json = gson.toJson(certificate);
            Files.writeString(tempPath, json);

            // Backup existing certificate
            if (Files.exists(certPath)) {
                Files.move(certPath, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Atomic move temp -> final
            Files.move(tempPath, certPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            System.out.println("[ValleyCert Storage] Certificate saved: " + certPath);
        } catch (IOException e) {
            System.err.println("[ValleyCert Storage] Error saving certificate: " + e.getMessage());
            
            // Cleanup temp file
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {}
        }
    }

    /**
     * Delete a certificate for a plugin.
     */
    public boolean deleteCertificate(String pluginId) {
        Path certPath = getCertificatePath(pluginId);
        Path backupPath = certPath.resolveSibling(certPath.getFileName() + BACKUP_SUFFIX);
        
        try {
            Files.deleteIfExists(certPath);
            Files.deleteIfExists(backupPath);
            return true;
        } catch (IOException e) {
            System.err.println("[ValleyCert Storage] Error deleting certificate: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the certificate path for a plugin.
     */
    public Path getCertificatePath(String pluginId) {
        return storageDir.resolve(pluginId).resolve(CERT_FILE_NAME);
    }

    /**
     * Check if a certificate exists for a plugin.
     */
    public boolean hasCertificate(String pluginId) {
        return Files.exists(getCertificatePath(pluginId));
    }

    private void createStorageDir() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            System.err.println("[ValleyCert Storage] Failed to create storage directory: " + e.getMessage());
        }
    }
}
