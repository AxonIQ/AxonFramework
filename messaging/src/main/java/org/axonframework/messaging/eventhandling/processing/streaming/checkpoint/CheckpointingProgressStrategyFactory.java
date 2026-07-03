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

package org.axonframework.messaging.eventhandling.processing.streaming.checkpoint;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link SegmentProgressStrategyFactory} that produces a {@link CheckpointingProgressStrategy} for every segment of a
 * processor whose components include one or more self-checkpointing {@link Checkpointing} units. It carries the
 * processor-scoped participants and the auto/deferred mode, and creates one strategy per work package.
 * <p>
 * Use {@link #detecting()} to obtain the component-driven selector: given a processor's
 * {@link EventHandlingComponent}s it inspects them for {@code Checkpointing} units (via
 * {@link EventHandlingComponent#unwrap(Class)}) and returns either this checkpointing factory (when any participate) or
 * the default {@link SegmentProgressStrategyFactory#tokenStoring() token-storing} factory (when none do).
 * <p>
 * <b>Internal API.</b> This class is marked {@link Internal}: it is part of the self-checkpointing support, intended
 * primarily for internal and advanced use, and its shape may change in a minor or patch release.
 *
 * @author Allard Buijze
 * @see CheckpointingProgressStrategy
 * @since 5.2.0
 */
@Internal
public final class CheckpointingProgressStrategyFactory implements SegmentProgressStrategyFactory {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final List<Checkpointing> participants;
    private final boolean autoCheckpointing;

    /**
     * Constructs a {@code CheckpointingProgressStrategyFactory} for the given {@code participants}.
     *
     * @param participants      the self-checkpointing units of the processor; must not be empty
     * @param autoCheckpointing {@code true} when an ordinary handler is co-located (auto mode), {@code false} when every
     *                          handler is self-checkpointing (fully-deferred)
     */
    public CheckpointingProgressStrategyFactory(List<Checkpointing> participants, boolean autoCheckpointing) {
        this.participants = List.copyOf(Objects.requireNonNull(participants, "The participants may not be null."));
        this.autoCheckpointing = autoCheckpointing;
    }

    /**
     * Returns the component-driven selector used to pick a {@link SegmentProgressStrategyFactory} for a processor: a
     * {@link CheckpointingProgressStrategyFactory} when its components include self-checkpointing {@link Checkpointing}
     * units, otherwise the default {@link SegmentProgressStrategyFactory#tokenStoring() token-storing} factory.
     * <p>
     * A component is self-checkpointing iff it (or the POJO it wraps) {@link EventHandlingComponent#unwrap(Class)
     * resolves} to a {@code Checkpointing} unit. The processor runs in auto mode unless <em>every</em> component is
     * self-checkpointing (fully-deferred); a mixed processor is logged, since the self-checkpointing components cannot
     * then defer the stored token.
     *
     * @return a function mapping a processor's components to the appropriate progress-strategy factory
     */
    public static Function<List<EventHandlingComponent>, SegmentProgressStrategyFactory> detecting() {
        return components -> {
            List<Checkpointing> participants = components.stream()
                                                         .map(c -> c.unwrap(Checkpointing.class))
                                                         .flatMap(Optional::stream)
                                                         .toList();
            if (participants.isEmpty()) {
                return SegmentProgressStrategyFactory.tokenStoring();
            }
            boolean autoCheckpointing = participants.size() < components.size();
            if (autoCheckpointing) {
                // Mixed mode: self-checkpointing components share a processor with ordinary ones. Auto checkpointing
                // wins, so a checkpoint is requested at the batch-end token every batch and the self-checkpointing
                // components are forced to cover it -- they cannot defer the stored token. Log this so the downgrade is
                // not silent.
                logger.info("Detected {} self-checkpointing component(s) alongside ordinary event-handling "
                                    + "component(s); running in auto-checkpointing mode. The self-checkpointing "
                                    + "components cannot defer the stored token and are driven to cover the batch-end "
                                    + "token every batch. Isolate self-checkpointing components in their own processor "
                                    + "to let them control when their segment's token advances.",
                            participants.size());
            }
            return new CheckpointingProgressStrategyFactory(participants, autoCheckpointing);
        };
    }

    @Override
    public SegmentProgressStrategy create(SegmentProgressContext context) {
        return new CheckpointingProgressStrategy(context, participants, autoCheckpointing);
    }
}
