package commands.infrastructure.retry;

// tag::custom-retry-policy[]
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.retry.RetryPolicy;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class CustomRetryPolicy implements RetryPolicy {

    @Override
    public Outcome defineFor(Message message, Throwable failure,
                             List<Class<? extends Throwable>[]> previousFailures) {
        if (failure instanceof IllegalArgumentException || previousFailures.size() >= 3) {
            return Outcome.doNotReschedule();
        }
        return Outcome.rescheduleIn(2, TimeUnit.SECONDS);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("maxRetries", 3);
        descriptor.describeProperty("retryDelay", "2 SECONDS");
    }
}
// end::custom-retry-policy[]
