# Payment System

A Java and Spring Boot backend for learning how to build a payment gateway simulation. It currently provides the project structure, security, and the Phase 1 API controllers. Business logic, persistence entities, and a frontend come next. No real money or real card data should be used.

## Stack

- Java 25 and Spring Boot 4.1.1.
- Maven 3.9.15 through the included Maven wrapper.
- Spring Web MVC for REST APIs.
- Spring Data JPA and Hibernate for persistence, with H2 for local development.
- Spring Security OAuth2 Resource Server for JWT bearer-token validation.
- Lombok for generated constructors and DTO accessors.
- Jakarta Bean Validation and Spring Boot Actuator.
- JUnit, Spring Boot Test, and MockMvc for automated checks.

## Structure

```text
backend/
  pom.xml
  mvnw, mvnw.cmd, .mvn/wrapper/
  src/main/java/com/paymentsystem/
    PaymentSystemApplication.java
    config/          SecurityConfig
    controllers/     Auth, Merchant, Payment, Simulator, and System controllers
    dtos/            API request and response records
    exceptions/      Custom exceptions and the global exception handler
    models/          Merchant, Payment, and PaymentAttempt entities, plus status enums
    repositories/    Spring Data JPA repositories for merchants, payments, and attempts
    security/        JWT configuration and the @CurrentMerchantId parameter annotation
    services/        One service per controller
  src/main/resources/
    application.yml
    application-local.yml
  src/test/java/com/paymentsystem/
    PaymentSystemApplicationTests.java
    controllers/ApiControllersTests.java
  src/test/resources/
    application-test.yml
```

| Package | Responsibility |
| --- | --- |
| `controllers` | HTTP routes, request validation, and response status codes. Delegate business decisions to services. |
| `services` | Business rules, use cases, and transaction boundaries. |
| `repositories` | Spring Data JPA repository interfaces for database access. |
| `dtos` | API request and response objects, separate from persistence entities. |
| `models` | JPA entities and domain types. |
| `config` | Framework and application configuration, including security and CORS. |
| `exceptions` | Application exceptions and their conversion to HTTP error responses. |
| `security` | JWT configuration and authentication infrastructure. |

A merchant has many payments, and a payment has many attempts. The persistence probe in the tests is test-only and is not packaged into the application. See [docs/relationship-map.md](docs/relationship-map.md) for class, table, object, and DTO relationships.

## Run Locally

Install JDK 25 and set `JAVA_HOME` to its installation directory. A global Maven installation is not needed; the wrapper downloads Maven on first use. Dependency downloads require internet access.

From the project root, in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
Set-Location backend
.\mvnw.cmd spring-boot:run
```

The backend listens on `http://localhost:8080` and binds only to loopback by default. Use `SERVER_PORT` to select another port. There is no frontend yet.

Check application and database health from another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Expected response: `{"status":"UP"}`.

## APIs

| Method | Endpoint | Access | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Public | Application health, including the database connection, without exposing internal details. |
| GET | `/api/v1/system/status` | JWT | Demonstrates the controller -> service -> response DTO path. |
| POST | `/api/v1/auth/register` | Public | Create a merchant account. Returns 201 with the merchant profile. |
| POST | `/api/v1/auth/login` | Public | Exchange email and password for a bearer token. |
| GET | `/api/v1/merchants/me` | JWT | Profile of the signed-in merchant. |
| POST | `/api/v1/payments` | JWT | Create a payment. Requires an `Idempotency-Key` header; returns 201 with a `Location` header. |
| GET | `/api/v1/payments` | JWT | Transaction history. Optional `status`, `page` (from 0), and `size` (1 to 100, default 20). |
| GET | `/api/v1/payments/{paymentId}` | JWT | Payment details. |
| POST | `/api/v1/payments/{paymentId}/cancel` | JWT | Cancel a payment. |
| GET | `/api/v1/payments/{paymentId}/attempts` | JWT | Attempts made for a payment. |
| POST | `/api/v1/simulator/payments/{paymentId}/attempts` | JWT | Simulate a customer attempt whose `outcome` is `SUCCEEDED`, `DECLINED`, or `PENDING`. Returns 201. |
| POST | `/api/v1/simulator/payments/{paymentId}/attempts/{attemptId}/succeed` | JWT | Simulate the processor approving a pending attempt. |
| POST | `/api/v1/simulator/payments/{paymentId}/attempts/{attemptId}/decline` | JWT | Simulate the processor declining a pending attempt. |

