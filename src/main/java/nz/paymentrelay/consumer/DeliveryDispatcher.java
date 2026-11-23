package nz.paymentrelay.consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import nz.paymentrelay.broker.EventDelivery;
import nz.paymentrelay.broker.EventDeliveryRepository;
import nz.paymentrelay.common.Backoff;
import nz.paymentrelay.common.NonRetryableException;
import nz.paymentrelay.config.RelayProperties;
import nz.paymentrelay.events.PaymentSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Takes messages from the broker table and hands them to the consumers.
 * Each consumer gets its own polling loop, so a slow fraud vendor can't hold up the ledger.
 * Each delivery has its own retry schedule, so one failing event doesn't block the ones behind it.
 * After the max attempts (or a NonRetryableException) a delivery is marked dead. It stays in the
 * table so it can be looked at and replayed.
 */
@Component
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final EventDeliveryRepository deliveries;
    private final List<EventConsumer> consumers;
    private final JsonMapper objectMapper;
    private final RelayProperties props;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public DeliveryDispatcher(EventDeliveryRepository deliveries, List<EventConsumer> consumers,
                              JsonMapper objectMapper, RelayProperties props, TaskScheduler scheduler, Clock clock) {
        this.deliveries = deliveries;
        this.consumers = consumers;
        this.objectMapper = objectMapper;
        this.props = props;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        for (EventConsumer consumer : consumers) {
            scheduler.scheduleWithFixedDelay(() -> dispatchBatch(consumer), props.deliveryPollInterval());
            log.info("Started delivery loop for consumer '{}'", consumer.name());
        }
    }

    public void dispatchBatch(EventConsumer consumer) {
        try {
            List<EventDelivery> due = deliveries.findDue(consumer.name(), EventDelivery.Status.PENDING,
                    clock.instant(), PageRequest.of(0, props.batchSize()));
            for (EventDelivery delivery : due) {
                deliver(consumer, delivery);
            }
        } catch (RuntimeException e) {
            log.error("Delivery loop for '{}' failed; will retry next tick", consumer.name(), e);
        }
    }

    private void deliver(EventConsumer consumer, EventDelivery delivery) {
        Instant now = clock.instant();
        try {
            PaymentSubmittedEvent event = objectMapper.readValue(delivery.getPayload(), PaymentSubmittedEvent.class);
            consumer.handle(event);
            delivery.markDelivered(clock.instant());
        } catch (JacksonException e) {
            delivery.markDead("Unreadable payload: " + e.getOriginalMessage(), now); // bad message that can never be read
            log.error("Dead-lettered delivery {} for '{}': unreadable payload", delivery.getId(), consumer.name());
        } catch (NonRetryableException e) {
            delivery.markDead(e.getMessage(), now);
            log.error("Dead-lettered delivery {} for '{}': {}", delivery.getId(), consumer.name(), e.getMessage());
        } catch (RuntimeException e) {
            Instant next = now.plus(Backoff.next(delivery.getAttempts() + 1, props.initialBackoff(), props.maxBackoff()));
            boolean dead = delivery.recordFailure(e.toString(), props.maxDeliveryAttempts(), next, now);
            if (dead) {
                log.error("Dead-lettered delivery {} for '{}' after {} attempts: {}",
                        delivery.getId(), consumer.name(), delivery.getAttempts(), e.toString());
            } else {
                log.warn("Delivery {} for '{}' failed (attempt {}), retrying at {}: {}",
                        delivery.getId(), consumer.name(), delivery.getAttempts(), next, e.toString());
            }
        }
        deliveries.save(delivery);
    }
}
