package nz.paymentrelay.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED }

    @Id
    private String id;
    private String aggregateId;
    private String eventType;
    private String payload;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String id, String aggregateId, String eventType, String payload, Instant now) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void markPublished(Instant now) {
        this.status = Status.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /** Outbox rows never give up, because an accepted payment has to be published eventually. We'd alert on how old they get. */
    public void recordFailure(String error, Instant nextAttempt) {
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttempt;
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }

    public String getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
}
