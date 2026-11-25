package dev.webhookdelivery.delivery;

import dev.webhookdelivery.config.DeliverySettings;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
    public enum Next { DELIVERED, PENDING, DEAD }
    public record Decision(Next state, long delayMs) {}
    private final DeliverySettings settings;

    public RetryPolicy(DeliverySettings settings) {
        this.settings = settings;
    }

    public Decision decide(DeliveryResult.Outcome outcome, int used) {
        if (outcome == DeliveryResult.Outcome.ACKNOWLEDGED) return new Decision(Next.DELIVERED, 0);
        if (outcome == DeliveryResult.Outcome.REJECTED || used >= settings.maxAttempts()) {
            return new Decision(Next.DEAD, 0);
        }
        long ceiling = settings.retryBase().toMillis();
        long cap = settings.retryCap().toMillis();
        for (int attempt = 1; attempt < used && ceiling < cap; attempt++) {
            ceiling = Math.min(cap, ceiling * 2);
        }
        long delay = ThreadLocalRandom.current().nextLong(Math.max(1, ceiling / 2), ceiling + 1);
        return new Decision(Next.PENDING, delay);
    }
}
