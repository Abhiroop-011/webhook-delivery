package dev.webhookdelivery.event;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/events")
@Profile("api")
public class EventController {
    public record Accepted(UUID id) {}
    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Accepted> accept(@RequestHeader("Idempotency-Key") String key,
                                           @RequestBody EventService.Submission request) {
        UUID id = service.accept(key, request);
        return ResponseEntity.accepted().location(URI.create("/events/" + id)).body(new Accepted(id));
    }

    @GetMapping
    public List<EventRepository.Event> latest() {
        return service.latest();
    }

    @GetMapping("/{id}")
    public EventService.History history(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Accepted> replay(@PathVariable UUID id) {
        service.replay(id);
        return ResponseEntity.accepted().body(new Accepted(id));
    }
}
