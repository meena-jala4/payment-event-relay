package nz.paymentrelay.broker;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import nz.paymentrelay.consumer.EventConsumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stand-in broker that behaves like a real one: durable (it's a table, so it survives restarts),
 * fan-out (one copy per subscriber) and at-least-once (it doesn't de-duplicate, consumers have to cope).
 */
@Component
public class DatabaseBackedBroker implements MessageBroker {

    private final EventDeliveryRepository deliveries;
    private final BrokerAvailability availability;
    private final List<EventConsumer> subscribers;
    private final Clock clock;

    public DatabaseBackedBroker(EventDeliveryRepository deliveries, BrokerAvailability availability,
                                List<EventConsumer> subscribers, Clock clock) {
        this.deliveries = deliveries;
        this.availability = availability;
        this.subscribers = subscribers;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void publish(String eventId, String eventType, String payload) {
        if (!availability.isAvailable()) {
            throw new BrokerUnavailableException("Broker is unavailable (simulated outage)");
        }
        Instant now = clock.instant();
        deliveries.saveAll(subscribers.stream()
                .map(s -> new EventDelivery(eventId, eventType, s.name(), payload, now))
                .toList());
    }
}
