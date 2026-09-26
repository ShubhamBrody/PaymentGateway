package com.paymentsystem.dtos;

public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds) {

    @Override
    public String toString() {
        return "AuthResponse[accessToken=[REDACTED], tokenType=" + tokenType
                + ", expiresInSeconds=" + expiresInSeconds + "]";
    }
}
