package dev.webhookdelivery.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
    private WebhookSignature() {}

    public static String sign(String secret, UUID id, String type, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((id + "\n" + type + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the webhook", e);
        }
    }

    public static boolean valid(String secret, UUID id, String type, byte[] body, String received) {
        if (received == null || received.length() != 64) return false;
        return MessageDigest.isEqual(sign(secret, id, type, body).getBytes(StandardCharsets.US_ASCII),
                                     received.getBytes(StandardCharsets.US_ASCII));
    }
}
