package nz.paymentrelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import nz.paymentrelay.broker.BrokerAvailability;
import nz.paymentrelay.broker.EventDelivery;
import nz.paymentrelay.broker.EventDeliveryRepository;
import nz.paymentrelay.events.PaymentSubmittedEvent;
import nz.paymentrelay.fraud.FraudScreeningConsumer;
import nz.paymentrelay.fraud.stub.FraudStubProperties;
import nz.paymentrelay.ledger.LedgerConsumer;
import nz.paymentrelay.ledger.LedgerEntryRepository;
import nz.paymentrelay.outbox.OutboxEvent;
import nz.paymentrelay.outbox.OutboxRepository;
import nz.paymentrelay.payment.FraudDecision;
import nz.paymentrelay.payment.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The things the brief cares about most: nothing lost, nothing applied twice, nothing blocked. */
class ReliabilityTest extends IntegrationTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PaymentRepository payments;
    @Autowired OutboxRepository outbox;
    @Autowired EventDeliveryRepository deliveries;
    @Autowired LedgerEntryRepository ledger;
    @Autowired BrokerAvailability broker;
    @Autowired FraudStubProperties fraudStub;

    @AfterEach
    void resetSimulations() {
        broker.setAvailable(true);
        fraudStub.setFailureRate(0.0);
    }

    @Test
    void acceptedPaymentFlowsToAllThreeConsumers() throws Exception {
        String id = submit("500.00");

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(payments.findById(id).orElseThrow().getFraudDecision()).isEqualTo(FraudDecision.ACCEPT);
            assertThat(ledger.findByPaymentId(id)).hasSize(1);
            assertThat(deliveriesFor(id)).hasSize(3).allMatch(d -> d.getStatus() == EventDelivery.Status.DELIVERED);
        });
    }

    @Test
    void paymentAcceptedDuringBrokerOutageIsPublishedWhenBrokerRecovers() throws Exception {
        broker.setAvailable(false);

        String id = submit("20.00"); // API still says yes, the event is safe in the outbox

        Thread.sleep(300); // a few relay ticks
        assertThat(outbox.findByAggregateId(id)).singleElement()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
                    assertThat(e.getAttempts()).isPositive();
                });
        assertThat(deliveriesFor(id)).isEmpty();

        broker.setAvailable(true);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(outbox.findByAggregateId(id).get(0).getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
            assertThat(ledger.findByPaymentId(id)).hasSize(1);
        });
    }

    @Test
    void duplicateDeliveriesNeverDoublePostTheLedger() throws Exception {
        String id = submit("75.00");
        await().atMost(WAIT).until(() -> ledger.findByPaymentId(id).size() == 1);
        EventDelivery original = deliveriesFor(id).get(0);

        // 1. broker redelivers the same event (same eventId) to every consumer, twice
        for (int i = 0; i < 2; i++) {
            for (String consumer : List.of(LedgerConsumer.NAME, FraudScreeningConsumer.NAME, "notification")) {
                deliveries.save(new EventDelivery(original.getEventId(), original.getEventType(), consumer,
                        original.getPayload(), Instant.now()));
            }
        }
        // 2. same payment published again but with a new eventId (e.g. a replay gone wrong)
        PaymentSubmittedEvent event = mapper.readValue(original.getPayload(), PaymentSubmittedEvent.class);
        String newEventId = UUID.randomUUID().toString();
        PaymentSubmittedEvent sameBusinessEvent = new PaymentSubmittedEvent(newEventId, event.paymentId(),
                event.sourceAccount(), event.destinationName(), event.destinationAccount(), event.amount(),
                event.currency(), event.reference(), event.requestedExecutionDate(), event.occurredAt());
        Long replayId = deliveries.save(new EventDelivery(newEventId, PaymentSubmittedEvent.TYPE, LedgerConsumer.NAME,
                mapper.writeValueAsString(sameBusinessEvent), Instant.now())).getId();

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(deliveriesFor(id)).hasSize(9).allMatch(d -> d.getStatus() == EventDelivery.Status.DELIVERED);
            assertThat(deliveries.findById(replayId).orElseThrow().getStatus()).isEqualTo(EventDelivery.Status.DELIVERED);
        });
        assertThat(ledger.findByPaymentId(id)).hasSize(1);
    }

    @Test
    void persistentlyFailingFraudProviderDeadLettersWithoutBlockingOtherConsumers() throws Exception {
        fraudStub.setFailureRate(1.0); // vendor completely down

        String id = submit("42.00");

        await().atMost(WAIT).untilAsserted(() -> {
            List<EventDelivery> ds = deliveriesFor(id);
            assertThat(ds).filteredOn(d -> d.getConsumer().equals(FraudScreeningConsumer.NAME))
                    .singleElement().satisfies(d -> {
                        assertThat(d.getStatus()).isEqualTo(EventDelivery.Status.DEAD);
                        assertThat(d.getAttempts()).isEqualTo(3);
                        assertThat(d.getLastError()).contains("SVC-503");
                    });
            assertThat(ds).filteredOn(d -> !d.getConsumer().equals(FraudScreeningConsumer.NAME))
                    .hasSize(2).allMatch(d -> d.getStatus() == EventDelivery.Status.DELIVERED);
        });
        assertThat(ledger.findByPaymentId(id)).hasSize(1);
        assertThat(payments.findById(id).orElseThrow().getFraudDecision()).isNull(); // no guessing
    }

    private String submit(String amount) throws Exception {
        String body = mvc.perform(post("/payments").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson(amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<String>read(body, "$.id");
    }

    private List<EventDelivery> deliveriesFor(String paymentId) {
        return outbox.findByAggregateId(paymentId).stream()
                .flatMap(e -> deliveries.findByEventId(e.getId()).stream())
                .toList();
    }
}
