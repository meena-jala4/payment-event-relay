package nz.paymentrelay.broker;

import java.util.concurrent.atomic.AtomicBoolean;
import nz.paymentrelay.config.RelayProperties;
import org.springframework.stereotype.Component;

/** On/off switch used to simulate a broker outage (config or the /simulation endpoint). */
@Component
public class BrokerAvailability {

    private final AtomicBoolean available;

    public BrokerAvailability(RelayProperties properties) {
        this.available = new AtomicBoolean(properties.broker() == null || properties.broker().available());
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }
}
