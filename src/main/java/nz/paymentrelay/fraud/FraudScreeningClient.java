package nz.paymentrelay.fraud;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import nz.paymentrelay.common.NonRetryableException;
import nz.paymentrelay.events.PaymentSubmittedEvent;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the fraud vendor. Whatever goes wrong with the vendor ends up as one of two
 * outcomes: FraudProviderException (try again later) or NonRetryableException (dead-letter, a
 * person needs to look).
 */
@Component
public class FraudScreeningClient {

    static final String PATH = "/screening/v2/check";

    private final RestClient restClient;
    private final FraudResponseParser parser;

    public FraudScreeningClient(FraudProviderProperties props, FraudResponseParser parser) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) props.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) props.readTimeout().toMillis()); // slow vendor means timeout, which means retry
        this.restClient = RestClient.builder() // plain builder, so no need for a Builder bean from Boot
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Api-Key", props.apiKey())
                .build();
        this.parser = parser;
    }

    public ScreeningResult.Decision screen(PaymentSubmittedEvent event) {
        RawResponse raw;
        try {
            raw = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_XML)
                    .body(Map.of(
                            "requestRef", event.paymentId(),
                            "sourceAccount", event.sourceAccount(),
                            "destinationAccount", event.destinationAccount(),
                            "amount", event.amount().toPlainString(),
                            "currency", event.currency()))
                    // exchange() so we decide what each status means, instead of RestClient throwing on 5xx
                    .exchange((request, response) -> new RawResponse(
                            response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw new FraudProviderException("Fraud provider unreachable or timed out: " + e.getMessage(), e);
        }

        if (raw.status() == 429 || raw.status() >= 500) {
            // try to read an error from the body; if that fails it's still a temporary failure
            throw new FraudProviderException("Fraud provider HTTP " + raw.status() + describeError(raw.body()));
        }
        if (raw.status() >= 400) {
            throw new NonRetryableException("Fraud provider rejected request: HTTP " + raw.status() + describeError(raw.body()));
        }

        ScreeningResult result = parser.parse(raw.body());
        return switch (result) {
            case ScreeningResult.Decision d -> d;
            case ScreeningResult.ProviderError err when err.retryable() ->
                    throw new FraudProviderException("Vendor error " + err.code() + ": " + err.message());
            case ScreeningResult.ProviderError err ->
                    throw new NonRetryableException("Vendor error (not retryable) " + err.code() + ": " + err.message());
        };
    }

    private String describeError(String body) {
        try {
            if (parser.parse(body) instanceof ScreeningResult.ProviderError err) {
                return " (" + err.code() + ": " + err.message() + ")";
            }
        } catch (RuntimeException ignored) {
            // body wasn't vendor XML, carry on
        }
        return "";
    }

    record RawResponse(int status, String body) {
    }
}
