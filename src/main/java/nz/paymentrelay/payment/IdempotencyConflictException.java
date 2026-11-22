package nz.paymentrelay.payment;

/** Same Idempotency-Key sent with a different request body. Most likely a client bug, so we refuse it. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
