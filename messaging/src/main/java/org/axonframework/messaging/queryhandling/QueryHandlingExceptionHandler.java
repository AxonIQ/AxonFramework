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

package org.axonframework.messaging.queryhandling;

import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Handles exceptions thrown by a {@link QueryHandler} within a {@link QueryHandlingComponent}.
 * <p>
 * Return {@link MessageStream#empty()} to suppress the exception — the query returns no results. Return
 * {@link MessageStream#failed(Throwable)} (with the same or a different exception) to let the error propagate to the
 * caller. Return one or more {@link QueryResponseMessage} items to substitute results in place of the error.
 * <p>
 * Register via
 * {@link org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule.QueryHandlerPhase#withExceptionHandler(org.axonframework.common.configuration.ComponentBuilder)}.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@FunctionalInterface
public interface QueryHandlingExceptionHandler extends MessageHandlingExceptionHandler<QueryMessage> {

    /**
     * Called when a query handler throws an exception.
     *
     * @param query   the query being handled when the exception occurred
     * @param context the active processing context
     * @param error   the exception thrown by the query handler
     * @return {@link MessageStream#empty()} to suppress the error, {@link MessageStream#failed(Throwable)} to
     *         propagate it, or a stream of {@link QueryResponseMessage} items to substitute results
     */
    @Override
    MessageStream<QueryResponseMessage> handle(QueryMessage query, ProcessingContext context, Throwable error);
}
