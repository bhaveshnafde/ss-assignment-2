package com.moneytransfer.account.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The Account API end to end inside this service: real controllers, real database (H2), real transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountApiIntegrationTest {

    @Autowired TestRestTemplate http;

    // ---- Challenge 1 -------------------------------------------------------------------------------

    @Test
    void challenge1_25ParallelHoldsOf100Against500_exactly5Succeed() throws Exception {
        String source = openAccount("500.00");
        String destination = openAccount("0.00");

        List<ResponseEntity<JsonNode>> responses = inParallel(25, i ->
                placeHold(UUID.randomUUID().toString(), source, destination, "100.00"));

        long succeeded = responses.stream().filter(r -> r.getStatusCode().value() == 201).count();
        long refused = responses.stream().filter(r -> r.getStatusCode().value() == 409).count();
        assertThat(succeeded).isEqualTo(5);
        assertThat(refused).isEqualTo(20);
        responses.stream().filter(r -> r.getStatusCode().value() == 409).forEach(r ->
                assertThat(code(r)).isEqualTo("INSUFFICIENT_FUNDS"));

        JsonNode account = getAccount(source);
        assertThat(account.get("available").decimalValue()).isEqualByComparingTo("0");
        assertThat(account.get("balance").decimalValue()).isEqualByComparingTo("500");
        assertThat(account.get("onHold").decimalValue()).isEqualByComparingTo("500");
        assertThat(http.getForObject("/api/v1/accounts/" + source + "/holds", JsonNode.class).size()).isEqualTo(5);
    }

    @Test
    void tenIdenticalHoldsForOneTransferAtTheSameMoment_reserveTheMoneyOnce() throws Exception {
        String source = openAccount("1000.00");
        String destination = openAccount("0.00");
        String transferId = UUID.randomUUID().toString();

        List<ResponseEntity<JsonNode>> responses = inParallel(10, i ->
                placeHold(transferId, source, destination, "300.00"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201));
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(1);
        assertThat(getAccount(source).get("available").decimalValue()).isEqualByComparingTo("700");
    }

    // ---- BR2 / BR4 ---------------------------------------------------------------------------------

    @Test
    void BR2_insufficientFunds_is409_andLeavesNoHoldBehind() {
        String source = openAccount("50.00");
        String destination = openAccount("0.00");
        String transferId = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> response = placeHold(transferId, source, destination, "50.01");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(http.getForEntity("/api/v1/holds/" + transferId, JsonNode.class).getStatusCode().value()).isEqualTo(404);
        assertThat(getAccount(source).get("available").decimalValue()).isEqualByComparingTo("50");
    }

    @Test
    void BR4_frozenAccount_is422() {
        ResponseEntity<JsonNode> response = placeHold(UUID.randomUUID().toString(), "ACC-FROZEN", "ACC-2002", "10.00");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void BR4_currencyMismatch_is422() {
        ResponseEntity<JsonNode> response = placeHold(UUID.randomUUID().toString(), "ACC-USD-1", "ACC-2002", "10.00");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void unknownAccount_is404() {
        ResponseEntity<JsonNode> response = placeHold(UUID.randomUUID().toString(), "ACC-NOPE", "ACC-2002", "10.00");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    // ---- hold lifecycle ----------------------------------------------------------------------------

    @Test
    void holdThenCapture_movesTheMoney_andRepeatsChangeNothing() {
        String source = openAccount("1000.00");
        String destination = openAccount("200.00");
        String transferId = UUID.randomUUID().toString();

        assertThat(placeHold(transferId, source, destination, "250.00").getStatusCode().value()).isEqualTo(201);
        assertThat(placeHold(transferId, source, destination, "250.00").getStatusCode().value()).isEqualTo(200);
        assertThat(getAccount(source).get("available").decimalValue()).isEqualByComparingTo("750");
        assertThat(getAccount(source).get("balance").decimalValue()).isEqualByComparingTo("1000");

        assertThat(post("/api/v1/holds/" + transferId + "/capture").getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/v1/holds/" + transferId + "/capture").getStatusCode().value()).isEqualTo(200);

        JsonNode src = getAccount(source);
        JsonNode dst = getAccount(destination);
        assertThat(src.get("balance").decimalValue()).isEqualByComparingTo("750");
        assertThat(src.get("available").decimalValue()).isEqualByComparingTo("750");
        assertThat(dst.get("balance").decimalValue()).isEqualByComparingTo("450");
        assertThat(dst.get("available").decimalValue()).isEqualByComparingTo("450");

        ResponseEntity<JsonNode> releaseAfterCapture = post("/api/v1/holds/" + transferId + "/release");
        assertThat(releaseAfterCapture.getStatusCode().value()).isEqualTo(409);
        assertThat(code(releaseAfterCapture)).isEqualTo("HOLD_ALREADY_CAPTURED");
    }

    @Test
    void holdThenRelease_givesTheMoneyBack_andCaptureIsThenRefused() {
        String source = openAccount("1000.00");
        String destination = openAccount("0.00");
        String transferId = UUID.randomUUID().toString();
        placeHold(transferId, source, destination, "400.00");

        assertThat(post("/api/v1/holds/" + transferId + "/release").getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/v1/holds/" + transferId + "/release").getStatusCode().value()).isEqualTo(200);
        assertThat(getAccount(source).get("available").decimalValue()).isEqualByComparingTo("1000");

        ResponseEntity<JsonNode> captureAfterRelease = post("/api/v1/holds/" + transferId + "/capture");
        assertThat(captureAfterRelease.getStatusCode().value()).isEqualTo(409);
        assertThat(code(captureAfterRelease)).isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(getAccount(destination).get("balance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void releaseOfUnknownHold_is404_soTransferTreatsItAsAlreadyUndone() {
        ResponseEntity<JsonNode> response = post("/api/v1/holds/" + UUID.randomUUID() + "/release");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("HOLD_NOT_FOUND");
    }

    @Test
    void sameTransferIdWithDifferentAmount_is422() {
        String source = openAccount("1000.00");
        String destination = openAccount("0.00");
        String transferId = UUID.randomUUID().toString();
        placeHold(transferId, source, destination, "100.00");

        ResponseEntity<JsonNode> response = placeHold(transferId, source, destination, "999.00");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("HOLD_MISMATCH");
    }

    // ---- accounts ----------------------------------------------------------------------------------

    @Test
    void openingTheSameAccountTwice_is409() {
        String id = openAccount("10.00");
        ResponseEntity<JsonNode> again = http.postForEntity("/api/v1/accounts", json(
                "{\"accountId\":\"" + id + "\",\"holderName\":\"X\",\"currency\":\"INR\",\"initialBalance\":1}"), JsonNode.class);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("ACCOUNT_ALREADY_EXISTS");
    }

    @Test
    void A2_closingAnAccountWithMoney_is409() {
        String id = openAccount("10.00");
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/accounts/" + id + "/status", HttpMethod.PATCH,
                json("{\"status\":\"CLOSED\"}"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("ACCOUNT_NOT_EMPTY");
    }

    @Test
    void invalidFields_are400WithOneDetailPerField() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/accounts",
                json("{\"accountId\":\"x\",\"currency\":\"RUPEES\",\"initialBalance\":-1}"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        response.getBody().at("/error/details").forEach(d -> fields.add(d.get("field").asText()));
        assertThat(fields).containsExactlyInAnyOrder("accountId", "holderName", "currency", "initialBalance");
    }

    @Test
    void healthIsUp() {
        assertThat(http.getForObject("/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private interface Call { ResponseEntity<JsonNode> run(int i); }

    private static List<ResponseEntity<JsonNode>> inParallel(int n, Call call) throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                startTogether.await();
                return call.run(index);
            }));
        }
        startTogether.countDown();
        List<ResponseEntity<JsonNode>> results = new ArrayList<>();
        for (Future<ResponseEntity<JsonNode>> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private String openAccount(String balance) {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/accounts", json(
                "{\"accountId\":\"" + id + "\",\"holderName\":\"Test\",\"currency\":\"INR\",\"initialBalance\":"
                        + new BigDecimal(balance).toPlainString() + "}"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return id;
    }

    private JsonNode getAccount(String id) {
        return http.getForObject("/api/v1/accounts/" + id, JsonNode.class);
    }

    private ResponseEntity<JsonNode> placeHold(String transferId, String source, String destination, String amount) {
        return http.postForEntity("/api/v1/holds", json("{\"transferId\":\"" + transferId + "\",\"sourceAccountId\":\""
                + source + "\",\"destinationAccountId\":\"" + destination + "\",\"amount\":" + amount
                + ",\"currency\":\"INR\"}"), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path) {
        return http.postForEntity(path, json(""), JsonNode.class);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().at("/error/code").asText();
    }
}
