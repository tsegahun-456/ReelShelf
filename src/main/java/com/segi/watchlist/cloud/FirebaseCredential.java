package com.segi.watchlist.cloud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A remembered sign-in: enough information (uid, email, encrypted refresh token)
 * to restore a Firebase session after an app restart without asking for the
 * password again. Rows exist only while "Keep me signed in" is active.
 */
@Entity
@Table(name = "firebase_credential")
public class FirebaseCredential {
    @Id
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "firebase_uid", nullable = false, length = 128)
    private String firebaseUid;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "refresh_token_encrypted", nullable = false, length = 1024)
    private String refreshTokenEncrypted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FirebaseCredential() {
    }

    public void update(String email, String uid, String displayName, String encryptedRefreshToken) {
        this.email = email;
        this.firebaseUid = uid;
        this.displayName = displayName == null || displayName.isBlank() ? "" : displayName;
        this.refreshTokenEncrypted = encryptedRefreshToken;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getEmail() { return email; }
    public String getFirebaseUid() { return firebaseUid; }
    public String getDisplayName() { return displayName; }
    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
