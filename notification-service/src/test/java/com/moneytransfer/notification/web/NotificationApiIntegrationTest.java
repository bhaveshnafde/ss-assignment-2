package com.moneytransfer.notification.web;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The Notification API end to end inside this service: real controllers, real database (H2), real provider mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationApiIntegrationTest {

    @Autowired TestRestTemplate http;

    @AfterEach
    void providerBackToNormal() {
        setProvider(0, 0.0, 0);
    }

    // ---- delivery and repeats ----------------------------------------------------------------------

    @Test
    void notify_is202Sent_andARepeatIs202WithoutSendingAgain() {
        String transferId = newTransferId();

        ResponseEntity<JsonNode> first = notify(transferId, "You sent 100.00 INR");
        ResponseEntity<JsonNode> second = notify(transferId, "You sent 100.00 INR");

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getBody().get("status").asText()).isEqualTo("SENT");
        assertThat(first.getBody().get("attempts").asInt()).isEqualTo(1);
        assertThat(first.getBody().get("notificationId").asText()).startsWith("N-");
        assertThat(second.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getBody().get("notificationId")).isEqualTo(first.getBody().get("notificationId"));

        JsonNode found = forTransfer(transferId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("history")).hasSize(1);   // the provider was called once
    }

    @Test
    void N1_tenIdenticalRequestsAtTheSameMoment_callTheProviderOnce() throws Exception {
        String transferId = newTransferId();
        setProvider(200, 0.0, 0);   // slow provider, so the requests really overlap

        List<ResponseEntity<JsonNode>> responses = inParallel(10, () -> notify(transferId, "You sent 5.00 INR"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(202));
        JsonNode found = forTransfer(transferId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("history")).hasSize(1);
        assertThat(found.get(0).get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void sameTransferWithADifferentMessage_is422() {
        String transferId = newTransferId();
        notify(transferId, "You sent 100.00 INR");

        ResponseEntity<JsonNode> response = notify(transferId, "You sent 999.00 INR");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("NOTIFICATION_MISMATCH");
    }

    // ---- the provider can fail (condition e) -------------------------------------------------------

    @Test
    void N3_providerFailsTwice_thenTheThirdRetrySucceeds_andEveryAttemptIsRecorded() {
        String transferId = newTransferId();
        setProvider(0, 0.0, 2);

        ResponseEntity<JsonNode> first = notify(transferId, "You sent 1.00 INR");
        ResponseEntity<JsonNode> second = notify(transferId, "You sent 1.00 INR");
        ResponseEntity<JsonNode> third = notify(transferId, "You sent 1.00 INR");

        assertThat(first.getStatusCode().value()).isEqualTo(503);
        assertThat(code(first)).isEqualTo("PROVIDER_DOWN");
        assertThat(second.getStatusCode().value()).isEqualTo(503);
        assertThat(third.getStatusCode().value()).isEqualTo(202);
        assertThat(third.getBody().get("attempts").asInt()).isEqualTo(3);

        List<String> outcomes = new ArrayList<>();
        forTransfer(transferId).get(0).get("history").forEach(a -> outcomes.add(a.get("outcome").asText()));
        assertThat(outcomes).containsExactly("FAILED", "FAILED", "SENT");
    }

    @Test
    void providerAlwaysFailing_is503_andTheNotificationIsMarkedFailed() {
        String transferId = newTransferId();
        setProvider(0, 1.0, 0);

        ResponseEntity<JsonNode> response = notify(transferId, "You sent 2.00 INR");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(code(response)).isEqualTo("PROVIDER_DOWN");
        assertThat(response.getBody().at("/error/details/0/attempts").asInt()).isEqualTo(1);
        assertThat(forTransfer(transferId).get(0).get("status").asText()).isEqualTo("FAILED");
    }

    // ---- reads, validation, admin ------------------------------------------------------------------

    @Test
    void unknownTransferHasNoNotifications_andAQueryWithoutParametersIs400() {
        assertThat(forTransfer(newTransferId())).isEmpty();

        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/notifications", JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    void accountHistoryListsNewestFirst() {
        String account = "ACC-N-" + UUID.randomUUID().toString().substring(0, 6);
        String t1 = newTransferId();
        String t2 = newTransferId();
        notify(t1, account, "first");
        notify(t2, account, "second");

        JsonNode found = http.getForObject("/api/v1/notifications?accountId=" + account, JsonNode.class);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).get("transferId").asText()).isEqualTo(t2);
        assertThat(found.get(1).get("message").asText()).isEqualTo("first");
    }

    @Test
    void invalidFields_are400_andAnUnknownChannelIsMalformed() {
        ResponseEntity<JsonNode> invalid = post("{\"transferId\":\"\",\"accountId\":\"ACC-1\",\"channel\":\"SMS\"}");
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(code(invalid)).isEqualTo("VALIDATION_FAILED");

        ResponseEntity<JsonNode> malformed = post("{\"transferId\":\"t\",\"accountId\":\"ACC-1\",\"channel\":\"PIGEON\","
                + "\"template\":\"TRANSFER_COMPLETED\",\"message\":\"hi\"}");
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(code(malformed)).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void invalidProviderSetting_is422() {
        ResponseEntity<JsonNode> response = http.exchange("/api/v1/admin/provider", HttpMethod.PUT,
                json("{\"delayMs\":0,\"failRate\":1.5,\"failNext\":0}"), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("INVALID_SETTING");
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

    private ResponseEntity<JsonNode> notify(String transferId, String message) {
        return notify(transferId, "ACC-1001", message);
    }

    private ResponseEntity<JsonNode> notify(String transferId, String account, String message) {
        return post("{\"transferId\":\"" + transferId + "\",\"accountId\":\"" + account + "\",\"channel\":\"SMS\","
                + "\"template\":\"TRANSFER_COMPLETED\",\"message\":\"" + message + "\"}");
    }

    private ResponseEntity<JsonNode> post(String body) {
        return http.postForEntity("/api/v1/notifications", json(body), JsonNode.class);
    }

    private JsonNode forTransfer(String transferId) {
        return http.getForObject("/api/v1/notifications?transferId=" + transferId, JsonNode.class);
    }

    private void setProvider(long delayMs, double failRate, int failNext) {
        http.exchange("/api/v1/admin/provider", HttpMethod.PUT,
                json("{\"delayMs\":" + delayMs + ",\"failRate\":" + failRate + ",\"failNext\":" + failNext + "}"), JsonNode.class);
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
