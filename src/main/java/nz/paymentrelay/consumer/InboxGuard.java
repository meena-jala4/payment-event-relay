package nz.paymentrelay.consumer;

import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbox pattern: save (consumer, eventId) in the same transaction as the consumer's side effect.
 * The unique constraint on that pair means the side effect can only commit once, even if two
 * copies of the event are processed at the same time.
 */
@Component
public class InboxGuard {

    private final ProcessedEventRepository processed;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InboxGuard(ProcessedEventRepository processed, TransactionTemplate tx, Clock clock) {
        this.processed = processed;
        this.tx = tx;
        this.clock = clock;
    }

    /** Quick check so a consumer can skip expensive work (like a vendor call) for a duplicate. */
    public boolean alreadyProcessed(String consumer, String eventId) {
        return processed.existsByConsumerAndEventId(consumer, eventId);
    }

    /** @return true if the action ran and committed, false if the event was already processed */
    public boolean runOnce(String consumer, String eventId, Runnable sideEffect) {
        try {
            Boolean ran = tx.execute(status -> {
                if (processed.existsByConsumerAndEventId(consumer, eventId)) {
                    return false;
                }
                processed.saveAndFlush(new ProcessedEvent(consumer, eventId, clock.instant()));
                sideEffect.run();
                return true;
            });
            return Boolean.TRUE.equals(ran);
        } catch (DataIntegrityViolationException duplicate) {
            return false; // another copy got there first and did the work
        }
    }
}
