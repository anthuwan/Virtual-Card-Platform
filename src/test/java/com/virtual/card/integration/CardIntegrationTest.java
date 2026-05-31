package com.virtual.card.integration;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Full-stack integration tests using a real PostgreSQL instance via Testcontainers.
 *
 * <p>These tests exercise the entire HTTP → Service → JOOQ → DB round-trip,
 * verifying that all layers work together correctly. The container is shared
 * across all tests in this class for efficiency.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Card API Integration Tests")
class CardIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("virtualcard_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
    }

    // ─── Card Creation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /cards — creates card and returns 201 with Location header")
    void createCard_returns201() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"cardholderName": "Alice Borg", "initialBalance": 500.00}
                        """)
        .when()
                .post("/cards")
        .then()
                .statusCode(201)
                .header("Location", containsString("/api/v1/cards/"))
                .body("id", notNullValue())
                .body("cardholderName", equalTo("Alice Borg"))
                .body("balance", equalTo(500.0f))
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    @DisplayName("POST /cards — returns 400 when cardholder name is blank")
    void createCard_validatesCardholderName() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"cardholderName": "", "initialBalance": 100.00}
                        """)
        .when()
                .post("/cards")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /cards — returns 400 when initial balance is negative")
    void createCard_rejectsNegativeBalance() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"cardholderName": "Bob", "initialBalance": -10.00}
                        """)
        .when()
                .post("/cards")
        .then()
                .statusCode(400);
    }

    // ─── Card Retrieval ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /cards/{id} — returns 404 for unknown card")
    void getCard_returns404() {
        given()
        .when()
                .get("/cards/" + UUID.randomUUID())
        .then()
                .statusCode(404)
                .body("error", equalTo("CARD_NOT_FOUND"));
    }

    // ─── Spend ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /cards/{id}/spend — deducts balance and returns SUCCESSFUL")
    void spend_successfulDeduction() {
        // Create card
        String cardId = createCard("Carol", 200.00);

        // Spend 75
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"amount": 75.00, "description": "Test purchase"}
                        """)
        .when()
                .post("/cards/" + cardId + "/spend")
        .then()
                .statusCode(200)
                .body("status", equalTo("SUCCESSFUL"))
                .body("type", equalTo("SPEND"))
                .body("amount", equalTo(75.0f));

        // Verify balance updated
        given()
        .when()
                .get("/cards/" + cardId)
        .then()
                .body("balance", equalTo(125.0f));
    }

    @Test
    @DisplayName("POST /cards/{id}/spend — returns DECLINED when insufficient funds")
    void spend_declinedOnInsufficientFunds() {
        String cardId = createCard("David", 50.00);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"amount": 999.00}
                        """)
        .when()
                .post("/cards/" + cardId + "/spend")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"));

        // Balance should remain unchanged
        given()
        .when()
                .get("/cards/" + cardId)
        .then()
                .body("balance", equalTo(50.0f));
    }

    @Test
    @DisplayName("POST /cards/{id}/spend — idempotent: same key returns same transaction")
    void spend_idempotent() {
        String cardId = createCard("Eve", 300.00);
        String idempotencyKey = "spend-idem-" + UUID.randomUUID();

        // First call
        String txId1 = given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body("""
                        {"amount": 100.00}
                        """)
        .when()
                .post("/cards/" + cardId + "/spend")
        .then()
                .statusCode(200)
                .body("status", equalTo("SUCCESSFUL"))
                .extract().path("id");

        // Second call with same key — should return identical response
        String txId2 = given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body("""
                        {"amount": 100.00}
                        """)
        .when()
                .post("/cards/" + cardId + "/spend")
        .then()
                .statusCode(200)
                .body("status", equalTo("SUCCESSFUL"))
                .extract().path("id");

        Assertions.assertEquals(txId1, txId2, "Idempotent calls must return the same transaction ID");

        // Balance should reflect only ONE deduction
        given()
        .when()
                .get("/cards/" + cardId)
        .then()
                .body("balance", equalTo(200.0f)); // 300 - 100 = 200
    }

    // ─── Top-Up ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /cards/{id}/top-up — increases balance")
    void topUp_increasesBalance() {
        String cardId = createCard("Frank", 100.00);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"amount": 50.00}
                        """)
        .when()
                .post("/cards/" + cardId + "/top-up")
        .then()
                .statusCode(200)
                .body("status", equalTo("SUCCESSFUL"))
                .body("type", equalTo("TOP_UP"));

        given()
        .when()
                .get("/cards/" + cardId)
        .then()
                .body("balance", equalTo(150.0f));
    }

    // ─── Card Status ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /cards/{id}/block — blocks active card; spend is then rejected")
    void blockCard_preventsSpend() {
        String cardId = createCard("Grace", 200.00);

        // Block the card
        given()
        .when()
                .patch("/cards/" + cardId + "/block")
        .then()
                .statusCode(200)
                .body("status", equalTo("BLOCKED"));

        // Attempt to spend — expect 422
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"amount": 10.00}
                        """)
        .when()
                .post("/cards/" + cardId + "/spend")
        .then()
                .statusCode(422)
                .body("error", equalTo("CARD_NOT_ACTIVE"));
    }

    // ─── Transaction History ─────────────────────────────────────────────────

    @Test
    @DisplayName("GET /cards/{id}/transactions — returns all transactions in descending order")
    void getTransactions_returnsHistory() {
        String cardId = createCard("Henry", 500.00);

        // Top-up
        given().contentType(ContentType.JSON).body("{\"amount\": 100.00}")
                .post("/cards/" + cardId + "/top-up");
        // Spend
        given().contentType(ContentType.JSON).body("{\"amount\": 50.00}")
                .post("/cards/" + cardId + "/spend");

        given()
        .when()
                .get("/cards/" + cardId + "/transactions")
        .then()
                .statusCode(200)
                .body("size()", equalTo(2))
                // Most recent first
                .body("[0].type", equalTo("SPEND"))
                .body("[1].type", equalTo("TOP_UP"));
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private String createCard(String name, double balance) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("{\"cardholderName\": \"%s\", \"initialBalance\": %.2f}", name, balance))
        .when()
                .post("/cards")
        .then()
                .statusCode(201)
                .extract().path("id");
    }
}
