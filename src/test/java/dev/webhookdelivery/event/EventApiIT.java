package dev.webhookdelivery.event;

import com.fasterxml.jackson.databind.JsonNode;
import dev.webhookdelivery.support.PostgresTestSupport;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.assertThat;

class EventApiIT extends PostgresTestSupport {
    @Autowired TestRestTemplate http;

    private ResponseEntity<JsonNode> post(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/events", new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void concurrentReplaysReturnOneDurableEventAndConflictsAreRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        String request = """
                {"destination":"warehouse","eventType":"order.created","payload":{"orderId":"42"}}
                """;
        Callable<String> submission = () -> {
            var response = post(key, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            return response.getBody().required("id").asText();
        };
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = pool.invokeAll(IntStream.range(0, 12).mapToObj(i -> submission).toList());
            String id = futures.get(0).get();
            for (var future : futures) assertThat(future.get()).isEqualTo(id);
            assertThat(events.latest()).hasSize(1);
            assertThat(http.getForObject("/events/" + id, JsonNode.class).at("/event/state").asText()).isEqualTo("PENDING");
        } finally {
            pool.shutdownNow();
        }
        assertThat(post(key, request.replace("42", "43")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsUnconfiguredDestinationAndUnknownEvent() {
        assertThat(post("test", """
                {"destination":"another","eventType":"order.created","payload":{}}
                """).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/events/" + UUID.randomUUID(), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
