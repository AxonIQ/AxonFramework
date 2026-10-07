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

import org.axonframework.extension.spring.messaging.unitofwork.NonTransactionalConnectionProviderException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Explains a failed application start caused by a {@link NonTransactionalConnectionProviderException}.
 *
 * @author Mitchell Herrijgers
 * @since 5.4.0
 */
public class NonTransactionalConnectionProviderFailureAnalyzer
        extends AbstractFailureAnalyzer<NonTransactionalConnectionProviderException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NonTransactionalConnectionProviderException cause) {
        return new FailureAnalysis(
                "Axon's ConnectionProvider hands out connections that are not part of the transaction Axon starts "
                        + "for a unit of work. Statements on them, like token store and dead-letter updates, would "
                        + "commit on their own instead of together with the rest of the unit of work.\n\n"
                        + cause.reason(),
                "Let the PlatformTransactionManager manage the DataSource of the ConnectionProvider, for example by "
                        + "using the same DataSource bean for both.\n\n"
                        + "If statements committing outside the transaction are intended, set "
                        + "'axon.transaction.allow-non-transactional-connection-provider=true'.",
                cause
        );
    }
}
