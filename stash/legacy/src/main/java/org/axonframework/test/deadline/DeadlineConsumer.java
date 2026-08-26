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

package org.axonframework.test.deadline;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeDescriptor;

/**
 * Functional interface describing a {@link java.util.function.Consumer} of a {@link DeadlineMessage}.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3
 */
@FunctionalInterface
public interface DeadlineConsumer {

    /**
     * Consumes given {@code deadlineMessage}. The {@code deadlineScope} is used to identify the exact handler of the
     * message.
     *
     * @param deadlineScope   A description of the {@link Scope} in which a deadline was
     *                        scheduled
     * @param deadlineMessage the {@link DeadlineMessage} to be handled
     * @throws Exception in case something goes wrong while consuming the {@code deadlineMessage}
     */
    void consume(ScopeDescriptor deadlineScope, DeadlineMessage deadlineMessage) throws Exception;
}
