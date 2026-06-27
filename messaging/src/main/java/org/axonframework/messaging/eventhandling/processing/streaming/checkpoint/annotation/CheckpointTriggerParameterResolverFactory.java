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

package org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.annotation;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.Checkpointing;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link ParameterResolverFactory} constructing a {@link ParameterResolver} resolving the {@link CheckpointTrigger}
 * for the segment currently being handled.
 * <p>
 * This is the additive convenience that lets a self-checkpointing event handler declare a {@code CheckpointTrigger}
 * parameter and call {@link CheckpointTrigger#requestCheckpoint(org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken)
 * requestCheckpoint} directly, instead of retaining the trigger handed to it through
 * {@link Checkpointing#onSegmentClaimed(org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment, CheckpointTrigger)}.
 * <p>
 * Expects the {@code CheckpointTrigger} to reside in the {@link ProcessingContext} under
 * {@link CheckpointTrigger#RESOURCE_KEY}. A
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor streaming event
 * processor} places it there per batch whenever it has
 * <b>at least one</b> {@link Checkpointing} component. The trigger is <em>segment-scoped</em>: it is shared by every
 * handler in that processor on the claimed segment, not restricted to the checkpointing component(s). A requested
 * checkpoint therefore applies to all of the processor's components on that segment.
 * <p>
 * <b>Important:</b> a {@code CheckpointTrigger} is <em>not</em> available to every handler. The resolver always
 * {@link ParameterResolver#matches(ProcessingContext) matches} (so the handler is never silently skipped), but if no
 * trigger is present when the parameter is resolved it fails the handler invocation with a descriptive
 * {@link IllegalStateException} rather than passing {@code null}. A {@code CheckpointTrigger} parameter is therefore
 * only usable on a handler running in a streaming event processor that has at least one {@link Checkpointing}
 * component; declaring it on a handler that may run in a subscribing or otherwise non-checkpointing processor, or in a
 * processor with no checkpointing component at all, will raise that exception at runtime.
 * <p>
 * <b>Internal API.</b> This factory is marked {@link Internal}: it is part of the self-checkpointing support, which is
 * currently intended primarily for internal and advanced use and whose shape may change in a minor or patch release.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@Internal
public class CheckpointTriggerParameterResolverFactory implements ParameterResolverFactory {

    private static final CheckpointTriggerParameterResolver RESOLVER = new CheckpointTriggerParameterResolver();

    @Nullable
    @Override
    public ParameterResolver<CheckpointTrigger> createInstance(Executable executable,
                                                               Parameter[] parameters,
                                                               int parameterIndex) {
        return CheckpointTrigger.class.equals(parameters[parameterIndex].getType()) ? RESOLVER : null;
    }

    /**
     * A {@link ParameterResolver} resolving the {@link CheckpointTrigger} from the {@link ProcessingContext} under
     * {@link CheckpointTrigger#RESOURCE_KEY}.
     * <p>
     * It {@link #matches(ProcessingContext) matches} unconditionally so a handler declaring a {@code CheckpointTrigger}
     * parameter is never silently skipped. When no trigger is present (a non-streaming processor, or a pooled streaming
     * processor with no {@link Checkpointing} component), {@link #resolveParameterValue(ProcessingContext)} fails the
     * handler invocation with a descriptive {@link IllegalStateException} instead of supplying {@code null}.
     */
    private static class CheckpointTriggerParameterResolver implements ParameterResolver<CheckpointTrigger> {

        @Override
        public CompletableFuture<CheckpointTrigger> resolveParameterValue(ProcessingContext context) {
            return CheckpointTrigger.fromContext(context)
                                    .map(CompletableFuture::completedFuture)
                                    .orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException(
                                            "Cannot resolve a CheckpointTrigger parameter: no CheckpointTrigger is "
                                                    + "present in the current ProcessingContext. A CheckpointTrigger is "
                                                    + "only available to handlers running in a pooled streaming event "
                                                    + "processor that has at least one Checkpointing component. Ensure "
                                                    + "this processor has a Checkpointing component, or remove the "
                                                    + "CheckpointTrigger parameter from handlers that may run in a "
                                                    + "non-checkpointing or non-streaming processor.")));
        }

        @Override
        public boolean matches(ProcessingContext context) {
            // Match unconditionally: a false match here would make the whole handler unable to handle the event (it
            // would be silently skipped). Instead the handler always participates and resolveParameterValue fails
            // loudly when no trigger is available.
            return true;
        }
    }
}
