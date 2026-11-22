package nz.paymentrelay.payment.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Structural validation lives here (annotations). Rules that need config or the clock
 * (supported currency, minor units, date not in past) live in {@link nz.paymentrelay.payment.PaymentValidator}.
 */
public record CreatePaymentRequest(
        @NotBlank @Pattern(regexp = AccountNumbers.NZ_FORMAT, message = AccountNumbers.NZ_FORMAT_MESSAGE)
        String sourceAccount,

        @NotNull @Valid
        Destination destination,

        // BigDecimal is parsed exactly from the JSON text ("100.10" or 100.10) - no binary floating point involved.
        @NotNull @Positive
        BigDecimal amount,

        @NotBlank @Size(min = 3, max = 3)
        String currency,

        @NotBlank @Size(max = 35)
        String reference,

        @NotNull
        LocalDate requestedExecutionDate) {

    public record Destination(
            @NotBlank @Size(max = 140)
            String name,

            @NotBlank @Pattern(regexp = AccountNumbers.NZ_FORMAT, message = AccountNumbers.NZ_FORMAT_MESSAGE)
            String accountNumber) {
    }
}
