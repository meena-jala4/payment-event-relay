package nz.paymentrelay.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByPaymentId(String paymentId);

    boolean existsByPaymentId(String paymentId);
}
