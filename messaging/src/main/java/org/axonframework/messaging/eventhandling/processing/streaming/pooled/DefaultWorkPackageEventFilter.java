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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.ProcessorEventHandlingComponents;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorContext;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentMatcher;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of a {@link WorkPackage.EventFilter} that filters events based on the
 * {@link EventHandlingComponent} and {@link SegmentMatcher}.
 * <p>
 * This filter checks if the event message is supported by the event handling component and if it matches the segment.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
class DefaultWorkPackageEventFilter implements WorkPackage.EventFilter {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWorkPackageEventFilter.class);

    private final String eventProcessor;
    private final ProcessorEventHandlingComponents eventHandlingComponents;
    private final ErrorHandler errorHandler;

    DefaultWorkPackageEventFilter(
            String eventProcessor,
            ProcessorEventHandlingComponents eventHandlingComponents,
            ErrorHandler errorHandler
    ) {
        this.eventProcessor = Objects.requireNonNull(eventProcessor, "EventProcessor name may not be null");
        this.eventHandlingComponents = Objects.requireNonNull(eventHandlingComponents,
                                                              "ProcessorEventHandlingComponents may not be null");
        this.errorHandler = Objects.requireNonNull(errorHandler, "ErrorHandler may not be null");
    }

    /**
     * Indicates whether the processor can/should handle the given {@code eventMessage} for the given {@code segment}.
     * <p>
     * This implementation will delegate the decision to the {@link EventHandlingComponent}.
     * <p>
     * When any of the components returns the {@link SequencingPolicy#BROADCAST_SEQUENCE_IDENTIFIER} sequence identifier
     * for the given {@code eventMessage}, segment matching is skipped and the event is handled by every segment.
     *
     * @param eventMessage The message for which to identify if the processor can handle it.
     * @param segment      The segment for which the event should be processed.
     * @return {@code true} if the event message should be handled, otherwise {@code false}.
     * @throws Exception If the {@code errorHandler} throws an Exception back on the
     *                   {@link ErrorHandler#handleError(ErrorContext)} call.
     */
    @Override
    public boolean canHandle(
            EventMessage eventMessage,
            ProcessingContext context,
            Segment segment
    ) throws Exception {
        try {
            var eventMessageQualifiedName = eventMessage.type().qualifiedName();
            var eventSupported = eventHandlingComponents.supports(eventMessageQualifiedName);
            if (!eventSupported) {
                logger.trace("Processor [{}] filtered out event [{}] of type [{}]: "
                                     + "event type not supported by event handling components.",
                             eventProcessor, eventMessage.identifier(), eventMessageQualifiedName);
                return false;
            }

            var isReplay = TrackingToken.fromContext(context)
                                        .filter(ReplayToken::isReplay)
                                        .isPresent();
            if (isReplay && !eventHandlingComponents.supportsReset()) {
                logger.trace("Processor [{}] filtered out event [{}] of type [{}] during replay: "
                                     + "event handling components do not support reset.",
                             eventProcessor, eventMessage.identifier(), eventMessage.type().qualifiedName());
                return false;
            }

            var sequenceIdentifiers = eventHandlingComponents.sequenceIdentifiersFor(eventMessage, context);
            if (sequenceIdentifiers.contains(SequencingPolicy.BROADCAST_SEQUENCE_IDENTIFIER)) {
                return true;
            }
            return sequenceIdentifiers.stream().anyMatch(identifier -> new SegmentMatcher(
                    (e, ctx) -> Optional.of(identifier)).matches(segment, eventMessage, context)
            );
        } catch (Exception e) {
            errorHandler.handleError(
                    new ErrorContext(eventProcessor, e, Collections.singletonList(eventMessage), context)
            );
            return false;
        }
    }
}
