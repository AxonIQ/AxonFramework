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

package org.axonframework.extension.springboot;

import org.axonframework.extension.spring.messaging.unitofwork.NonTransactionalConnectionPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties describing how Axon takes part in Spring transactions.
 *
 * @author Mitchell Herrijgers
 * @since 5.4.0
 */
@ConfigurationProperties("axon.transaction")
public class TransactionProperties {

    /**
     * Whether to start the application when the connections of Axon's {@code ConnectionProvider} are not part of the
     * Spring transaction Axon starts for a unit of work. Statements on such connections, like token store and
     * dead-letter updates, commit on their own. Defaults to {@code false}, failing the application start.
     */
    private boolean allowNonTransactionalConnectionProvider = false;

    /**
     * Indicates whether the application starts when the connections of Axon's {@code ConnectionProvider} are not part
     * of the Spring transaction Axon starts for a unit of work.
     *
     * @return {@code true} when the application starts regardless, {@code false} when it fails to start.
     */
    public boolean isAllowNonTransactionalConnectionProvider() {
        return allowNonTransactionalConnectionProvider;
    }

    /**
     * Sets whether the application starts when the connections of Axon's {@code ConnectionProvider} are not part of
     * the Spring transaction Axon starts for a unit of work.
     *
     * @param allowNonTransactionalConnectionProvider {@code true} to start the application regardless, {@code false} to
     *                                                fail its start.
     */
    public void setAllowNonTransactionalConnectionProvider(boolean allowNonTransactionalConnectionProvider) {
        this.allowNonTransactionalConnectionProvider = allowNonTransactionalConnectionProvider;
    }

    /**
     * Returns the {@link NonTransactionalConnectionPolicy} matching these properties.
     *
     * @return {@link NonTransactionalConnectionPolicy#IGNORE} when a non-transactional {@code ConnectionProvider} is
     * allowed, {@link NonTransactionalConnectionPolicy#FAIL} otherwise.
     */
    public NonTransactionalConnectionPolicy nonTransactionalConnectionPolicy() {
        return allowNonTransactionalConnectionProvider
                ? NonTransactionalConnectionPolicy.IGNORE
                : NonTransactionalConnectionPolicy.FAIL;
    }
}
