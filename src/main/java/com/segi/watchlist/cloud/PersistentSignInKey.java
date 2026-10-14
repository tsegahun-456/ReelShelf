package com.segi.watchlist.cloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads (or creates once) the local secret used to protect remembered sign-ins:
 * AES key for encrypting Firebase refresh tokens at rest and the HMAC key for
 * remember-me cookie signatures. The key file stays on this PC only.
 */
@Component
public class PersistentSignInKey {
    private static final Logger log = LoggerFactory.getLogger(PersistentSignInKey.class);
    private static final int KEY_BYTES = 32;

    private final byte[] keyBytes;
    private final String hmacKey;

    public PersistentSignInKey(@Value("${security.remember-me.key-file:}") String keyFilePath) {
        this.keyBytes = loadOrCreate(keyFilePath);
        this.hmacKey = HexFormat.of().formatHex(keyBytes);
    }

    /** AES key bytes (a copy; callers cannot mutate the stored key). */
    public byte[] keyBytes() {
        return keyBytes.clone();
    }

    /** Hex form of the key, used as the remember-me HMAC signing key. */
    public String hmacKey() {
        return hmacKey;
    }

    private static byte[] loadOrCreate(String keyFilePath) {
        if (keyFilePath == null || keyFilePath.isBlank()) return randomKey("no key file is configured");
        Path path = Paths.get(keyFilePath);
        try {
            if (Files.exists(path)) {
                byte[] stored = HexFormat.of().parseHex(Files.readString(path).trim());
                if (stored.length == KEY_BYTES) return stored;
                log.warn("Persistent sign-in key file {} is malformed; creating a replacement.", path);
            } else if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            byte[] fresh = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(fresh);
            Files.writeString(path, HexFormat.of().formatHex(fresh) + "\n");
            restrictToOwner(path);
            log.info("Created the persistent sign-in key at {} (keep this file private).", path);
            return fresh;
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return randomKey("cannot read or create " + path + " ("
                    + exception.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] randomKey(String reason) {
        log.warn("Remembered sign-ins will not survive a restart: {}", reason);
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows has no POSIX permissions; the user profile directory protects the file there.
        }
    }
}
