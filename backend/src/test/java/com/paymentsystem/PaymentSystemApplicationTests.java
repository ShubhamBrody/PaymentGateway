package com.paymentsystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.paymentsystem.security.JwtProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentSystemApplicationTests {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void startsWithH2AndJpa() throws Exception {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
        }
    }

    @Test
    @Transactional
    void persistsAndReloadsAnEntity() {
        PersistenceProbe probe = new PersistenceProbe();
        probe.description = "JPA persistence check";
        entityManager.persist(probe);
        entityManager.flush();
        entityManager.clear();

        PersistenceProbe stored = entityManager.find(PersistenceProbe.class, probe.id);
        assertThat(stored.description).isEqualTo("JPA persistence check");
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void statusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validSignedTokenCanAccessStatus() throws Exception {
        mockMvc.perform(get("/api/v1/system/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("payment-system"))
                .andExpect(jsonPath("$.mode").value("simulation"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        assertUnauthorized("not-a-jwt");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertUnauthorized(token(claims()
                .issuedAt(Instant.now().minusSeconds(900))
                .expiresAt(Instant.now().minusSeconds(300))));
    }

    @Test
    void wrongSigningKeyIsRejected() throws Exception {
        assertUnauthorized(token(claims(), "a-different-test-only-key-at-least-32-characters"));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        assertUnauthorized(token(claims().issuer("another-issuer")));
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        assertUnauthorized(token(claims().audience(List.of("another-api"))));
    }

    @Test
    void nonUuidSubjectIsRejected() throws Exception {
        assertUnauthorized(token(claims().subject("test-user")));
    }

    @Test
    void signedTokenForUnknownMerchantReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Merchant not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub", "exp"})
    void missingRequiredClaimIsRejected(String requiredClaim) throws Exception {
        assertUnauthorized(token(claims().claims(values -> values.remove(requiredClaim))));
    }

    @Test
    void allowsConfiguredFrontendPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/system/status")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void rejectsUnconfiguredFrontendPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/system/status")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/v1/system/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    private String token(JwtClaimsSet.Builder claims) {
        return token(claims, jwtProperties.secret());
    }

    private String token(JwtClaimsSet.Builder claims, String secret) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    @Entity(name = "PersistenceProbe")
    @Table(name = "persistence_probe")
    static class PersistenceProbe {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String description;
    }
}