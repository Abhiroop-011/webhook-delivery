package dev.webhookdelivery.delivery;

import com.sun.net.httpserver.HttpServer;
import dev.webhookdelivery.config.DeliverySettings;
import dev.webhookdelivery.signing.WebhookSignature;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookClientTest {
    private HttpServer server;
    private URI destination;
    private final DeliveryClaim claim = new DeliveryClaim(UUID.randomUUID(), UUID.randomUUID(),
            1, 1, "order.created", "{\"orderId\":\"café-17\"}");

    @BeforeEach
    void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/orders");
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private WebhookClient client() {
        return new WebhookClient(new DeliverySettings(destination, "test-secret", Duration.ofSeconds(2),
                Duration.ofSeconds(8), 5, Duration.ofSeconds(1), Duration.ofSeconds(5), 100));
    }

    @Test
    void receiverCanVerifyTheExactSignedBytes() throws Exception {
        var verified = new AtomicReference<>(false);
        server.createContext("/orders", exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                verified.set(WebhookSignature.valid("test-secret",
                        UUID.fromString(headers.getFirst("Webhook-Id")), headers.getFirst("Webhook-Type"),
                        exchange.getRequestBody().readAllBytes(), headers.getFirst("Webhook-Signature")));
                exchange.sendResponseHeaders(verified.get() ? 204 : 401, -1);
            }
        });
        assertThat(client().send(claim).httpStatus()).isEqualTo(204);
        assertThat(verified.get()).isTrue();
    }

    @Test
    void timeoutLeavesAnUnknownOutcomeEvenWhenTheReceiverReadTheRequest() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/orders", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                received.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            var result = client().send(claim);
            assertThat(received.getCount()).isZero();
            assertThat(result.outcome()).isEqualTo(DeliveryResult.Outcome.UNKNOWN);
            assertThat(result.httpStatus()).isNull();
        } finally {
            release.countDown();
        }
    }

    @Test
    void redirectDoesNotForwardTheSignedEventToAnotherRoute() throws Exception {
        var forwarded = new AtomicInteger();
        server.createContext("/orders", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/elsewhere");
                exchange.sendResponseHeaders(307, -1);
            }
        });
        server.createContext("/elsewhere", exchange -> {
            try (exchange) {
                forwarded.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        assertThat(client().send(claim).outcome()).isEqualTo(DeliveryResult.Outcome.REJECTED);
        assertThat(forwarded.get()).isZero();
    }
}
