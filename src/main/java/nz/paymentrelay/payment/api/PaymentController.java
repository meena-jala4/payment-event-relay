package nz.paymentrelay.payment.api;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import java.math.RoundingMode;
import java.net.URI;
import java.util.Currency;
import java.util.List;
import nz.paymentrelay.payment.Payment;
import nz.paymentrelay.payment.PaymentService;
import nz.paymentrelay.payment.PaymentValidationException;
import nz.paymentrelay.payment.PaymentValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService service;
    private final JsonMapper objectMapper;

    public PaymentController(PaymentService service, JsonMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * Idempotency-Key is REQUIRED: mobile channels retry on flaky networks, and the key is how a retry
     * is recognised as "the same payment" instead of a second payment.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new PaymentValidationException(List.of(
                    new PaymentValidator.FieldViolation("Idempotency-Key", "must be 1-100 characters")));
        }
        PaymentService.SubmitResult result = service.submit(idempotencyKey, request);
        PaymentResponse body = toResponse(result.payment());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/payments/" + body.id())).body(body);
        }
        // Replay of an earlier, identical request: same payment back, nothing created.
        return ResponseEntity.status(HttpStatus.OK).header("Idempotent-Replayed", "true").body(body);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id) {
        return toResponse(service.get(id));
    }

    private PaymentResponse toResponse(Payment p) {
        PaymentResponse.FraudScreening fraud = p.getFraudDecision() == null
                ? new PaymentResponse.FraudScreening("PENDING", null, null, null, null)
                : new PaymentResponse.FraudScreening("COMPLETED", p.getFraudDecision().name(),
                        p.getFraudRiskScore(), reasons(p.getFraudReasons()), p.getFraudCheckedAt());
        return new PaymentResponse(p.getId(), p.getStatus().name(), p.getSourceAccount(),
                new CreatePaymentRequest.Destination(p.getDestinationName(), p.getDestinationAccount()),
                // DB keeps 4dp; show the currency's minor units (NZD 2dp). Exact: input was validated to fit.
                p.getAmount().setScale(Currency.getInstance(p.getCurrency()).getDefaultFractionDigits(), RoundingMode.UNNECESSARY),
                p.getCurrency(), p.getReference(), p.getExecutionDate(), p.getCreatedAt(), fraud);
    }

    private List<PaymentResponse.Reason> reasons(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PaymentResponse.Reason>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
