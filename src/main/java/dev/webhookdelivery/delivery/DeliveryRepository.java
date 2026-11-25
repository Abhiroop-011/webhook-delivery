package dev.webhookdelivery.delivery;

import dev.webhookdelivery.config.DeliverySettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DeliveryRepository {
    private final JdbcClient db;
    private final DeliverySettings settings;
    private final RetryPolicy retries;

    public DeliveryRepository(JdbcClient db, DeliverySettings settings, RetryPolicy retries) {
        this.db = db;
        this.settings = settings;
        this.retries = retries;
    }

    @Transactional
    public Optional<DeliveryClaim> claim() {
        var claim = db.sql("""
                WITH candidate AS (
                    SELECT id FROM webhook_event WHERE state = 'PENDING' AND due_at <= now()
                    ORDER BY due_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE webhook_event e SET state = 'DELIVERING', claim_token = ?,
                    lease_until = clock_timestamp() + (? * interval '1 millisecond'),
                    attempts_used = attempts_used + 1, total_attempts = total_attempts + 1,
                    updated_at = now()
                FROM candidate WHERE e.id = candidate.id
                RETURNING e.id AS event_id, claim_token AS token, total_attempts AS number,
                          attempts_used, event_type, payload::text AS payload
                """).params(UUID.randomUUID(), settings.lease().toMillis())
                .query(DeliveryClaim.class).optional();
        claim.ifPresent(c -> db.sql("INSERT INTO delivery_attempt(event_id, number) VALUES (?, ?)")
                .params(c.eventId(), c.number()).update());
        return claim;
    }

    @Transactional
    public boolean finish(DeliveryClaim claim, DeliveryResult result) {
        var next = retries.decide(result.outcome(), claim.attemptsUsed());
        // The HTTP call happened outside this transaction. Its lease may already belong to another worker.
        int changed = db.sql("""
                UPDATE webhook_event SET state = ?, due_at = now() + (? * interval '1 millisecond'),
                    claim_token = NULL, lease_until = NULL, updated_at = now()
                WHERE id = ? AND claim_token = ? AND lease_until > clock_timestamp()
                """).params(next.state().name(), next.delayMs(), claim.eventId(), claim.token()).update();
        if (changed == 0) return false;
        db.sql("""
                UPDATE delivery_attempt SET finished_at = now(), outcome = ?, http_status = ?,
                    latency_ms = ?, detail = ? WHERE event_id = ? AND number = ?
                """).params(result.outcome().name(), result.httpStatus(), result.latencyMs(),
                            result.detail(), claim.eventId(), claim.number()).update();
        return true;
    }

    private record Expired(UUID id, int number, int attemptsUsed) {}

    @Transactional
    public boolean recoverOneExpired() {
        var expired = db.sql("""
                SELECT id, total_attempts AS number, attempts_used FROM webhook_event
                WHERE state = 'DELIVERING' AND lease_until <= now()
                ORDER BY lease_until, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(Expired.class).optional();
        if (expired.isEmpty()) return false;
        var previous = expired.get();
        var next = retries.decide(DeliveryResult.Outcome.UNKNOWN, previous.attemptsUsed());
        db.sql("""
                UPDATE delivery_attempt SET finished_at = now(), outcome = 'UNKNOWN',
                    detail = 'Lease expired before the sender recorded a result'
                WHERE event_id = ? AND number = ?
                """).params(previous.id(), previous.number()).update();
        db.sql("""
                UPDATE webhook_event SET state = ?, due_at = now() + (? * interval '1 millisecond'),
                    claim_token = NULL, lease_until = NULL, updated_at = now() WHERE id = ?
                """).params(next.state().name(), next.delayMs(), previous.id()).update();
        return true;
    }
}
