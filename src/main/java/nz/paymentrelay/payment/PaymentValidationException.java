package nz.paymentrelay.payment;

import java.util.List;

public class PaymentValidationException extends RuntimeException {

    private final List<PaymentValidator.FieldViolation> violations;

    public PaymentValidationException(List<PaymentValidator.FieldViolation> violations) {
        super("Payment failed validation");
        this.violations = List.copyOf(violations);
    }

    public List<PaymentValidator.FieldViolation> getViolations() {
        return violations;
    }
}
