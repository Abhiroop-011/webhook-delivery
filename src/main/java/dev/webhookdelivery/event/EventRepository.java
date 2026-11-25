package dev.webhookdelivery.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
    public record Event(UUID id, String destination, String eventType, String state,
                        int totalAttempts, int replayCount, Instant createdAt, Instant updatedAt) {}
    public record Attempt(int number, Instant startedAt, Instant finishedAt, String outcome,
                          Integer httpStatus, Long latencyMs, String detail) {}
    public record Receipt(UUID id, boolean matches) {}

    private static final String EVENT_FIELDS =
            "id, destination, event_type, state, total_attempts, replay_count, created_at, updated_at";
    private final JdbcClient db;

    public EventRepository(JdbcClient db) {
        this.db = db;
    }

    public Receipt accept(String key, String destination, String type, String payload) {
        db.sql("""
                INSERT INTO webhook_event(id, ingest_key, destination, event_type, payload)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (ingest_key) DO NOTHING
                """).params(UUID.randomUUID(), key, destination, type, payload).update();
        return db.sql("""
                SELECT id, (destination = ? AND event_type = ? AND payload = CAST(? AS jsonb)) AS matches
                FROM webhook_event WHERE ingest_key = ?
                """).params(destination, type, payload, key).query(Receipt.class).single();
    }

    public Optional<Event> get(UUID id) {
        return db.sql("SELECT " + EVENT_FIELDS + " FROM webhook_event WHERE id = ?")
                .param(id).query(Event.class).optional();
    }

    public List<Event> latest() {
        return db.sql("SELECT " + EVENT_FIELDS + " FROM webhook_event ORDER BY created_at DESC, id LIMIT 50")
                .query(Event.class).list();
    }

    public List<Attempt> attempts(UUID id) {
        return db.sql("""
                SELECT number, started_at, finished_at, outcome, http_status, latency_ms, detail
                FROM delivery_attempt WHERE event_id = ? ORDER BY number
                """).param(id).query(Attempt.class).list();
    }

    public boolean replay(UUID id) {
        return db.sql("""
                UPDATE webhook_event SET state = 'PENDING', due_at = now(), updated_at = now(),
                  attempts_used = 0, replay_count = replay_count + 1
                WHERE id = ? AND state = 'DEAD'
                """).param(id).update() == 1;
    }
}
