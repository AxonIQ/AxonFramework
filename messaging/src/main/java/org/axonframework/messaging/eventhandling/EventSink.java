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

package org.axonframework.messaging.eventhandling;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Interface describing the option to publish {@link EventMessage events}.
 * <p>
 * When a {@link ProcessingContext} is provided, the publication is typically staged in the
 * {@link ProcessingContext#onPostInvocation(Function) post invocation} phase of the {@code ProcessingContext}. As a
 * consequence, the result of publication will be made apparent in the {@code ProcessingContext}. When providing no
 * {@code ProcessingContext}, the result of publication is carried in the resulting {@link CompletableFuture}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface EventSink extends DescribableComponent {

    /**
     * Publishes the given {@code events} within the given {@code context}, when present.
     * <p>
     * When present, the {@link ProcessingContext#onPostInvocation(Function) post invocation} phase is used to publish
     * the {@code events}. As a consequence, the resulting {@link CompletableFuture} completes when the {@code events}
     * are staged in that phase.
     * <p>
     * When no {@link ProcessingContext} is provided, implementers of this interface may choose to create a
     * {@code ProcessingContext} when necessary.
     *
     * @param context The processing context, if any, to publish the given {@code events} in.
     * @param events  The {@link EventMessage events} to publish in this sink.
     * @return A {@link CompletableFuture} of {@link Void}. When this completes and a non-null {@code context} was
     * given, this means the {@code events} have been successfully staged. When a null {@code context} was provided,
     * successful completion of this future means the {@code events} were published.
     */
    default CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                            EventMessage @Nullable ... events) {
        return publish(context, Arrays.asList(events));
    }

    /**
     * Publishes the given {@code events} within the given {@code context}, when present.
     * <p>
     * When present, the {@link ProcessingContext#onPostInvocation(Function) post invocation} phase is used to publish
     * the {@code events}. As a consequence, the resulting {@link CompletableFuture} completes when the {@code events}
     * are staged in that phase.
     * <p>
     * When no {@link ProcessingContext} is provided, implementers of this interface may choose to create a
     * {@code ProcessingContext} when necessary.
     *
     * @param context The processing context, if any, to publish the given {@code events} in.
     * @param events  The {@link EventMessage events} to publish in this sink.
     * @return A {@link CompletableFuture} of {@link Void}. When this completes and a non-null {@code context} was
     * given, this means the {@code events} have been successfully staged. When a null {@code context} was provided,
     * successful completion of this future means the {@code events} were published.
     */
    CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                    List<? extends EventMessage> events);
}
