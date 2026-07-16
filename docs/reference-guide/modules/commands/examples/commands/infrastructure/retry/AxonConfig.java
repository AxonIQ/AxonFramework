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
