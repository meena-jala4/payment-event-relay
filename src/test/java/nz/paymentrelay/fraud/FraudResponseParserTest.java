package nz.paymentrelay.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import nz.paymentrelay.payment.FraudDecision;
import org.junit.jupiter.api.Test;

/** The vendor XML is the riskiest integration, so these tests are deliberately thorough. */
class FraudResponseParserTest {

    private final FraudResponseParser parser = new FraudResponseParser();

    @Test
    void parsesDocumentedSuccessResponse() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <fs:ScreeningResponse xmlns:fs="urn:vendor:fraudscreen:v2">
                  <fs:Header>
                    <fs:RequestRef>PAY-7f3a9c</fs:RequestRef>
                    <fs:Timestamp>2026-11-12T09:15:22+13:00</fs:Timestamp>
                  </fs:Header>
                  <fs:Result>
                    <fs:Decision>REVIEW</fs:Decision>
                    <fs:RiskScore>72.50</fs:RiskScore>
                    <fs:Reasons>
                      <fs:Reason code="R104">New payee</fs:Reason>
                      <fs:Reason code="R221">Amount above customer average</fs:Reason>
                    </fs:Reasons>
                  </fs:Result>
                </fs:ScreeningResponse>
                """;

        ScreeningResult.Decision d = (ScreeningResult.Decision) parser.parse(xml);

        assertThat(d.requestRef()).isEqualTo("PAY-7f3a9c");
        assertThat(d.decision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(d.riskScore()).isEqualByComparingTo(new BigDecimal("72.50"));
        assertThat(d.reasons()).extracting(ScreeningResult.Reason::code).containsExactly("R104", "R221");
    }

    @Test
    void errorBodyIsAnErrorEvenWhenVendorSaysHttp200() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <fs:ScreeningResponse xmlns:fs="urn:vendor:fraudscreen:v2">
                  <fs:Error>
                    <fs:Code>SVC-503</fs:Code>
                    <fs:Message>Scoring engine temporarily unavailable</fs:Message>
                    <fs:Retryable>true</fs:Retryable>
                  </fs:Error>
                </fs:ScreeningResponse>
                """;

        assertThat(parser.parse(xml)).isEqualTo(
                new ScreeningResult.ProviderError("SVC-503", "Scoring engine temporarily unavailable", true));
    }

    @Test
    void nonRetryableErrorIsReportedAsSuch() {
        String xml = """
                <fs:ScreeningResponse xmlns:fs="urn:vendor:fraudscreen:v2">
                  <fs:Error><fs:Code>VAL-400</fs:Code><fs:Message>Bad account</fs:Message><fs:Retryable>false</fs:Retryable></fs:Error>
                </fs:ScreeningResponse>
                """;

        assertThat(((ScreeningResult.ProviderError) parser.parse(xml)).retryable()).isFalse();
    }

    @Test
    void ignoresElementsTheVendorAddsLaterAndToleratesADifferentPrefix() {
        String xml = """
                <v:ScreeningResponse xmlns:v="urn:vendor:fraudscreen:v2">
                  <v:Header><v:RequestRef>PAY-1</v:RequestRef><v:Region>APAC</v:Region></v:Header>
                  <v:Result>
                    <v:Decision>ACCEPT</v:Decision>
                    <v:RiskScore>5.00</v:RiskScore>
                    <v:Explainability><v:Feature weight="0.2">velocity</v:Feature></v:Explainability>
                  </v:Result>
                  <v:Billing><v:Units>1</v:Units></v:Billing>
                </v:ScreeningResponse>
                """;

        ScreeningResult.Decision d = (ScreeningResult.Decision) parser.parse(xml);
        assertThat(d.decision()).isEqualTo(FraudDecision.ACCEPT);
        assertThat(d.reasons()).isEmpty();
    }

    @Test
    void unknownDecisionFailsSafeToReview() {
        String xml = """
                <fs:ScreeningResponse xmlns:fs="urn:vendor:fraudscreen:v2">
                  <fs:Result><fs:Decision>HOLD</fs:Decision><fs:RiskScore>50</fs:RiskScore></fs:Result>
                </fs:ScreeningResponse>
                """;

        assertThat(((ScreeningResult.Decision) parser.parse(xml)).decision()).isEqualTo(FraudDecision.REVIEW);
    }

    @Test
    void rejectsXxeAttack() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <fs:ScreeningResponse xmlns:fs="urn:vendor:fraudscreen:v2">
                  <fs:Result><fs:Decision>&xxe;</fs:Decision></fs:Result>
                </fs:ScreeningResponse>
                """;

        assertThatThrownBy(() -> parser.parse(xml)).isInstanceOf(FraudProviderException.class);
    }

    @Test
    void garbageIsATransientProviderFailure() {
        assertThatThrownBy(() -> parser.parse("<html>502 Bad Gateway</html>"))
                .isInstanceOf(FraudProviderException.class);
    }
}
