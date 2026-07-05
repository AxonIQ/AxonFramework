package commands.infrastructure.retry.springboot;

// tag::retry-scheduler-spring[]
import org.axonframework.messaging.core.retry.AsyncRetryScheduler;
import org.axonframework.messaging.core.retry.ExponentialBackOffRetryPolicy;
import org.axonframework.messaging.core.retry.FilteringRetryPolicy;
import org.axonframework.messaging.core.retry.MaxAttemptsPolicy;
import org.axonframework.messaging.core.retry.RetryScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class AxonConfig {

    @Bean
    public RetryScheduler retryScheduler() {
        return new AsyncRetryScheduler(
                new MaxAttemptsPolicy(
                        new FilteringRetryPolicy(
                                new ExponentialBackOffRetryPolicy(100),
                                e -> !(e instanceof IllegalArgumentException)
                        ),
                        3
                ),
                Executors.newSingleThreadScheduledExecutor()
        );
    }
}
// end::retry-scheduler-spring[]
