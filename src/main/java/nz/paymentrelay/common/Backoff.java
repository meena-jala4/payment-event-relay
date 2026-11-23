package nz.paymentrelay.common;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with +/-20% jitter, so lots of retries don't all hit a recovering service at once. */
public final class Backoff {

    private Backoff() {
    }

    public static Duration next(int attempts, Duration initial, Duration max) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 20); // cap it so it can't overflow
        long base = initial.toMillis() * (1L << exponent);
        long capped = Math.min(base, max.toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (capped * jitter));
    }
}
