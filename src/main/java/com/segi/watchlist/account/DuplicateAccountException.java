package com.segi.watchlist.account;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() {
        super("An account with this email already exists.");
    }
}
