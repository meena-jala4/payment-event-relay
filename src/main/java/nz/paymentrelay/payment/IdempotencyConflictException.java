package nz.paymentrelay.payment;

/** Same Idempotency-Key re-used with a different request body: almost certainly a client bug, so we refuse. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
