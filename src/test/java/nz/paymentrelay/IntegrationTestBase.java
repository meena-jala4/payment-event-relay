package nz.paymentrelay;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Starts the whole app (real server, H2, the schedulers). All the integration tests extend this,
 * so Spring builds one context and only one server uses the test port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static String paymentJson(String amount) {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Pacific/Auckland")).plusDays(1);
        return """
                {
                  "sourceAccount": "12-3456-7890123-00",
                  "destination": { "name": "Aroha Smith", "accountNumber": "02-0100-0123456-001" },
                  "amount": "%s",
                  "currency": "NZD",
                  "reference": "Rent Nov",
                  "requestedExecutionDate": "%s"
                }
                """.formatted(amount, tomorrow);
    }
}
