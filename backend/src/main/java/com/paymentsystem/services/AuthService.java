package com.paymentsystem.services;

import com.paymentsystem.dtos.AuthResponse;
import com.paymentsystem.dtos.LoginRequest;
import com.paymentsystem.dtos.MerchantResponse;
import com.paymentsystem.dtos.RegisterRequest;
import com.paymentsystem.exceptions.ConflictException;
import com.paymentsystem.exceptions.InvalidCredentialsException;
import com.paymentsystem.models.Merchant;
import com.paymentsystem.repositories.MerchantRepository;
import com.paymentsystem.security.JwtProperties;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    // Checked for unknown emails so both login failures take a similar time.
    private volatile String unknownEmailPasswordHash;

    @Transactional
    public MerchantResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (merchantRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        Merchant merchant = new Merchant(
                request.businessName().trim(), email, passwordEncoder.encode(request.password()));
        return MerchantResponse.from(merchantRepository.saveAndFlush(merchant));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Merchant merchant = merchantRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
        String passwordHash = merchant == null ? unknownEmailPasswordHash() : merchant.getPasswordHash();
        if (!passwordEncoder.matches(request.password(), passwordHash) || merchant == null) {
            throw new InvalidCredentialsException();
        }
        return new AuthResponse(
                issueAccessToken(merchant.getId()), "Bearer", jwtProperties.accessTokenTtl().toSeconds());
    }

    private String issueAccessToken(UUID merchantId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(merchantId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String unknownEmailPasswordHash() {
        String hash = unknownEmailPasswordHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            unknownEmailPasswordHash = hash;
        }
        return hash;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
