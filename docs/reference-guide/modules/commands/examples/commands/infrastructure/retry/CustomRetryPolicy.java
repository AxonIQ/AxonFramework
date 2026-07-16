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
