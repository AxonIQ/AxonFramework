package commands.infrastructure.retry;

import org.axonframework.messaging.core.retry.ExponentialBackOffRetryPolicy;
import org.axonframework.messaging.core.retry.FilteringRetryPolicy;
import org.axonframework.messaging.core.retry.MaxAttemptsPolicy;
import org.axonframework.messaging.core.retry.RetryPolicy;

public class RetryPolicyExamples {

    private RetryPolicy delegate;

    void exponentialBackoff() {
        // tag::exponential-backoff[]
        // Retry with delays of 100ms, 200ms, 400ms, 800ms, ...
        RetryPolicy policy = new ExponentialBackOffRetryPolicy(100);
        // end::exponential-backoff[]
    }

    void maxAttempts() {
        // tag::max-attempts[]
        // Retry at most 3 times using the given delegate policy
        RetryPolicy policy = new MaxAttemptsPolicy(delegate, 3);
        // end::max-attempts[]
    }

    void filtering() {
        // tag::filtering[]
        // Only retry when the failure is not a validation error
        RetryPolicy policy = new FilteringRetryPolicy(
                delegate,
                e -> !(e instanceof IllegalArgumentException)
        );
        // end::filtering[]
    }

    void composed() {
        // tag::composed-policy[]
        RetryPolicy policy = new MaxAttemptsPolicy(
                new FilteringRetryPolicy(
                        new ExponentialBackOffRetryPolicy(100),
                        e -> !(e instanceof IllegalArgumentException)
                ),
                3
        );
        // end::composed-policy[]
    }
}
