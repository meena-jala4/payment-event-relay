package nz.paymentrelay.payment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import nz.paymentrelay.config.PaymentProperties;
import nz.paymentrelay.payment.api.CreatePaymentRequest;
import org.springframework.stereotype.Component;

/** Business rules the annotations can't cover, because they depend on config or the current time. */
@Component
public class PaymentValidator {

    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentValidator(PaymentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void validate(CreatePaymentRequest request) {
        List<FieldViolation> violations = new ArrayList<>();

        String currency = request.currency().toUpperCase();
        if (!properties.supportedCurrencies().contains(currency)) {
            violations.add(new FieldViolation("currency", "unsupported currency; supported: " + properties.supportedCurrencies()));
        } else {
            // NZD has 2 decimal places, JPY has 0. Reject 10.001 NZD instead of quietly rounding the customer's money
            int minorUnits = Currency.getInstance(currency).getDefaultFractionDigits();
            if (request.amount().stripTrailingZeros().scale() > minorUnits) {
                violations.add(new FieldViolation("amount", "must have at most " + minorUnits + " decimal places for " + currency));
            }
        }

        // "today" is the NZ date, not the server's date (a container runs in UTC)
        LocalDate today = LocalDate.now(clock.withZone(properties.businessZone()));
        if (request.requestedExecutionDate().isBefore(today)) {
            violations.add(new FieldViolation("requestedExecutionDate", "must not be in the past (today is " + today + ")"));
        }

        if (request.sourceAccount().equals(request.destination().accountNumber())) {
            violations.add(new FieldViolation("destination.accountNumber", "must differ from sourceAccount"));
        }

        if (!violations.isEmpty()) {
            throw new PaymentValidationException(violations);
        }
    }

    public record FieldViolation(String field, String message) {
    }
}
