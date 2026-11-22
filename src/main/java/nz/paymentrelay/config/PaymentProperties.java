package nz.paymentrelay.config;

import java.time.ZoneId;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param supportedCurrencies ISO-4217 codes we accept
 * @param businessZone        zone used to decide what "today" means for the execution-date check
 */
@ConfigurationProperties(prefix = "payments")
public record PaymentProperties(Set<String> supportedCurrencies, ZoneId businessZone) {
}
