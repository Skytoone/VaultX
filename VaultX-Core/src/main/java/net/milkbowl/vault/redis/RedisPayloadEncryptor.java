package net.milkbowl.vault.redis;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class RedisPayloadEncryptor {

    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private final SecretKey key;
    private final boolean enabled;

    public RedisPayloadEncryptor(String passphrase) {
        if (passphrase == null || passphrase.trim().isEmpty() || passphrase.startsWith("YOUR_")) {
            this.key = null;
            this.enabled = false;
        } else {
            this.key = deriveKey(passphrase);
            this.enabled = true;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String rawPayload) {
        if (!enabled || rawPayload == null) {
            return rawPayload;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] cipherText = cipher.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return "ENC:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            return rawPayload;
        }
    }

    public String decrypt(String encryptedPayload) {
        if (!enabled || encryptedPayload == null || !encryptedPayload.startsWith("ENC:")) {
            return encryptedPayload;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedPayload.substring(4));
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[combined.length - IV_LENGTH];

            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encryptedPayload;
        }
    }

    private SecretKey deriveKey(String passphrase) {
        try {
            byte[] salt = "VaultXRedisSalt2026".getBytes(StandardCharsets.UTF_8);
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 65536, 256);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = skf.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            return null;
        }
    }
}
