package dev.webhookdelivery.support;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.webhookdelivery.delivery.DeliveryRepository;
import dev.webhookdelivery.event.EventService;
import dev.webhookdelivery.warehouse.WarehouseController;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "delivery.secret=test-signature-key", "delivery.retry-base=1ms", "delivery.retry-cap=1ms",
        "delivery.http-timeout=700ms", "delivery.lease=8s", "delivery.max-attempts=3"})
@ActiveProfiles({"api", "warehouse"})
public abstract class PostgresTestSupport {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired protected JdbcTemplate db;
    @Autowired protected EventService events;
    @Autowired protected DeliveryRepository jobs;
    @Autowired protected WarehouseController warehouse;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void resetTestDatabase() {
        db.execute("TRUNCATE warehouse_order, delivery_attempt, webhook_event");
        warehouse.changeMode(WarehouseController.Mode.HEALTHY);
    }

    protected UUID submit() {
        var payload = JsonNodeFactory.instance.objectNode().put("orderId", "A104");
        return events.accept(UUID.randomUUID().toString(), new EventService.Submission("warehouse", "order.created", payload));
    }

    protected void makeDue(UUID id) {
        db.update("UPDATE webhook_event SET due_at = now() WHERE id = ?", id);
    }
}
