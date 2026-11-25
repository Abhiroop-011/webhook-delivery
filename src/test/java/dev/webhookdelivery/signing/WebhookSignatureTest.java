package dev.webhookdelivery.signing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {
    @Test
    void bindsIdentityTypeAndExactBodyBytes() {
        UUID id = UUID.randomUUID();
        byte[] body = "{\"order\":42}".getBytes(StandardCharsets.UTF_8);
        String tag = WebhookSignature.sign("secret", id, "order.created", body);
        assertThat(WebhookSignature.valid("secret", id, "order.created", body, tag)).isTrue();
        assertThat(WebhookSignature.valid("secret", UUID.randomUUID(), "order.created", body, tag)).isFalse();
        assertThat(WebhookSignature.valid("secret", id, "order.cancelled", body, tag)).isFalse();
        assertThat(WebhookSignature.valid("secret", id, "order.created", new byte[]{42}, tag)).isFalse();
        assertThat(WebhookSignature.valid("other", id, "order.created", body, tag)).isFalse();
    }
}
