package nz.paymentrelay.fraud.stub;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mutable on purpose, so tests and the /simulation endpoint can change the stub's behaviour while it runs. */
@ConfigurationProperties(prefix = "fraud.stub")
public class FraudStubProperties {

    private boolean enabled = true;
    /** Chance (0.0 to 1.0) that a call returns a vendor error (HTTP 500, or HTTP 200 with an Error body). */
    private double failureRate = 0.3;
    /** Chance (0.0 to 1.0) that a call waits for slowDelay before answering. */
    private double slowRate = 0.1;
    private Duration slowDelay = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getFailureRate() { return failureRate; }
    public void setFailureRate(double failureRate) { this.failureRate = failureRate; }
    public double getSlowRate() { return slowRate; }
    public void setSlowRate(double slowRate) { this.slowRate = slowRate; }
    public Duration getSlowDelay() { return slowDelay; }
    public void setSlowDelay(Duration slowDelay) { this.slowDelay = slowDelay; }
}
