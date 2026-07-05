package commands.infrastructure.retry;

// tag::retry-scheduler-config-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.retry.AsyncRetryScheduler;
import org.axonframework.messaging.core.retry.ExponentialBackOffRetryPolicy;
import org.axonframework.messaging.core.retry.FilteringRetryPolicy;
import org.axonframework.messaging.core.retry.MaxAttemptsPolicy;
import org.axonframework.messaging.core.retry.RetryScheduler;

import java.util.concurrent.Executors;

public class AxonConfig {

    public void configureRetryScheduler(MessagingConfigurer configurer) {
        configurer.componentRegistry(cr -> cr.registerComponent(
                RetryScheduler.class,
                config -> new AsyncRetryScheduler(
                        new MaxAttemptsPolicy(
                                new FilteringRetryPolicy(
                                        new ExponentialBackOffRetryPolicy(100),
                                        e -> !(e instanceof IllegalArgumentException)
                                ),
                                3
                        ),
                        Executors.newSingleThreadScheduledExecutor()
                )
        ));
    }
}
// end::retry-scheduler-config-api[]
