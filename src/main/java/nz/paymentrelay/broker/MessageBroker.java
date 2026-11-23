package nz.paymentrelay.broker;

/**
 * The boundary between our service and the messaging system. For this exercise it's a durable
 * stub; in production Kafka, RabbitMQ or Azure Service Bus would go behind this interface.
 */
public interface MessageBroker {

    /** @throws BrokerUnavailableException if the broker can't take the message right now (the caller retries) */
    void publish(String eventId, String eventType, String payload);
}
