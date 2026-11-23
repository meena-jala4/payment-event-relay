package nz.paymentrelay.common;

/** Thrown by a consumer when retrying won't help (e.g. vendor says Retryable=false). Goes straight to dead-letter. */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
