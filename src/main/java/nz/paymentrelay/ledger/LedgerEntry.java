package nz.paymentrelay.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String paymentId;
    private String account;
    private String direction;
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;
    private String currency;
    private Instant postedAt;

    protected LedgerEntry() {
    }

    public static LedgerEntry debit(String paymentId, String account, BigDecimal amount, String currency, Instant at) {
        LedgerEntry e = new LedgerEntry();
        e.paymentId = paymentId;
        e.account = account;
        e.direction = "DEBIT";
        e.amount = amount;
        e.currency = currency;
        e.postedAt = at;
        return e;
    }

    public String getPaymentId() { return paymentId; }
    public String getAccount() { return account; }
    public String getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
