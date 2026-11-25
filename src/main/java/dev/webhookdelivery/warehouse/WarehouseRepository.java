package dev.webhookdelivery.warehouse;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseRepository {
    private final JdbcClient db;

    public WarehouseRepository(JdbcClient db) {
        this.db = db;
    }

    public boolean save(UUID eventId, String payload) {
        return db.sql("""
                INSERT INTO warehouse_order(event_id, payload) VALUES (?, CAST(? AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """).params(eventId, payload).update() == 1;
    }

    public boolean contains(UUID id) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM warehouse_order WHERE event_id = ?)")
                .param(id).query(Boolean.class).single();
    }

    public long count() {
        return db.sql("SELECT count(*) FROM warehouse_order").query(Long.class).single();
    }
}
