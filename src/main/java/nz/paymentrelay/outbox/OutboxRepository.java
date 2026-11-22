package nz.paymentrelay.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    @Query("""
            select e from OutboxEvent e
            where e.status = :status and e.nextAttemptAt <= :now
            order by e.createdAt""")
    List<OutboxEvent> findDue(@Param("status") OutboxEvent.Status status, @Param("now") Instant now, Pageable page);

    long countByStatus(OutboxEvent.Status status);

    List<OutboxEvent> findByAggregateId(String aggregateId);
}
