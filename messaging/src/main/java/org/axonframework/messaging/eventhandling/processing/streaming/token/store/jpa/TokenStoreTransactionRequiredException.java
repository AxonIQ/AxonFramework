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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa;

import org.axonframework.common.AxonNonTransientException;

/**
 * Exception indicating that a {@link org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore TokenStore} was asked to use an entity manager that is not part of a transaction.
 * <p>
 * Token store operations consist of several statements, and callers combine several operations (for example when
 * splitting or merging segments) expecting them to succeed or fail together. Without a transaction the changes are not
 * committed together, which can leave the token store in an inconsistent state, for example with overlapping
 * segments. This is a configuration problem, typically an entity manager provider that is not managed by the
 * transaction manager in use, and retrying will not resolve it.
 *
 * @author John Hendrikx
 * @since 5.4.2
 */
public class TokenStoreTransactionRequiredException extends AxonNonTransientException {

    /**
     * Initializes the exception with the given {@code message}.
     *
     * @param message the message explaining that the entity manager was not part of a transaction, and how to resolve it
     */
    public TokenStoreTransactionRequiredException(String message) {
        super(message);
    }
}