Only health and system status are implemented. The other endpoints enforce authentication, validate requests, and delegate to services that currently return `501 Not Implemented`. Errors use the problem-detail JSON format (`application/problem+json`): invalid requests return 400, missing or invalid tokens and wrong credentials return 401, missing resources return 404, conflicts return 409, and unexpected errors return 500 without internal details.

- Merchant-scoped endpoints take the merchant ID from the token subject, never from the request.
- Amounts are integers in the currency's smallest unit, from 1 to 100,000,000; for example, 1000 means INR 10.00. Decimal amounts are rejected instead of being truncated.
- Currencies are three-letter uppercase codes, such as `INR`.
- Registration passwords must contain 15 to 64 characters.
- Payment statuses are `AWAITING_PAYMENT`, `PROCESSING`, `SUCCEEDED`, and `CANCELLED`. A declined attempt leaves the payment awaiting another attempt.
- Attempt statuses are `PENDING`, `SUCCEEDED`, and `DECLINED`.
- Simulator endpoints stand in for the customer and payment processor and are not part of a real provider flow.

An authenticated status response is `{"application":"payment-system","mode":"simulation"}`.

## Authentication Boundary

Registration and login are public endpoints, but they return 501 until `AuthService` is implemented, so the application does not issue tokens yet. Token refresh, logout/revocation, and team roles are not implemented. The tests sign synthetic tokens solely to verify the security configuration.

Tokens are sent as `Authorization: Bearer <token>`. The backend validates an HS256 signature, issuer, audience, required expiration, and a subject containing the merchant ID as a canonical UUID, plus the not-before time when present. Spring Security's default timestamp clock skew is retained. The signing key is the UTF-8 encoding of the `JWT_SECRET` string, not a Base64-decoded key.

The default `local` profile generates an ephemeral random secret unless `JWT_SECRET` is supplied. Tokens using the generated secret become invalid after restart. Non-local application startup requires a configured secret of at least 32 characters; use cryptographically random material and never commit or expose it to the frontend. The fixed secret in the test profile is only for automated tests.

Authentication is stateless, with no session cookies, HTTP Basic, or form login. CSRF protection is disabled specifically for this bearer-header-only API. Revisit that choice before introducing cookie-based authentication. Frontend CORS access is limited to configured origins and does not bypass JWT authentication.

## Configuration

| Environment variable | Local default |
| --- | --- |
| `SERVER_PORT` | `8080` |
| `SERVER_ADDRESS` | `127.0.0.1` |
| `DB_URL` | `jdbc:h2:file:./data/payment-system` |
| `DB_USERNAME` | `sa` |
| `DB_PASSWORD` | Empty, for embedded local H2 only |
| `JWT_SECRET` | Generated per application startup in the local profile |
| `JWT_ISSUER` | `payment-system` |
| `JWT_AUDIENCE` | `payment-system-api` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` |

The `DB_*` aliases belong to the local profile. CORS origins are comma-separated. Environment variables are read directly; there is no automatic `.env` file loader.

H2 stores local data under `backend/data/` when launched with the commands above, so future entities will survive application restarts. Database files are ignored by source control. Tests use a separate in-memory database. The H2 web console is not enabled.

Hibernate schema updates are enabled only in the local profile for early development. Before switching to PostgreSQL or another database, add its JDBC driver and a dedicated profile, introduce versioned migrations, and validate queries and transaction behavior against that database. Changing the connection URL alone is not a complete migration. This starter does not include a production deployment profile.

## Tests And Packaging

From the backend directory:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

The tests cover startup, an H2/JPA persistence round trip, public health, JWT-protected APIs, valid and invalid signed tokens, required claims, stateless responses, and CORS preflight handling. Controller tests use mocked services to check routes, authentication, validation, merchant identity, status codes, JSON fields, and error responses. Model tests check entity relationships, cascading, DTO mapping, and unique constraints. Repository tests check merchant-scoped lookups, paging, status filters, and attempt ordering. The tests do not require Docker or a separately installed database.

`verify` also creates the executable application JAR. Run it from the backend directory with:

```powershell
java -jar target/payment-system-0.0.1-SNAPSHOT.jar
```

## Next Phase

Implement the services behind these controllers using the repositories: password hashing, login and JWT issuance, payment and attempt state rules, merchant ownership checks, and idempotent payment creation. Financial records, refunds, webhooks, and frontend screens remain later phases.