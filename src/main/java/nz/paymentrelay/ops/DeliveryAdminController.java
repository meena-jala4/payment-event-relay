package nz.paymentrelay.ops;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import nz.paymentrelay.broker.EventDelivery;
import nz.paymentrelay.broker.EventDeliveryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an operator look at the dead-letter queue and replay items. Would need auth or an
 * internal-only ingress in production, left out here.
 */
@RestController
@RequestMapping("/admin/deliveries")
public class DeliveryAdminController {

    private final EventDeliveryRepository deliveries;
    private final Clock clock;

    public DeliveryAdminController(EventDeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @GetMapping
    public List<DeliveryView> list(@RequestParam(defaultValue = "DEAD") EventDelivery.Status status) {
        return deliveries.findByStatus(status).stream().map(DeliveryView::of).toList();
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<DeliveryView> replay(@PathVariable Long id) {
        return deliveries.findById(id)
                .map(d -> {
                    d.replay(clock.instant());
                    return ResponseEntity.ok(DeliveryView.of(deliveries.save(d)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    public record DeliveryView(Long id, String eventId, String consumer, String status, int attempts,
                               String lastError, Instant updatedAt) {
        static DeliveryView of(EventDelivery d) {
            return new DeliveryView(d.getId(), d.getEventId(), d.getConsumer(), d.getStatus().name(),
                    d.getAttempts(), d.getLastError(), d.getUpdatedAt());
        }
    }
}
