package nz.paymentrelay.broker;

public class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException(String message) {
        super(message);
    }
}
