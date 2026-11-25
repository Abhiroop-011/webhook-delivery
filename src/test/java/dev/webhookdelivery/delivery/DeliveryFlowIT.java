package dev.webhookdelivery.delivery;

import dev.webhookdelivery.config.DeliverySettings;
import dev.webhookdelivery.support.PostgresTestSupport;
import dev.webhookdelivery.warehouse.WarehouseController.Mode;
import dev.webhookdelivery.warehouse.WarehouseRepository;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryFlowIT extends PostgresTestSupport {
    @Autowired DeliverySettings config;
    @Autowired WarehouseRepository orders;
    @LocalServerPort int port;

    private DeliveryResult deliver() throws InterruptedException {
        var claim = jobs.claim().orElseThrow();
        var settings = new DeliverySettings(URI.create("http://localhost:" + port + "/hooks/orders"),
                config.secret(), config.httpTimeout(), config.lease(), config.maxAttempts(),
                config.retryBase(), config.retryCap(), config.pollMs());
        var result = new WebhookClient(settings).send(claim);
        assertThat(jobs.finish(claim, result)).isTrue();
        return result;
    }

    @Test
    void lostAcknowledgmentRetriesOneCommittedOrder() throws Exception {
        UUID id = submit();
        warehouse.changeMode(Mode.DELAY_FIRST_ACK);
        assertThat(deliver().outcome()).isEqualTo(DeliveryResult.Outcome.UNKNOWN);
        assertThat(orders.contains(id)).isTrue();
        makeDue(id);
        assertThat(deliver().httpStatus()).isEqualTo(204);
        assertThat(orders.count()).isOne();
        assertThat(events.history(id).attempts()).extracting("outcome").containsExactly("UNKNOWN", "ACKNOWLEDGED");
    }

    @Test
    void offlineReceiverExhaustsBudgetThenReplayKeepsTheHistoryAndIdentity() throws Exception {
        UUID id = submit();
        warehouse.changeMode(Mode.OFFLINE);
        for (int i = 0; i < config.maxAttempts(); i++) {
            makeDue(id);
            assertThat(deliver().httpStatus()).isEqualTo(503);
        }
        assertThat(events.history(id).event().state()).isEqualTo("DEAD");
        events.replay(id);
        warehouse.changeMode(Mode.HEALTHY);
        assertThat(deliver().httpStatus()).isEqualTo(204);
        var history = events.history(id);
        assertThat(history.event().replayCount()).isOne();
        assertThat(history.event().totalAttempts()).isEqualTo(4);
        assertThat(history.event().state()).isEqualTo("DELIVERED");
        assertThat(orders.contains(id)).isTrue();
    }
}
