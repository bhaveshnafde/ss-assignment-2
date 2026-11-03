package com.moneytransfer.ledger.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The Ledger API end to end inside this service: real controllers, real database (H2), real transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LedgerApiIntegrationTest {

    @Autowired TestRestTemplate http;

    @AfterEach
    void resetFaults() {
        setFaults(false, 0);
    }

    // ---- posting -----------------------------------------------------------------------------------

    @Test
    void postingAJournal_is201_andARepeatIs200WithTheSameJournal() {
        String transferId = newTransferId();

        ResponseEntity<JsonNode> first = post(transferId, "ACC-A", "ACC-B", "1500.00");
        ResponseEntity<JsonNode> second = post(transferId, "ACC-A", "ACC-B", "1500");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().get("type").asText()).isEqualTo("TRANSFER");
        assertThat(first.getBody().get("journalId").asText()).startsWith("J-");
        assertThat(first.getBody().get("entries")).hasSize(2);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("journalId")).isEqualTo(first.getBody().get("journalId"));
        assertThat(journalsFor(transferId)).hasSize(1);
    }

    @Test
    void tenIdenticalPostsAtTheSameMoment_recordOneJournal() throws Exception {
        String transferId = newTransferId();

        List<ResponseEntity<JsonNode>> responses = inParallel(10, () -> post(transferId, "ACC-A", "ACC-B", "300.00"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201));
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201).count()).isEqualTo(1);
        Set<String> ids = responses.stream().map(r -> r.getBody().get("journalId").asText()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(journalsFor(transferId)).hasSize(1);
    }

    @Test
    void sameTransferWithDifferentLines_is422() {
        String transferId = newTransferId();
        post(transferId, "ACC-A", "ACC-B", "100.00");

        ResponseEntity<JsonNode> response = post(transferId, "ACC-A", "ACC-B", "999.00");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("JOURNAL_MISMATCH");
    }

    @Test
    void BR5_unbalancedJournal_is422_andNothingIsRecorded() {
        String transferId = newTransferId();
        ResponseEntity<JsonNode> response = postRaw("{\"transferId\":\"" + transferId + "\",\"currency\":\"INR\",\"entries\":["
                + "{\"accountId\":\"ACC-A\",\"direction\":\"DEBIT\",\"amount\":100},"
                + "{\"accountId\":\"ACC-B\",\"direction\":\"CREDIT\",\"amount\":90}]}");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("UNBALANCED_JOURNAL");
        assertThat(http.getForEntity("/api/v1/journals/" + transferId, JsonNode.class).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void BR5_oneSidedJournal_is422() {
        ResponseEntity<JsonNode> response = postRaw("{\"transferId\":\"" + newTransferId() + "\",\"currency\":\"INR\",\"entries\":["
                + "{\"accountId\":\"ACC-A\",\"direction\":\"DEBIT\",\"amount\":100}]}");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("UNBALANCED_JOURNAL");
    }

    @Test
    void invalidFields_are400_andAnUnknownDirectionIsMalformed() {
        ResponseEntity<JsonNode> invalid = postRaw("{\"transferId\":\"\",\"currency\":\"RUPEES\",\"entries\":[]}");
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(code(invalid)).isEqualTo("VALIDATION_FAILED");

        ResponseEntity<JsonNode> malformed = postRaw("{\"transferId\":\"t\",\"currency\":\"INR\",\"entries\":["
                + "{\"accountId\":\"ACC-A\",\"direction\":\"SIDEWAYS\",\"amount\":1}]}");
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(code(malformed)).isEqualTo("MALFORMED_REQUEST");
    }

    // ---- reversal ----------------------------------------------------------------------------------

    @Test
    void reversal_is201_swapsDirections_andARepeatIs200() {
        String transferId = newTransferId();
        String originalId = post(transferId, "ACC-A", "ACC-B", "250.00").getBody().get("journalId").asText();

        ResponseEntity<JsonNode> first = http.postForEntity("/api/v1/journals/" + transferId + "/reversal", json(""), JsonNode.class);
        ResponseEntity<JsonNode> second = http.postForEntity("/api/v1/journals/" + transferId + "/reversal", json(""), JsonNode.class);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().get("type").asText()).isEqualTo("REVERSAL");
        assertThat(first.getBody().get("reverses").asText()).isEqualTo(originalId);
        assertThat(first.getBody().at("/entries/0/accountId").asText()).isEqualTo("ACC-A");
        assertThat(first.getBody().at("/entries/0/direction").asText()).isEqualTo("CREDIT");
        assertThat(first.getBody().at("/entries/1/direction").asText()).isEqualTo("DEBIT");
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("journalId")).isEqualTo(first.getBody().get("journalId"));

        JsonNode both = journalsFor(transferId);
        assertThat(both).hasSize(2);
        assertThat(both.get(0).get("type").asText()).isEqualTo("TRANSFER");
        assertThat(both.get(0).get("reversedBy").asText()).isEqualTo(first.getBody().get("journalId").asText());
        assertThat(both.get(1).get("type").asText()).isEqualTo("REVERSAL");
    }

    @Test
    void reversalOfUnknownTransfer_is404_soTransferTreatsItAsAlreadyUndone() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/journals/" + newTransferId() + "/reversal",
                json(""), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(code(response)).isEqualTo("JOURNAL_NOT_FOUND");
    }

    // ---- account history ---------------------------------------------------------------------------

    @Test
    void accountHistory_listsItsLinesNewestFirst_includingReversals() {
        String account = "ACC-H-" + UUID.randomUUID().toString().substring(0, 6);
        String t1 = newTransferId();
        String t2 = newTransferId();
        post(t1, account, "ACC-B", "100.00");
        post(t2, "ACC-B", account, "40.00");
        http.postForEntity("/api/v1/journals/" + t1 + "/reversal", json(""), JsonNode.class);

        JsonNode lines = http.getForObject("/api/v1/journals?accountId=" + account, JsonNode.class);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).get("type").asText()).isEqualTo("REVERSAL");
        assertThat(lines.get(0).get("direction").asText()).isEqualTo("CREDIT");
        assertThat(lines.get(1).get("transferId").asText()).isEqualTo(t2);
        assertThat(lines.get(2).get("transferId").asText()).isEqualTo(t1);
        assertThat(lines.get(2).get("direction").asText()).isEqualTo("DEBIT");
    }

    @Test
    void accountHistoryWithoutAccountId_is400() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/journals", JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("MISSING_PARAMETER");
    }

    // ---- fault injection (Challenge 3C demo) -------------------------------------------------------

    @Test
    void faultSwitch_failsPostsWith503_butReversalsKeepWorking() {
        String existing = newTransferId();
        post(existing, "ACC-A", "ACC-B", "10.00");
        setFaults(true, 0);

        ResponseEntity<JsonNode> refused = post(newTransferId(), "ACC-A", "ACC-B", "10.00");
        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(code(refused)).isEqualTo("LEDGER_FORCED_FAILURE");

        assertThat(http.postForEntity("/api/v1/journals/" + existing + "/reversal", json(""), JsonNode.class)
                .getStatusCode().value()).isEqualTo(201);
        assertThat(http.postForEntity("/api/v1/journals/" + newTransferId() + "/reversal", json(""), JsonNode.class)
                .getStatusCode().value()).isEqualTo(404);
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

    private ResponseEntity<JsonNode> post(String transferId, String debit, String credit, String amount) {
        return postRaw("{\"transferId\":\"" + transferId + "\",\"currency\":\"INR\",\"entries\":["
                + "{\"accountId\":\"" + debit + "\",\"direction\":\"DEBIT\",\"amount\":" + amount + "},"
                + "{\"accountId\":\"" + credit + "\",\"direction\":\"CREDIT\",\"amount\":" + amount + "}]}");
    }

    private ResponseEntity<JsonNode> postRaw(String body) {
        return http.postForEntity("/api/v1/journals", json(body), JsonNode.class);
    }

    private JsonNode journalsFor(String transferId) {
        return http.getForObject("/api/v1/journals/" + transferId, JsonNode.class);
    }

    private void setFaults(boolean failPosts, long delayMs) {
        http.exchange("/api/v1/admin/faults", HttpMethod.PUT,
                json("{\"failPosts\":" + failPosts + ",\"postDelayMs\":" + delayMs + "}"), JsonNode.class);
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
