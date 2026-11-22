package nz.paymentrelay.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AppConfig {

    /** Injected everywhere "now" is needed so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Programmatic transactions: lets us keep slow HTTP calls OUTSIDE database transactions. */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
