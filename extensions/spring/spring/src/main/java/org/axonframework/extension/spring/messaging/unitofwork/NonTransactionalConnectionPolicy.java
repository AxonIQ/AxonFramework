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

import org.axonframework.common.jdbc.ConnectionProvider;

/**
 * What a {@link SpringTransactionManager} does when the connections of its {@link ConnectionProvider} are not part of
 * the transactions of its {@link org.springframework.transaction.PlatformTransactionManager}.
 *
 * @author Mitchell Herrijgers
 * @since 5.4.0
 */
public enum NonTransactionalConnectionPolicy {

    /**
     * Refuse the configuration by throwing a {@link NonTransactionalConnectionProviderException}.
     */
    FAIL,
    /**
     * Log a warning and accept the configuration.
     */
    WARN,
    /**
     * Accept the configuration without reporting it.
     */
    IGNORE
}
