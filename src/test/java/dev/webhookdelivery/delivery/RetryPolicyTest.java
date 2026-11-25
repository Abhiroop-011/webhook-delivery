package dev.webhookdelivery.delivery;

import dev.webhookdelivery.config.DeliverySettings;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
    private final RetryPolicy retries = new RetryPolicy(new DeliverySettings(
            URI.create("http://localhost/orders"), "test", Duration.ofSeconds(1), Duration.ofSeconds(3),
            5, Duration.ofMillis(100), Duration.ofMillis(300), 10));

    @Test
    void retriesTransientErrorsWithBoundedJitter() {
        assertThat(retries.decide(DeliveryResult.Outcome.RETRYABLE, 1).delayMs()).isBetween(50L, 100L);
        assertThat(retries.decide(DeliveryResult.Outcome.UNKNOWN, 2).delayMs()).isBetween(100L, 200L);
        assertThat(retries.decide(DeliveryResult.Outcome.UNKNOWN, 4).delayMs()).isBetween(150L, 300L);
    }

    @Test
    void lastAttemptMayStillSucceedButUnknownDoesNotRetryForever() {
        assertThat(retries.decide(DeliveryResult.Outcome.UNKNOWN, 5).state()).isEqualTo(RetryPolicy.Next.DEAD);
        assertThat(retries.decide(DeliveryResult.Outcome.ACKNOWLEDGED, 5).state()).isEqualTo(RetryPolicy.Next.DELIVERED);
        assertThat(retries.decide(DeliveryResult.Outcome.REJECTED, 1).state()).isEqualTo(RetryPolicy.Next.DEAD);
    }

    @Test
    void classifiesResponses() {
        assertThat(DeliveryResult.response(204, 1).outcome()).isEqualTo(DeliveryResult.Outcome.ACKNOWLEDGED);
        for (int code : new int[]{408, 429, 500, 503}) {
            assertThat(DeliveryResult.response(code, 1).outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE);
        }
        for (int code : new int[]{302, 400, 401, 404}) {
            assertThat(DeliveryResult.response(code, 1).outcome()).isEqualTo(DeliveryResult.Outcome.REJECTED);
        }
    }
}
