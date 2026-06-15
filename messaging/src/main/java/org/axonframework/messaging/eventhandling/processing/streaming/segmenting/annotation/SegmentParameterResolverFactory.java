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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting.annotation;

import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of a {@link ParameterResolverFactory} that resolves the {@link Segment} from the
 * {@link ProcessingContext} whenever it's available.
 * <p>
 * A streaming event processor places the {@code Segment} currently being handled in the context under
 * {@link Segment#RESOURCE_KEY}, so a handler method may declare a {@link Segment} parameter to learn which segment it is
 * processing -- for example to address the matching {@link org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger}
 * of a self-checkpointing component.
 * <p>
 * The resolver always {@link ParameterResolver#matches(ProcessingContext) matches} -- so a handler declaring a
 * {@link Segment} parameter is <b>never silently skipped</b> -- and resolves to {@code null} when no {@code Segment} is
 * present (a processor that is not aware of segments, such as a subscribing processor). A {@code null} {@code Segment}
 * is harmless to receive, so, unlike the
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger} parameter, this
 * resolver does not fail when one is absent.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
public class SegmentParameterResolverFactory implements ParameterResolverFactory {

    private static final SegmentParameterResolver RESOLVER = new SegmentParameterResolver();

    @Nullable
    @Override
    public ParameterResolver<Segment> createInstance(Executable executable,
                                                     Parameter[] parameters,
                                                     int parameterIndex) {
        if (Segment.class.equals(parameters[parameterIndex].getType())) {
            return RESOLVER;
        }
        return null;
    }

    private static class SegmentParameterResolver implements ParameterResolver<Segment> {

        @Override
        public CompletableFuture<Segment> resolveParameterValue(ProcessingContext context) {
            return CompletableFuture.completedFuture(Segment.fromContext(context).orElse(null));
        }

        @Override
        public boolean matches(ProcessingContext context) {
            // Match unconditionally: a false match here would make the whole handler unable to handle the event (it
            // would be silently skipped). Instead the handler always participates and resolveParameterValue fails
            // loudly when no Segment is available.
            return true;
        }
    }
}
