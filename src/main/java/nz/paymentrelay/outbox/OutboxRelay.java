package nz.paymentrelay.outbox;

import java.time.Clock;
import java.time.Instant;
import nz.paymentrelay.broker.BrokerUnavailableException;
import nz.paymentrelay.broker.MessageBroker;
import nz.paymentrelay.common.Backoff;
import nz.paymentrelay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Polls the outbox and passes pending events to the broker. It's at-least-once: if we crash after
 * publish() but before markPublished(), the event is published again on restart. That's fine
 * because every consumer is idempotent on eventId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final MessageBroker broker;
    private final RelayProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, MessageBroker broker, RelayProperties props,
                       TaskScheduler scheduler, Clock clock) {
        this.outbox = outbox;
        this.broker = broker;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::relayBatch, props.outboxPollInterval());
    }

    public void relayBatch() {
        try {
            Instant now = clock.instant();
            for (OutboxEvent event : outbox.findDue(OutboxEvent.Status.PENDING, now, PageRequest.of(0, props.batchSize()))) {
                try {
                    broker.publish(event.getId(), event.getEventType(), event.getPayload());
                    event.markPublished(clock.instant());
                    outbox.save(event);
                } catch (BrokerUnavailableException e) {
                    event.recordFailure(e.getMessage(),
                            now.plus(Backoff.next(event.getAttempts() + 1, props.initialBackoff(), props.maxBackoff())));
                    outbox.save(event);
                    log.warn("Broker unavailable; outbox event {} will be retried (attempt {})", event.getId(), event.getAttempts());
                    break; // broker is down for everyone, so don't hit it with the rest of the batch
                }
            }
        } catch (RuntimeException e) {
            // an exception here must never stop the scheduled loop
            log.error("Outbox relay iteration failed", e);
        }
    }
}
