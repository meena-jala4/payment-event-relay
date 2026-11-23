package nz.paymentrelay.ledger;

import java.time.Clock;
import nz.paymentrelay.consumer.EventConsumer;
import nz.paymentrelay.consumer.InboxGuard;
import nz.paymentrelay.events.PaymentSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Posts the debit. Two separate checks stop it posting twice:
 *  1. InboxGuard, which de-duplicates by eventId
 *  2. UNIQUE(payment_id) on ledger_entry, which de-duplicates by payment, so even a re-published
 *     event with a new eventId can't post the same payment again
 */
@Component
public class LedgerConsumer implements EventConsumer {

    public static final String NAME = "ledger";
    private static final Logger log = LoggerFactory.getLogger(LedgerConsumer.class);

    private final InboxGuard inbox;
    private final LedgerEntryRepository ledger;
    private final Clock clock;

    public LedgerConsumer(InboxGuard inbox, LedgerEntryRepository ledger, Clock clock) {
        this.inbox = inbox;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handle(PaymentSubmittedEvent event) {
        boolean posted = inbox.runOnce(NAME, event.eventId(), () -> {
            if (ledger.existsByPaymentId(event.paymentId())) {
                return;
            }
            ledger.saveAndFlush(LedgerEntry.debit(event.paymentId(), event.sourceAccount(),
                    event.amount(), event.currency(), clock.instant()));
            log.info("LEDGER debit posted for payment {}", event.paymentId());
        });
        if (!posted) {
            log.info("Duplicate event {} for payment {} ignored by ledger", event.eventId(), event.paymentId());
        }
    }
}
