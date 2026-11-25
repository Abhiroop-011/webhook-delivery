package dev.webhookdelivery.delivery;

import dev.webhookdelivery.support.PostgresTestSupport;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerConcurrencyIT extends PostgresTestSupport {
    @Test
    void workersCompetingForOneEventGetOneLease() throws Exception {
        submit();
        var pool = Executors.newFixedThreadPool(8);
        try {
            Callable<Boolean> work = () -> jobs.claim().isPresent();
            var results = pool.invokeAll(IntStream.range(0, 16).mapToObj(i -> work).toList());
            int owners = 0;
            for (var result : results) if (result.get()) owners++;
            assertThat(owners).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredOwnerCannotChangeTheAcknowledgedResult() {
        UUID id = submit();
        var formerOwner = jobs.claim().orElseThrow();
        db.update("UPDATE webhook_event SET lease_until = now() - interval '1 second' WHERE id = ?", id);
        assertThat(jobs.recoverOneExpired()).isTrue();
        makeDue(id);
        var currentOwner = jobs.claim().orElseThrow();
        assertThat(currentOwner.token()).isNotEqualTo(formerOwner.token());
        assertThat(jobs.finish(currentOwner, DeliveryResult.response(204, 1))).isTrue();
        assertThat(jobs.finish(formerOwner, DeliveryResult.response(503, 1))).isFalse();
        var history = events.history(id);
        assertThat(history.event().state()).isEqualTo("DELIVERED");
        assertThat(history.attempts()).extracting("outcome").containsExactly("UNKNOWN", "ACKNOWLEDGED");
    }
}
