package nz.paymentrelay.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Published once for each accepted payment. The eventId stays the same on redelivery, which is
 * what lets every consumer spot duplicates.
 */
public record PaymentSubmittedEvent(
        String eventId,
        String paymentId,
        String sourceAccount,
        String destinationName,
        String destinationAccount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String reference,
        LocalDate requestedExecutionDate,
        Instant occurredAt) {

    public static final String TYPE = "PaymentSubmitted";
}
