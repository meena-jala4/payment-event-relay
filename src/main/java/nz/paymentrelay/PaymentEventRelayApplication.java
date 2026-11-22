package nz.paymentrelay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // gives us the auto-configured TaskScheduler used by the relay + dispatcher loops
public class PaymentEventRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentEventRelayApplication.class, args);
    }
}
