package nz.paymentrelay.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import nz.paymentrelay.config.PaymentProperties;
import nz.paymentrelay.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.Test;

class PaymentValidatorTest {

    // 12:00 UTC on the 20th is already 01:00 on the 21st in Auckland (UTC+13)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
    private final PaymentValidator validator = new PaymentValidator(
            new PaymentProperties(Set.of("NZD", "JPY"), ZoneId.of("Pacific/Auckland")), clock);

    @Test
    void todayIsJudgedInNewZealandTimeNotServerUtc() {
        // still the 20th in UTC, but for someone in NZ the 20th is already yesterday
        assertThatThrownBy(() -> validator.validate(request("10.00", "NZD", LocalDate.of(2026, 11, 20))))
                .isInstanceOf(PaymentValidationException.class);
        assertThatCode(() -> validator.validate(request("10.00", "NZD", LocalDate.of(2026, 11, 21))))
                .doesNotThrowAnyException();
    }

    @Test
    void minorUnitsDependOnCurrency() {
        assertThatThrownBy(() -> validator.validate(request("10.5", "JPY", LocalDate.of(2026, 12, 1))))
                .isInstanceOf(PaymentValidationException.class); // yen has no decimals
        assertThatCode(() -> validator.validate(request("1050", "JPY", LocalDate.of(2026, 12, 1))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request("10.50000", "NZD", LocalDate.of(2026, 12, 1))))
                .doesNotThrowAnyException(); // trailing zeros don't change the value
    }

    private static CreatePaymentRequest request(String amount, String currency, LocalDate date) {
        return new CreatePaymentRequest("12-3456-7890123-00",
                new CreatePaymentRequest.Destination("Aroha", "02-0100-0123456-001"),
                new BigDecimal(amount), currency, "ref", date);
    }
}
