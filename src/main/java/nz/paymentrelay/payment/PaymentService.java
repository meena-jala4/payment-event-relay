package nz.paymentrelay.payment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import nz.paymentrelay.events.PaymentSubmittedEvent;
import nz.paymentrelay.outbox.OutboxEvent;
import nz.paymentrelay.outbox.OutboxRepository;
import nz.paymentrelay.payment.api.CreatePaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final PaymentValidator validator;
    private final JsonMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, OutboxRepository outbox, PaymentValidator validator,
                          JsonMapper objectMapper, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.outbox = outbox;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.tx = tx;
        this.clock = clock;
    }

    /** Tells the controller whether to answer 201 (new payment) or 200 (repeat of an earlier request). */
    public record SubmitResult(Payment payment, boolean created) {
    }

    public SubmitResult submit(String idempotencyKey, CreatePaymentRequest request) {
        validator.validate(request);
        String hash = hash(request);

        Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, hash);
        }
        try {
            Payment created = tx.execute(status -> createWithOutbox(idempotencyKey, hash, request));
            return new SubmitResult(created, true);
        } catch (DataIntegrityViolationException race) {
            // two retries of the same request arrived together; the unique constraint let only one through
            log.info("Concurrent submission for idempotency key {}, returning the winner", idempotencyKey);
            Payment winner = payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
            return replay(winner, idempotencyKey, hash);
        }
    }

    public Payment get(String id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    private SubmitResult replay(Payment payment, String key, String hash) {
        if (!payment.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException(key);
        }
        return new SubmitResult(payment, false);
    }

    /**
     * The important reliability decision: the payment row and its outbox event are written in one
     * local transaction. Either both exist or neither does, so an accepted payment can't lose its
     * event, whatever happens to the broker or this process afterwards.
     */
    private Payment createWithOutbox(String key, String hash, CreatePaymentRequest r) {
        Instant now = clock.instant();
        String paymentId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        Payment payment = payments.save(new Payment(paymentId, key, hash, r.sourceAccount(),
                r.destination().name(), r.destination().accountNumber(), r.amount(),
                r.currency().toUpperCase(), r.reference(), r.requestedExecutionDate(), now));

        PaymentSubmittedEvent event = new PaymentSubmittedEvent(eventId, paymentId, r.sourceAccount(),
                r.destination().name(), r.destination().accountNumber(), r.amount(),
                r.currency().toUpperCase(), r.reference(), r.requestedExecutionDate(), now);
        outbox.save(new OutboxEvent(eventId, paymentId, PaymentSubmittedEvent.TYPE, toJson(event), now));
        payments.flush(); // make a unique-key clash show up here, inside the transaction

        log.info("Accepted payment {} (event {})", paymentId, eventId);
        return payment;
    }

    /** Normalised form, so 100.5 and 100.50 count as the same request. */
    private static String hash(CreatePaymentRequest r) {
        String canonical = String.join("|",
                r.sourceAccount(), r.destination().name(), r.destination().accountNumber(),
                r.amount().stripTrailingZeros().toPlainString(), r.currency().toUpperCase(),
                r.reference(), r.requestedExecutionDate().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(PaymentSubmittedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise event", e);
        }
    }
}
