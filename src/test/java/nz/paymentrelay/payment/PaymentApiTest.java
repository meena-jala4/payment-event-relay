package nz.paymentrelay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import nz.paymentrelay.IntegrationTestBase;
import nz.paymentrelay.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class PaymentApiTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PaymentRepository payments;
    @Autowired OutboxRepository outbox;

    @Test
    void acceptsValidPaymentAndWritesOutboxEventInSameTransaction() throws Exception {
        MvcResult result = mvc.perform(post("/payments").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("250.50")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.amount").value("250.50"))   // string, exact, 2 decimals
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();

        String id = JsonPath.<String>read(result.getResponse().getContentAsString(), "$.id");
        assertThat(payments.findById(id)).isPresent();
        assertThat(outbox.findByAggregateId(id)).hasSize(1);
    }

    @Test
    void retryWithSameKeyAndBodyReturnsOriginalPaymentAndCreatesNothing() throws Exception {
        String key = newKey();
        String first = submit(key, paymentJson("10.00")).getResponse().getContentAsString();

        // mobile app retries: same key should give the same payment (10.0 vs 10.00 must not matter)
        MvcResult replay = mvc.perform(post("/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("10.0")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        String id = JsonPath.<String>read(first, "$.id");
        assertThat(JsonPath.<String>read(replay.getResponse().getContentAsString(), "$.id")).isEqualTo(id);
        assertThat(outbox.findByAggregateId(id)).hasSize(1); // still only one event
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        String key = newKey();
        submit(key, paymentJson("10.00"));

        mvc.perform(post("/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(paymentJson("99.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:payment-relay:idempotency-key-reused"));
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(paymentJson("10.00")))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "amount,                   0",
            "amount,                   -5.00",
            "amount,                   10.001",      // more decimals than NZD allows
            "currency,                 XYZ",
            "sourceAccount,            12345",
            "destination.accountNumber, 02-01-1-1",
            "requestedExecutionDate,   PAST",
    })
    void rejectsInvalidInputWithFieldLevelErrors(String field, String badValue) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(paymentJson("10.00"), Map.class);
        String value = "PAST".equals(badValue)
                ? LocalDate.now(ZoneId.of("Pacific/Auckland")).minusDays(1).toString()
                : badValue;
        if (field.startsWith("destination.")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> destination = (Map<String, Object>) body.get("destination");
            destination.put("accountNumber", value);
        } else {
            body.put(field, value);
        }

        mvc.perform(post("/payments").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:payment-relay:validation-error"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')]").exists());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/payments").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPaymentIs404() throws Exception {
        mvc.perform(get("/payments/does-not-exist")).andExpect(status().isNotFound());
    }

    private MvcResult submit(String key, String json) throws Exception {
        return mvc.perform(post("/payments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
    }
}
