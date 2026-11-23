package nz.paymentrelay.consumer;

import nz.paymentrelay.events.PaymentSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Fake notification service that always works. */
@Component
public class NotificationConsumer implements EventConsumer {

    public static final String NAME = "notification";
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final InboxGuard inbox;

    public NotificationConsumer(InboxGuard inbox) {
        this.inbox = inbox;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handle(PaymentSubmittedEvent event) {
        boolean sent = inbox.runOnce(NAME, event.eventId(), () ->
                // a real version would call a notification provider and pass the eventId as that provider's
                // idempotency key, because a database transaction alone can't make an email exactly-once
                log.info("NOTIFY customer: payment {} of {} {} to {} submitted",
                        event.paymentId(), event.amount().toPlainString(), event.currency(), event.destinationName()));
        if (!sent) {
            log.debug("Duplicate event {} ignored by {}", event.eventId(), NAME);
        }
    }
}
