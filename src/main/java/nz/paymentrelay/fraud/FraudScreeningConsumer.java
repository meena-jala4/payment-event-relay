package nz.paymentrelay.fraud;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import nz.paymentrelay.consumer.EventConsumer;
import nz.paymentrelay.consumer.InboxGuard;
import nz.paymentrelay.events.PaymentSubmittedEvent;
import nz.paymentrelay.payment.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls the vendor outside any DB transaction (a 5 second stall shouldn't hold a DB connection),
 * then saves the decision on the payment inside the inbox transaction.
 */
@Component
public class FraudScreeningConsumer implements EventConsumer {

    public static final String NAME = "fraud-screening";
    private static final Logger log = LoggerFactory.getLogger(FraudScreeningConsumer.class);

    private final InboxGuard inbox;
    private final FraudScreeningClient client;
    private final PaymentRepository payments;
    private final JsonMapper objectMapper;
    private final Clock clock;

    public FraudScreeningConsumer(InboxGuard inbox, FraudScreeningClient client, PaymentRepository payments,
                                  JsonMapper objectMapper, Clock clock) {
        this.inbox = inbox;
        this.client = client;
        this.payments = payments;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handle(PaymentSubmittedEvent event) {
        if (inbox.alreadyProcessed(NAME, event.eventId())) {
            log.debug("Duplicate event {} ignored by {}", event.eventId(), NAME);
            return; // don't call (and pay) the vendor twice for the same check
        }
        ScreeningResult.Decision decision = client.screen(event);
        String reasonsJson = toJson(decision);

        inbox.runOnce(NAME, event.eventId(), () -> payments.findById(event.paymentId())
                .orElseThrow(() -> new IllegalStateException("Payment " + event.paymentId() + " not found"))
                .recordFraudDecision(decision.decision(), decision.riskScore(), reasonsJson, clock.instant()));
        log.info("FRAUD decision {} (score {}) recorded for payment {}",
                decision.decision(), decision.riskScore(), event.paymentId());
    }

    private String toJson(ScreeningResult.Decision decision) {
        try {
            return objectMapper.writeValueAsString(decision.reasons());
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
