package nz.paymentrelay.fraud.stub;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pretends to be the third-party vendor. It runs inside the same app so there's one start command,
 * but the app only talks to it over HTTP through the real client. Switched off in prod by config.
 */
@RestController
@ConditionalOnProperty(prefix = "fraud.stub", name = "enabled", havingValue = "true")
public class FraudStubController {

    private static final String NS = "urn:vendor:fraudscreen:v2";
    private final FraudStubProperties props;

    public FraudStubController(FraudStubProperties props) {
        this.props = props;
    }

    @PostMapping(path = "/screening/v2/check", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> check(@RequestBody Map<String, Object> request) throws InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String ref = String.valueOf(request.getOrDefault("requestRef", "UNKNOWN"));

        if (random.nextDouble() < props.getSlowRate()) {
            Thread.sleep(props.getSlowDelay().toMillis());
        }
        if (random.nextDouble() < props.getFailureRate()) {
            // half the time the error comes back with HTTP 200, which is the trap mentioned in the brief
            HttpStatus status = random.nextBoolean() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_XML).body(errorXml());
        }

        BigDecimal amount = new BigDecimal(String.valueOf(request.getOrDefault("amount", "0")));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(decisionXml(ref, amount));
    }

    /** Decided by amount so demos are predictable: over 10,000 DECLINE, over 1,000 REVIEW, otherwise ACCEPT. */
    private String decisionXml(String ref, BigDecimal amount) {
        String decision;
        String score;
        String reasons;
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            decision = "DECLINE";
            score = "91.00";
            reasons = "<fs:Reason code=\"R310\">Amount exceeds risk threshold</fs:Reason>";
        } else if (amount.compareTo(new BigDecimal("1000")) > 0) {
            decision = "REVIEW";
            score = "72.50";
            reasons = "<fs:Reason code=\"R104\">New payee</fs:Reason>"
                    + "<fs:Reason code=\"R221\">Amount above customer average</fs:Reason>";
        } else {
            decision = "ACCEPT";
            score = "12.00";
            reasons = "";
        }
        // <fs:ModelVersion> isn't in the documented contract; it's here to show the parser ignores new elements
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <fs:ScreeningResponse xmlns:fs="%s">
                  <fs:Header>
                    <fs:RequestRef>%s</fs:RequestRef>
                    <fs:Timestamp>%s</fs:Timestamp>
                    <fs:ModelVersion>2026.11-b</fs:ModelVersion>
                  </fs:Header>
                  <fs:Result>
                    <fs:Decision>%s</fs:Decision>
                    <fs:RiskScore>%s</fs:RiskScore>
                    <fs:Reasons>%s</fs:Reasons>
                  </fs:Result>
                </fs:ScreeningResponse>
                """.formatted(NS, ref, now(), decision, score, reasons);
    }

    private String errorXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <fs:ScreeningResponse xmlns:fs="%s">
                  <fs:Error>
                    <fs:Code>SVC-503</fs:Code>
                    <fs:Message>Scoring engine temporarily unavailable</fs:Message>
                    <fs:Retryable>true</fs:Retryable>
                  </fs:Error>
                </fs:ScreeningResponse>
                """.formatted(NS);
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Pacific/Auckland")).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
