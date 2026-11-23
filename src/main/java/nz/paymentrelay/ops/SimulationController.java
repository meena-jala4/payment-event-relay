package nz.paymentrelay.ops;

import java.util.Map;
import nz.paymentrelay.broker.BrokerAvailability;
import nz.paymentrelay.fraud.stub.FraudStubProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Switches for the walkthrough demo. Turned off in prod (simulation.enabled=false). */
@RestController
@RequestMapping("/simulation")
@ConditionalOnProperty(prefix = "simulation", name = "enabled", havingValue = "true")
public class SimulationController {

    private final BrokerAvailability broker;
    private final FraudStubProperties fraudStub;

    public SimulationController(BrokerAvailability broker, FraudStubProperties fraudStub) {
        this.broker = broker;
        this.fraudStub = fraudStub;
    }

    @PostMapping("/broker")
    public Map<String, Object> broker(@RequestParam boolean available) {
        broker.setAvailable(available);
        return Map.of("brokerAvailable", available);
    }

    @PostMapping("/fraud-provider")
    public Map<String, Object> fraud(@RequestParam double failureRate, @RequestParam(defaultValue = "0") double slowRate) {
        fraudStub.setFailureRate(failureRate);
        fraudStub.setSlowRate(slowRate);
        return Map.of("failureRate", failureRate, "slowRate", slowRate);
    }
}
