package nz.paymentrelay.consumer;

import nz.paymentrelay.events.PaymentSubmittedEvent;

/**
 * A downstream subscriber. Rules:
 * - must be idempotent on event.eventId(), because it will see duplicates
 * - throw NonRetryableException for permanent failures; anything else is retried with backoff
 */
public interface EventConsumer {

    String name();

    void handle(PaymentSubmittedEvent event);
}
