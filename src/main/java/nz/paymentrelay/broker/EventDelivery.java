package nz.paymentrelay.broker;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One message waiting for one subscriber. Rows retry on their own, so one bad event doesn't block the others. */
@Entity
@Table(name = "event_delivery")
public class EventDelivery {

    public enum Status { PENDING, DELIVERED, DEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String eventId;
    private String eventType;
    private String consumer;
    private String payload;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    protected EventDelivery() {
    }

    public EventDelivery(String eventId, String eventType, String consumer, String payload, Instant now) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.consumer = consumer;
        this.payload = payload;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markDelivered(Instant now) {
        this.status = Status.DELIVERED;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void markDead(String error, Instant now) {
        this.attempts++;
        this.status = Status.DEAD;
        this.lastError = truncate(error);
        this.updatedAt = now;
    }

    /** @return true if this failure used up the retries and the delivery was dead-lettered */
    public boolean recordFailure(String error, int maxAttempts, Instant nextAttempt, Instant now) {
        this.attempts++;
        this.lastError = truncate(error);
        this.updatedAt = now;
        if (attempts >= maxAttempts) {
            this.status = Status.DEAD;
            return true;
        }
        this.nextAttemptAt = nextAttempt;
        return false;
    }

    /** For an operator to use once the underlying problem is fixed. */
    public void replay(Instant now) {
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getConsumer() { return consumer; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getUpdatedAt() { return updatedAt; }
}
