package dev.webhookdelivery.warehouse;

import dev.webhookdelivery.config.DeliverySettings;
import dev.webhookdelivery.signing.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("warehouse")
public class WarehouseController {
    public enum Mode { HEALTHY, OFFLINE, DELAY_FIRST_ACK }
    public record Status(Mode mode, long orders) {}
    public record Receipt(UUID eventId, boolean saved) {}

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.HEALTHY);
    private final DeliverySettings settings;
    private final WarehouseRepository orders;

    public WarehouseController(DeliverySettings settings, WarehouseRepository orders) {
        this.settings = settings;
        this.orders = orders;
    }

    @PostMapping("/hooks/orders")
    public ResponseEntity<Void> receive(@RequestHeader("Webhook-Id") UUID id,
            @RequestHeader("Webhook-Type") String type, @RequestHeader("Webhook-Signature") String signature,
            @RequestBody byte[] body) throws InterruptedException {
        if (!WebhookSignature.valid(settings.secret(), id, type, body, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }
        Mode current = mode.get();
        if (current == Mode.OFFLINE) return ResponseEntity.status(503).build();
        boolean inserted = orders.save(id, new String(body, StandardCharsets.UTF_8));
        if (inserted && current == Mode.DELAY_FIRST_ACK) {
            // The order is already committed. Let the sender time out waiting for this response.
            Thread.sleep(settings.httpTimeout().toMillis() + 1000);
        }
        return ResponseEntity.noContent().build();
    }

    @CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080"})
    @GetMapping("/demo")
    public Status status() {
        return new Status(mode.get(), orders.count());
    }

    @CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080"})
    @PostMapping(value = "/demo/mode/{value}", consumes = "application/json")
    public Status changeMode(@PathVariable Mode value) {
        mode.set(value);
        return status();
    }

    @CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080"})
    @GetMapping("/demo/orders/{id}")
    public Receipt receipt(@PathVariable UUID id) {
        return new Receipt(id, orders.contains(id));
    }
}
