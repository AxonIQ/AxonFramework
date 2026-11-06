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

package org.axonframework.extension.spring.messaging.unitofwork;

import org.axonframework.common.AxonNonTransientException;

/**
 * Exception indicating that the {@link org.axonframework.common.jdbc.ConnectionProvider} of a
 * {@link SpringTransactionManager} returned a connection that is not part of the transaction of its
 * {@link org.springframework.transaction.PlatformTransactionManager}.
 * <p>
 * Such a connection commits every statement on its own. Components that run several statements, or that combine
 * several operations expecting them to succeed or fail together (such as the JDBC token store when splitting or merging
 * segments), can then leave their data in an inconsistent state. This is a configuration problem, typically a
 * connection provider that uses a different {@link javax.sql.DataSource} instance than the one managed by the
 * transaction manager, and retrying will not resolve it.
 *
 * @author John Hendrikx
 * @since 5.5.0
 */
public class NonTransactionalConnectionException extends AxonNonTransientException {

    /**
     * Initializes the exception with the given {@code message}.
     *
     * @param message The message explaining that the connection is not part of a transaction, and how to resolve it.
     */
    public NonTransactionalConnectionException(String message) {
        super(message);
    }
}
