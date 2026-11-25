package dev.webhookdelivery.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("delivery")
public record DeliverySettings(URI warehouseUrl, String secret, Duration httpTimeout,
                               Duration lease, int maxAttempts, Duration retryBase,
                               Duration retryCap, long pollMs) {
    public DeliverySettings {
        if (secret == null || secret.isBlank() || maxAttempts < 1 || pollMs < 1
                || httpTimeout.toMillis() < 1 || lease.compareTo(httpTimeout) <= 0
                || retryBase.toMillis() < 1 || retryCap.compareTo(retryBase) < 0
                || retryCap.toMillis() > Duration.ofDays(1).toMillis()) {
            throw new IllegalArgumentException("Check secret, timeouts, and retry limits");
        }
    }
}
