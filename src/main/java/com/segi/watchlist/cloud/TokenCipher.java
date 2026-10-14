package com.segi.watchlist.cloud;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts the Firebase refresh token at rest with AES-GCM so a remembered
 * sign-in can be restored after a restart without storing a readable secret.
 */
@Component
public class TokenCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final PersistentSignInKey key;

    public TokenCipher(PersistentSignInKey key) {
        this.key = key;
    }

    public String encrypt(String secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.keyBytes(), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not protect the saved sign-in token.", exception);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] data = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.keyBytes(), "AES"),
                    new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("The saved sign-in token cannot be read on this PC.", exception);
        }
    }
}
