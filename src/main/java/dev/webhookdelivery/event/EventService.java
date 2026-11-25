package dev.webhookdelivery.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventService {
    public record Submission(String destination, String eventType, JsonNode payload) {}
    public record History(EventRepository.Event event, List<EventRepository.Attempt> attempts) {}
    private final EventRepository events;

    public EventService(EventRepository events) {
        this.events = events;
    }

    @Transactional
    public UUID accept(String key, Submission request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply a 1–128 character Idempotency-Key");
        }
        if (request == null || !"warehouse".equals(request.destination()) || request.eventType() == null
                || !request.eventType().matches("[a-zA-Z0-9._-]{1,80}") || request.payload() == null
                || !request.payload().isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected warehouse, an event type, and an object payload");
        }
        String payload = request.payload().toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Payload exceeds 64 KiB");
        }
        var receipt = events.accept(key, request.destination(), request.eventType(), payload);
        if (!receipt.matches()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This key belongs to a different event");
        }
        return receipt.id();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History history(UUID id) {
        return new History(events.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown event")), events.attempts(id));
    }

    public List<EventRepository.Event> latest() {
        return events.latest();
    }

    @Transactional
    public void replay(UUID id) {
        if (!events.replay(id)) {
            if (events.get(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown event");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Replay requires a dead event");
        }
    }
}
