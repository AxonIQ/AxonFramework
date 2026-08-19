/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
