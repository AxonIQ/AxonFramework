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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An {@link AppendObserver} that logs every append outcome at {@code DEBUG} level.
 * <p>
 * Each log line starts with the kind of outcome ({@code "Append committed"}, {@code "Append rejected"},
 * {@code "Append failed"} or {@code "Append rolled back"}), followed by the {@link AppendCondition} and the appended
 * events. Events are rendered by their {@link EventMessage#type() type}, {@link EventMessage#identifier() identifier}
 * and {@link TaggedEventMessage#tags() tags} only. Payloads and metadata are never logged, as they may contain
 * personal or otherwise sensitive data. Tag values and the {@link AppendCondition} are logged as is, so avoid tag
 * values that are sensitive themselves when enabling this log. A failed append is logged together with the stack
 * trace of its cause.
 * <p>
 * Nothing is rendered unless {@code DEBUG} is enabled for the {@code LoggingAppendObserver} logger, so the observer
 * itself does no work when that level is off. It is the observer
 * {@link AppendObservingEventStorageEngine#decoratorDefinition()} falls back to when no {@link AppendObserver}
 * component is registered, so enabling observation of appends only requires registering that decorator and turning
 * on {@code DEBUG} logging for {@code org.axonframework.eventsourcing.eventstore.LoggingAppendObserver}:
 * <pre>{@code
 * EventSourcingConfigurer.create()
 *                        .componentRegistry(registry -> registry.registerDecorator(
 *                                AppendObservingEventStorageEngine.decoratorDefinition()
 *                        ));
 * }</pre>
 *
 * @author Alexey Afanasyev
 * @see AppendObservingEventStorageEngine
 * @since 5.5.0
 */
public class LoggingAppendObserver implements AppendObserver {

    private static final Logger logger = LoggerFactory.getLogger(LoggingAppendObserver.class);

    /**
     * Constructs a {@code LoggingAppendObserver} logging to the {@code LoggingAppendObserver} logger.
     */
    public LoggingAppendObserver() {
        // No configuration needed; the logger is shared by all instances.
    }

    /**
     * Logs the given {@code observation} at {@code DEBUG} level, if that level is enabled.
     *
     * @param observation the outcome of an append, together with what was appended
     */
    @Override
    public void observe(AppendObservation observation) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        AppendCondition condition = observation.condition();
        String events = describe(observation.events());
        switch (observation.outcome()) {
            case AppendObservation.Outcome.Committed committed -> logger.debug(
                    "Append committed: consistency marker [{}], condition [{}], events [{}].",
                    committed.consistencyMarker(), condition, events
            );
            case AppendObservation.Outcome.Rejected rejected -> logger.debug(
                    "Append rejected: [{}], conflicting event [{}], condition [{}], events [{}].",
                    rejected.exception().getMessage(),
                    rejected.exception().conflictingEvent().map(LoggingAppendObserver::describe).orElse("unknown"),
                    condition, events
            );
            case AppendObservation.Outcome.Failed failed -> logger.debug(
                    "Append failed: condition [{}], events [{}].",
                    condition, events, failed.cause()
            );
            case AppendObservation.Outcome.RolledBack ignored -> logger.debug(
                    "Append rolled back: condition [{}], events [{}].",
                    condition, events
            );
        }
    }

    private static String describe(List<TaggedEventMessage<?>> events) {
        return events.stream()
                     .map(tagged -> describe(tagged.event()) + " " + describe(tagged.tags()))
                     .collect(Collectors.joining(", "));
    }

    private static String describe(Set<Tag> tags) {
        return tags.stream()
                   .map(tag -> tag.key() + "=" + tag.value())
                   .sorted()
                   .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String describe(EventMessage event) {
        return event.type() + " (" + event.identifier() + ")";
    }
}
