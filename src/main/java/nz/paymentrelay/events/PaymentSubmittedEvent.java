package nz.paymentrelay.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Published once per accepted payment. eventId is stable across redeliveries, which is what
 * lets every consumer de-duplicate.
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
