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
