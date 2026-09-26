package com.paymentsystem.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String businessName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 15, max = 64) String password) {

    @Override
    public String toString() {
        return "RegisterRequest[businessName=" + businessName + ", email=" + email + ", password=[REDACTED]]";
    }
}
