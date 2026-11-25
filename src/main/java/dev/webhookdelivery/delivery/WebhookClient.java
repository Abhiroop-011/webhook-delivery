package dev.webhookdelivery.delivery;

import dev.webhookdelivery.config.DeliverySettings;
import dev.webhookdelivery.signing.WebhookSignature;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class WebhookClient {
    private final DeliverySettings settings;
    private final HttpClient client;

    public WebhookClient(DeliverySettings settings) {
        this.settings = settings;
        client = HttpClient.newBuilder().connectTimeout(settings.httpTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public DeliveryResult send(DeliveryClaim claim) throws InterruptedException {
        byte[] body = claim.payload().getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(settings.secret(), claim.eventId(), claim.eventType(), body);
        var request = HttpRequest.newBuilder(settings.warehouseUrl()).timeout(settings.httpTimeout())
                .header("Content-Type", "application/json")
                .header("Webhook-Id", claim.eventId().toString())
                .header("Webhook-Type", claim.eventType())
                .header("Webhook-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        long start = System.nanoTime();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var bodyStream = response.body()) {
                return DeliveryResult.response(response.statusCode(), elapsedMs(start));
            }
        } catch (IOException e) {
            return new DeliveryResult(DeliveryResult.Outcome.UNKNOWN, null, elapsedMs(start),
                                      e.getClass().getSimpleName());
        }
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
