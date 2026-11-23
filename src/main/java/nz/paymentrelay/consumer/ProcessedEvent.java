package nz.paymentrelay.consumer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String consumer;
    private String eventId;
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String consumer, String eventId, Instant processedAt) {
        this.consumer = consumer;
        this.eventId = eventId;
        this.processedAt = processedAt;
    }
}
