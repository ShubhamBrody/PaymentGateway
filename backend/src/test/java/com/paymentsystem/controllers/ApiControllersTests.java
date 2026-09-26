package com.paymentsystem.controllers;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paymentsystem.dtos.AuthResponse;
import com.paymentsystem.dtos.CreatePaymentRequest;
import com.paymentsystem.dtos.LoginRequest;
import com.paymentsystem.dtos.MerchantResponse;
import com.paymentsystem.dtos.PageResponse;
import com.paymentsystem.dtos.PaymentAttemptResponse;
import com.paymentsystem.dtos.PaymentResponse;
import com.paymentsystem.dtos.RegisterRequest;
import com.paymentsystem.dtos.SimulatedAttemptRequest;
import com.paymentsystem.exceptions.ConflictException;
import com.paymentsystem.exceptions.InvalidCredentialsException;
import com.paymentsystem.exceptions.ResourceNotFoundException;
import com.paymentsystem.models.PaymentAttemptStatus;
import com.paymentsystem.models.PaymentStatus;
import com.paymentsystem.services.AuthService;
import com.paymentsystem.services.MerchantService;
import com.paymentsystem.services.PaymentService;
import com.paymentsystem.services.SimulatorService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiControllersTests {

    private static final UUID MERCHANT_ID = UUID.fromString("3f1c9d4e-8a2b-4c6d-9e0f-1a2b3c4d5e6f");
    private static final UUID PAYMENT_ID = UUID.fromString("9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
    private static final UUID ATTEMPT_ID = UUID.fromString("0a1b2c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d");
    private static final Instant CREATED_AT = Instant.parse("2026-09-26T10:00:00Z");
    private static final String VALID_PASSWORD = "correct-horse-battery-staple";
    private static final String VALID_PAYMENT_JSON =
            "{\"amount\":1000,\"currency\":\"INR\",\"description\":\"Test order\",\"orderReference\":\"order-123\"}";
    private static final PaymentResponse PAYMENT = payment(PaymentStatus.AWAITING_PAYMENT);
    private static final PaymentAttemptResponse PENDING_ATTEMPT = new PaymentAttemptResponse(
            ATTEMPT_ID, PAYMENT_ID, PaymentAttemptStatus.PENDING, null, CREATED_AT, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private MerchantService merchantService;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private SimulatorService simulatorService;

    @Test
    void registerIsPublicAndReturnsCreatedMerchant() throws Exception {
        RegisterRequest request = new RegisterRequest("Demo Store", "merchant@example.com", VALID_PASSWORD);
        when(authService.register(request)).thenReturn(merchant());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Demo Store\",\"email\":\"merchant@example.com\",\"password\":\""
                                + VALID_PASSWORD + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MERCHANT_ID.toString()))
                .andExpect(jsonPath("$.businessName").value("Demo Store"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-26T10:00:00Z"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"businessName\":\"\",\"email\":\"merchant@example.com\",\"password\":\"correct-horse-battery-staple\"}",
            "{\"businessName\":\"Demo Store\",\"email\":\"not-an-email\",\"password\":\"correct-horse-battery-staple\"}",
            "{\"businessName\":\"Demo Store\",\"email\":\"merchant@example.com\",\"password\":\"too-short\"}"
    })
    void registerRejectsInvalidInput(String body) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(authService);
    }

    @Test
    void loginIsPublicAndReturnsBearerToken() throws Exception {
        LoginRequest request = new LoginRequest("merchant@example.com", VALID_PASSWORD);
        when(authService.login(request)).thenReturn(new AuthResponse("signed.jwt.value", "Bearer", 900));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"merchant@example.com\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("signed.jwt.value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void currentMerchantComesFromTokenSubject() throws Exception {
        when(merchantService.getMerchant(MERCHANT_ID)).thenReturn(merchant());

        mockMvc.perform(get("/api/v1/merchants/me").with(merchantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MERCHANT_ID.toString()))
                .andExpect(jsonPath("$.email").value("merchant@example.com"));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/merchants/me",
            "POST, /api/v1/payments",
            "GET, /api/v1/payments",
            "GET, /api/v1/payments/9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
            "POST, /api/v1/payments/9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d/cancel",
            "GET, /api/v1/payments/9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d/attempts",
            "POST, /api/v1/simulator/payments/9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d/attempts",
            "POST, /api/v1/simulator/payments/9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d/attempts/0a1b2c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d/succeed",
            "POST, /api/v1/simulator/payments/9b8a7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d/attempts/0a1b2c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d/decline"
    })
    void merchantEndpointsRequireAuthentication(String method, String path) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), path))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(merchantService, paymentService, simulatorService);
    }

    @Test
    void createPaymentReturnsCreatedPaymentWithLocation() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(1000L, "INR", "Test order", "order-123");
        when(paymentService.createPayment(MERCHANT_ID, "checkout-123", request)).thenReturn(PAYMENT);

        mockMvc.perform(post("/api/v1/payments")
                        .with(merchantJwt())
                        .header("Idempotency-Key", "checkout-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYMENT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/payments/" + PAYMENT_ID))
                .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void createPaymentRequiresIdempotencyKey(String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/payments")
                .with(merchantJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYMENT_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }

        mockMvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"currency\":\"INR\"}",
            "{\"amount\":0,\"currency\":\"INR\"}",
            "{\"amount\":-100,\"currency\":\"INR\"}",
            "{\"amount\":10.5,\"currency\":\"INR\"}",
            "{\"amount\":100000001,\"currency\":\"INR\"}",
            "{\"amount\":1000}",
            "{\"amount\":1000,\"currency\":\"inr\"}"
    })
    void createPaymentRejectsInvalidMoney(String body) throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(merchantJwt())
                        .header("Idempotency-Key", "checkout-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(paymentService);
    }

    @Test
    void listPaymentsPassesFilterAndPaging() throws Exception {
        when(paymentService.listPayments(MERCHANT_ID, PaymentStatus.SUCCEEDED, 2, 50))
                .thenReturn(new PageResponse<>(List.of(PAYMENT), 2, 50, 101, 3));

        mockMvc.perform(get("/api/v1/payments")
                        .with(merchantJwt())
                        .param("status", "SUCCEEDED")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(101))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void listPaymentsUsesDefaultPaging() throws Exception {
        when(paymentService.listPayments(MERCHANT_ID, null, 0, 20))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/payments").with(merchantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.size").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "status=UNKNOWN"})
    void listPaymentsRejectsInvalidQuery(String query) throws Exception {
        mockMvc.perform(get("/api/v1/payments?" + query).with(merchantJwt()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void getPaymentReturnsMerchantPayment() throws Exception {
        when(paymentService.getPayment(MERCHANT_ID, PAYMENT_ID)).thenReturn(PAYMENT);

        mockMvc.perform(get("/api/v1/payments/{paymentId}", PAYMENT_ID).with(merchantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderReference").value("order-123"))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @Test
    void malformedPaymentIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/payments/not-a-uuid").with(merchantJwt()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paymentService);
    }

    @Test
    void cancelPaymentReturnsUpdatedPayment() throws Exception {
        when(paymentService.cancelPayment(MERCHANT_ID, PAYMENT_ID)).thenReturn(payment(PaymentStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", PAYMENT_ID).with(merchantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void listAttemptsReturnsPaymentAttempts() throws Exception {
        when(paymentService.listAttempts(MERCHANT_ID, PAYMENT_ID)).thenReturn(List.of(PENDING_ATTEMPT));

        mockMvc.perform(get("/api/v1/payments/{paymentId}/attempts", PAYMENT_ID).with(merchantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ATTEMPT_ID.toString()))
                .andExpect(jsonPath("$[0].paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void simulateAttemptReturnsCreatedAttempt() throws Exception {
        when(simulatorService.simulateAttempt(
                MERCHANT_ID, PAYMENT_ID, new SimulatedAttemptRequest(PaymentAttemptStatus.PENDING)))
                .thenReturn(PENDING_ATTEMPT);

        mockMvc.perform(post("/api/v1/simulator/payments/{paymentId}/attempts", PAYMENT_ID)
                        .with(merchantJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"PENDING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ATTEMPT_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"outcome\":\"UNKNOWN\"}"})
    void simulateAttemptRequiresKnownOutcome(String body) throws Exception {
        mockMvc.perform(post("/api/v1/simulator/payments/{paymentId}/attempts", PAYMENT_ID)
                        .with(merchantJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(simulatorService);
    }

    @ParameterizedTest
    @CsvSource({"succeed, SUCCEEDED", "decline, DECLINED"})
    void resolvesPendingAttempt(String action, PaymentAttemptStatus result) throws Exception {
        PaymentAttemptResponse resolved = new PaymentAttemptResponse(
                ATTEMPT_ID, PAYMENT_ID, result, null, CREATED_AT, CREATED_AT.plusSeconds(30));
        when(simulatorService.resolvePendingAttempt(MERCHANT_ID, PAYMENT_ID, ATTEMPT_ID, result)).thenReturn(resolved);

        mockMvc.perform(post("/api/v1/simulator/payments/{paymentId}/attempts/{attemptId}/{action}",
                        PAYMENT_ID, ATTEMPT_ID, action).with(merchantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(result.name()))
                .andExpect(jsonPath("$.resolvedAt").value("2026-09-26T10:00:30Z"));
    }

    @Test
    void missingPaymentReturnsNotFoundProblem() throws Exception {
        when(paymentService.getPayment(MERCHANT_ID, PAYMENT_ID))
                .thenThrow(new ResourceNotFoundException("Payment not found"));

        mockMvc.perform(get("/api/v1/payments/{paymentId}", PAYMENT_ID).with(merchantJwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Payment not found"));
    }

    @Test
    void invalidStateReturnsConflictProblem() throws Exception {
        when(paymentService.cancelPayment(MERCHANT_ID, PAYMENT_ID))
                .thenThrow(new ConflictException("A succeeded payment cannot be cancelled"));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", PAYMENT_ID).with(merchantJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("A succeeded payment cannot be cancelled"));
    }

    @Test
    void wrongCredentialsReturnGenericUnauthorizedProblem() throws Exception {
        when(authService.login(new LoginRequest("merchant@example.com", VALID_PASSWORD)))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"merchant@example.com\",\"password\":\"" + VALID_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email or password is incorrect"));
    }

    @Test
    void unexpectedErrorHidesInternalDetails() throws Exception {
        when(paymentService.getPayment(MERCHANT_ID, PAYMENT_ID))
                .thenThrow(new IllegalStateException("database password=secret"));

        mockMvc.perform(get("/api/v1/payments/{paymentId}", PAYMENT_ID).with(merchantJwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    private static RequestPostProcessor merchantJwt() {
        return jwt().jwt(token -> token.subject(MERCHANT_ID.toString()));
    }

    private static MerchantResponse merchant() {
        return new MerchantResponse(MERCHANT_ID, "Demo Store", "merchant@example.com", CREATED_AT);
    }

    private static PaymentResponse payment(PaymentStatus status) {
        return new PaymentResponse(
                PAYMENT_ID, 1000, "INR", status, "Test order", "order-123", CREATED_AT, CREATED_AT);
    }
}
