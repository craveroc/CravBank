package com.cravero.cravbank.common;

public class RefreshTokenInvalidException extends RuntimeException {
    public RefreshTokenInvalidException() {
        super("Refresh token is invalid or expired");
    }
}
