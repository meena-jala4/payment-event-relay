package nz.paymentrelay.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(
        Duration outboxPollInterval,
        Duration deliveryPollInterval,
        int batchSize,
        int maxDeliveryAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Broker broker) {

    public record Broker(boolean available) {
    }
}
