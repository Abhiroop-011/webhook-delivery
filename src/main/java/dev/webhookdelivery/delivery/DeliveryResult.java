package dev.webhookdelivery.delivery;

public record DeliveryResult(Outcome outcome, Integer httpStatus, long latencyMs, String detail) {
    public enum Outcome { ACKNOWLEDGED, RETRYABLE, REJECTED, UNKNOWN }

    public static DeliveryResult response(int status, long latencyMs) {
        Outcome outcome;
        if (status >= 200 && status < 300) {
            outcome = Outcome.ACKNOWLEDGED;
        } else if (status == 408 || status == 429 || status >= 500) {
            outcome = Outcome.RETRYABLE;
        } else {
            outcome = Outcome.REJECTED;
        }
        return new DeliveryResult(outcome, status, latencyMs, "HTTP " + status);
    }
}
