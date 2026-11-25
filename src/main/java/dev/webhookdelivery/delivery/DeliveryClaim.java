package dev.webhookdelivery.delivery;

import java.util.UUID;

public record DeliveryClaim(UUID eventId, UUID token, int number, int attemptsUsed,
                            String eventType, String payload) {}
