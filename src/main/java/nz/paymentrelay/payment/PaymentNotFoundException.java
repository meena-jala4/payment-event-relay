package nz.paymentrelay.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String id) {
        super("Payment " + id + " not found");
    }
}
