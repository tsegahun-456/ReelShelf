package com.segi.watchlist.account;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class AccountService implements UserDetailsService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void register(String displayName, String email, String password) {
        String normalizedEmail = email.trim().toLowerCase();
        if (accounts.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateAccountException();
        }
        accounts.save(new Account(
                normalizedEmail,
                displayName.trim(),
                passwordEncoder.encode(password)));
    }

    @Transactional(readOnly = true)
    public Account getRequiredByEmail(String email) {
        return accounts.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    @Transactional(readOnly = true)
    public boolean legacyCredentialsMatch(String email, String rawPassword) {
        return accounts.findByEmailIgnoreCase(email.trim())
                .map(account -> passwordEncoder.matches(rawPassword, account.getPasswordHash()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasAccount(String email) {
        return accounts.existsByEmailIgnoreCase(email.trim());
    }

    @Transactional
    public Account linkFirebaseIdentity(String email, String displayName, String firebaseUid) {
        String normalizedEmail = email.trim().toLowerCase();
        Account existing = accounts.findByFirebaseUid(firebaseUid).orElse(null);
        if (existing == null) {
            existing = accounts.findByEmailIgnoreCase(normalizedEmail).orElse(null);
            if (existing != null && existing.getFirebaseUid() != null
                    && !existing.getFirebaseUid().equals(firebaseUid)) {
                throw new AccountIdentityConflictException();
            }
        }
        if (existing != null) {
            existing.linkFirebaseIdentity(firebaseUid, normalizedEmail);
            return existing;
        }

        String safeName = displayName == null || displayName.isBlank()
                ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
                : displayName.trim();
        String unusableLocalPassword = passwordEncoder.encode(UUID.randomUUID().toString());
        Account account = new Account(normalizedEmail, safeName, unusableLocalPassword);
        account.linkFirebaseIdentity(firebaseUid, normalizedEmail);
        return accounts.save(account);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = getRequiredByEmail(username);
        return User.withUsername(account.getEmail())
                .password(account.getPasswordHash())
                .roles("USER")
                .build();
    }
}
