package nz.paymentrelay.payment.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        String id,
        String status,
        String sourceAccount,
        CreatePaymentRequest.Destination destination,
        // Serialised as a JSON string so clients (esp. JavaScript) never turn it into a float.
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String reference,
        LocalDate requestedExecutionDate,
        Instant createdAt,
        FraudScreening fraudScreening) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FraudScreening(
            String status, // PENDING | COMPLETED
            String decision,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal riskScore,
            List<Reason> reasons,
            Instant checkedAt) {
    }

    public record Reason(String code, String description) {
    }
}
