package nz.paymentrelay.payment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import nz.paymentrelay.config.PaymentProperties;
import nz.paymentrelay.payment.api.CreatePaymentRequest;
import org.springframework.stereotype.Component;

/** Business rules that annotations can't express on their own because they depend on config or time. */
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
            // NZD has 2 minor digits, JPY has 0: reject 10.001 NZD rather than silently rounding someone's money.
            int minorUnits = Currency.getInstance(currency).getDefaultFractionDigits();
            if (request.amount().stripTrailingZeros().scale() > minorUnits) {
                violations.add(new FieldViolation("amount", "must have at most " + minorUnits + " decimal places for " + currency));
            }
        }

        // "Today" is judged in the bank's business time zone (NZ), not the server's (UTC in a container).
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
