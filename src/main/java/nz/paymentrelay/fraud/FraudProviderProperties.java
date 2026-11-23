package nz.paymentrelay.fraud;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Switching between the stub and the real vendor is just config (base-url, plus the api-key from a Secret). */
@ConfigurationProperties(prefix = "fraud.provider")
public record FraudProviderProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
}
