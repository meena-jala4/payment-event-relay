package nz.paymentrelay.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "payment")
public class Payment {

    public enum Status { ACCEPTED }

    @Id
    private String id;
    private String idempotencyKey;
    private String requestHash;
    private String sourceAccount;
    private String destinationName;
    private String destinationAccount;

    /** Money is a BigDecimal amount plus an ISO 4217 currency. Never double or float. */
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;
    private String currency;
    private String reference;
    private LocalDate executionDate;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    private FraudDecision fraudDecision;
    @Column(precision = 5, scale = 2)
    private BigDecimal fraudRiskScore;
    private String fraudReasons; // JSON array, kept simple for this exercise
    private Instant fraudCheckedAt;

    private Instant createdAt;

    @Version
    private Long version; // null => Spring Data treats the entity as new (persist, not merge). Optimistic locking: two consumers updating the same row can't silently overwrite

    protected Payment() {
        // needed by JPA
    }

    public Payment(String id, String idempotencyKey, String requestHash, String sourceAccount,
                   String destinationName, String destinationAccount, BigDecimal amount, String currency,
                   String reference, LocalDate executionDate, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.sourceAccount = sourceAccount;
        this.destinationName = destinationName;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.executionDate = executionDate;
        this.status = Status.ACCEPTED;
        this.createdAt = createdAt;
    }

    public void recordFraudDecision(FraudDecision decision, BigDecimal riskScore, String reasonsJson, Instant at) {
        this.fraudDecision = decision;
        this.fraudRiskScore = riskScore;
        this.fraudReasons = reasonsJson;
        this.fraudCheckedAt = at;
    }

    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getSourceAccount() { return sourceAccount; }
    public String getDestinationName() { return destinationName; }
    public String getDestinationAccount() { return destinationAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public LocalDate getExecutionDate() { return executionDate; }
    public Status getStatus() { return status; }
    public FraudDecision getFraudDecision() { return fraudDecision; }
    public BigDecimal getFraudRiskScore() { return fraudRiskScore; }
    public String getFraudReasons() { return fraudReasons; }
    public Instant getFraudCheckedAt() { return fraudCheckedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
