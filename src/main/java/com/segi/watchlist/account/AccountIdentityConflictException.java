package com.segi.watchlist.account;

public class AccountIdentityConflictException extends RuntimeException {
    public AccountIdentityConflictException() {
        super("This email is already linked to another sign-in identity.");
    }
}
