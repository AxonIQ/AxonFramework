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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.jdbc.ConnectionProvider;

/**
 * Thrown when the connections of the {@link ConnectionProvider} of a {@link SpringTransactionManager} are not part of
 * the transactions of its {@link org.springframework.transaction.PlatformTransactionManager}, and the
 * {@link NonTransactionalConnectionPolicy} is {@link NonTransactionalConnectionPolicy#FAIL}.
 *
 * @author Mitchell Herrijgers
 * @since 5.4.0
 */
public class NonTransactionalConnectionProviderException extends AxonConfigurationException {

    private final String reason;

    /**
     * Constructs the exception for the given {@code reason}.
     *
     * @param reason Why the connections are not part of the transactions, and how to solve it.
     */
    public NonTransactionalConnectionProviderException(String reason) {
        super(SpringTransactionManager.NON_TRANSACTIONAL_CONNECTION_MESSAGE + " " + reason);
        this.reason = reason;
    }

    /**
     * Returns why the connections are not part of the transactions, and how to solve it.
     *
     * @return Why the connections are not part of the transactions, and how to solve it.
     */
    public String reason() {
        return reason;
    }
}
