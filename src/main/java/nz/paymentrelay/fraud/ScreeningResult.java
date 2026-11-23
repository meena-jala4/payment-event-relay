package nz.paymentrelay.fraud;

import java.math.BigDecimal;
import java.util.List;
import nz.paymentrelay.payment.FraudDecision;

/** What the vendor told us, decoded from the XML. It's a sealed type so callers have to handle both cases. */
public sealed interface ScreeningResult {

    record Decision(String requestRef, FraudDecision decision, BigDecimal riskScore, List<Reason> reasons)
            implements ScreeningResult {
    }

    record ProviderError(String code, String message, boolean retryable) implements ScreeningResult {
    }

    record Reason(String code, String description) {
    }
}
