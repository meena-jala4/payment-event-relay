package nz.paymentrelay.fraud;

/** Temporary vendor problem (timeout, 5xx, Retryable=true). The dispatcher retries it with backoff. */
public class FraudProviderException extends RuntimeException {

    public FraudProviderException(String message) {
        super(message);
    }

    public FraudProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
