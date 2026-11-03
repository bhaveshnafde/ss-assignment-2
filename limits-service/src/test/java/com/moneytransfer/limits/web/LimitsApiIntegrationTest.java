package com.moneytransfer.limits.web;

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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Limits API end to end inside this service: real controllers, real database (H2), real transactions.
 * Test profile: default limits 200,000 / 100,000; velocity rule 6 transfers per 60 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LimitsApiIntegrationTest {

    @Autowired TestRestTemplate http;

    // ---- reserve and release -----------------------------------------------------------------------

    @Test
    void reserve_is201_aRepeatIs200_andTheAmountIsCountedOnce() {
        String account = newAccount();
        String transferId = newTransferId();

        ResponseEntity<JsonNode> first = reserve(transferId, account, "1500.00");
        ResponseEntity<JsonNode> second = reserve(transferId, account, "1500");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().get("status").asText()).isEqualTo("RESERVED");
        assertThat(first.getBody().get("usedToday").decimalValue()).isEqualByComparingTo("1500");
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(limits(account).get("usedToday").decimalValue()).isEqualByComparingTo("1500");
    }

    @Test
    void release_is204_givesTheAmountBack_andARepeatIsAlso204() {
        String account = newAccount();
        String transferId = newTransferId();
        reserve(transferId, account, "700.00");

        assertThat(delete("/api/v1/limit-reservations/" + transferId).getStatusCode().value()).isEqualTo(204);
        assertThat(delete("/api/v1/limit-reservations/" + transferId).getStatusCode().value()).isEqualTo(204);

        assertThat(limits(account).get("usedToday").decimalValue()).isEqualByComparingTo("0");
        assertThat(http.getForObject("/api/v1/limit-reservations/" + transferId, JsonNode.class)
                .get("status").asText()).isEqualTo("RELEASED");
    }

    @Test
    void releaseOfUnknownTransfer_is404_soTransferTreatsItAsAlreadyUndone() {
        ResponseEntity<JsonNode> response = delete("/api/v1/limit-reservations/" + newTransferId());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("RESERVATION_NOT_FOUND");
    }

    @Test
    void reservingAgainAfterRelease_is409_soAnUndoneTransferCannotComeBack() {
        String account = newAccount();
        String transferId = newTransferId();
        reserve(transferId, account, "100.00");
        delete("/api/v1/limit-reservations/" + transferId);

        ResponseEntity<JsonNode> again = reserve(transferId, account, "100.00");

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("RESERVATION_RELEASED");
        assertThat(limits(account).get("usedToday").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void sameTransferWithDifferentAmount_is422() {
        String account = newAccount();
        String transferId = newTransferId();
        reserve(transferId, account, "100.00");

        ResponseEntity<JsonNode> response = reserve(transferId, account, "999.00");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("RESERVATION_MISMATCH");
    }

    // ---- BR3 / BR1 ---------------------------------------------------------------------------------

    @Test
    void BR3_reservationThatWouldPassTheDailyLimit_is422_andUsageIsUnchanged() {
        String account = newAccount();
        setLimits(account, "500.00", "500.00");
        reserve(newTransferId(), account, "300.00");

        ResponseEntity<JsonNode> refused = reserve(newTransferId(), account, "300.00");

        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(code(refused)).isEqualTo("LIMIT_EXCEEDED");
        assertThat(refused.getBody().at("/error/message").asText()).contains("used 300.00");
        JsonNode status = limits(account);
        assertThat(status.get("usedToday").decimalValue()).isEqualByComparingTo("300");
        assertThat(status.get("remaining").decimalValue()).isEqualByComparingTo("200");
    }

    @Test
    void BR3_25ParallelReservationsOf100AgainstALimitOf500_exactly5Succeed() throws Exception {
        String account = newAccount();
        setLimits(account, "500.00", "500.00");

        List<ResponseEntity<JsonNode>> responses = inParallel(25, () -> reserve(newTransferId(), account, "100.00"));

        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(5);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 422).count()).isEqualTo(20);
        assertThat(limits(account).get("usedToday").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    void tenIdenticalReservationsAtTheSameMoment_countTheAmountOnce() throws Exception {
        String account = newAccount();
        String transferId = newTransferId();

        List<ResponseEntity<JsonNode>> responses = inParallel(10, () -> reserve(transferId, account, "250.00"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201));
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(1);
        assertThat(limits(account).get("usedToday").decimalValue()).isEqualByComparingTo("250");
    }

    @Test
    void BR1_amountAboveThePerTransferLimit_is422() {
        ResponseEntity<JsonNode> response = reserve(newTransferId(), newAccount(), "100000.01");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void seededDemoAccountHasItsPersonalLimit() {
        JsonNode acc2002 = limits("ACC-2002");
        assertThat(acc2002.get("dailyLimit").decimalValue()).isEqualByComparingTo("2000");
        assertThat(acc2002.get("personal").asBoolean()).isTrue();
    }

    // ---- F1 (fraud) --------------------------------------------------------------------------------

    @Test
    void F1_theSeventhTransferWithinAMinute_isRefusedAsVelocityLimit() {
        String account = newAccount();
        for (int i = 0; i < 6; i++) {
            assertThat(reserve(newTransferId(), account, "1.00").getStatusCode().value()).isEqualTo(201);
        }

        ResponseEntity<JsonNode> seventh = reserve(newTransferId(), account, "1.00");

        assertThat(seventh.getStatusCode().value()).isEqualTo(422);
        assertThat(code(seventh)).isEqualTo("VELOCITY_LIMIT");
        assertThat(limits(account).get("usedToday").decimalValue()).isEqualByComparingTo("6");
    }

    // ---- limits management and validation ---------------------------------------------------------

    @Test
    void personalLimitsCanBeSetAndCleared() {
        String account = newAccount();
        assertThat(setLimits(account, "1000.00", "400.00").get("personal").asBoolean()).isTrue();

        assertThat(delete("/api/v1/limits/" + account).getStatusCode().value()).isEqualTo(204);

        JsonNode back = limits(account);
        assertThat(back.get("personal").asBoolean()).isFalse();
        assertThat(back.get("dailyLimit").decimalValue()).isEqualByComparingTo("200000");
    }

    @Test
    void perTransferLimitAboveDailyLimit_is422() {
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/limits/" + newAccount(), HttpMethod.PUT,
                json("{\"dailyLimit\":100,\"perTransferLimit\":200}"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("INVALID_LIMIT");
    }

    @Test
    void invalidFields_are400_andZeroAmountIs422() {
        ResponseEntity<JsonNode> invalid = http.postForEntity("/api/v1/limit-reservations",
                json("{\"transferId\":\"\",\"currency\":\"RUPEES\"}"), JsonNode.class);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(code(invalid)).isEqualTo("VALIDATION_FAILED");

        ResponseEntity<JsonNode> zero = reserve(newTransferId(), newAccount(), "0");
        assertThat(zero.getStatusCode().value()).isEqualTo(422);
        assertThat(code(zero)).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void healthIsUp() {
        assertThat(http.getForObject("/health", JsonNode.class).get("status").asText()).isEqualTo("UP");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private interface Call { ResponseEntity<JsonNode> run(); }

    private static List<ResponseEntity<JsonNode>> inParallel(int n, Call call) throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                return call.run();
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

    private ResponseEntity<JsonNode> reserve(String transferId, String account, String amount) {
        return http.postForEntity("/api/v1/limit-reservations", json("{\"transferId\":\"" + transferId
                + "\",\"accountId\":\"" + account + "\",\"amount\":" + amount + ",\"currency\":\"INR\"}"), JsonNode.class);
    }

    private JsonNode setLimits(String account, String daily, String perTransfer) {
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/limits/" + account, HttpMethod.PUT,
                json("{\"dailyLimit\":" + daily + ",\"perTransferLimit\":" + perTransfer + "}"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private JsonNode limits(String account) {
        return http.getForObject("/api/v1/limits/" + account, JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String path) {
        return http.exchange(path, HttpMethod.DELETE, null, JsonNode.class);
    }

    private static String newAccount() {
        return "ACC-T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String newTransferId() {
        return UUID.randomUUID().toString();
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
