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

package org.axonframework.hunt.harness;

import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.GlobalIndexConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelCriterion;
import org.axonframework.hunt.model.ModelEvent;
import org.axonframework.hunt.model.ModelTag;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventstreaming.EventCriterion;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Translates what the framework actually handed the storage engine into the reference model's vocabulary.
 * <p>
 * The model-conformance oracle judges the append the engine was asked to perform, not the one the workload thought it
 * was asking for. Those differ by design: the framework derives the condition from the sourcing that preceded the
 * append, merging markers and OR-ing criteria. Recording the derived condition is therefore the whole point, and this
 * class is the one place where the translation lives.
 * <p>
 * One boundary is worth knowing. A criteria tree is read through
 * {@link org.axonframework.messaging.eventstreaming.EventCriteria#flatten()}, which returns the empty set for the
 * match-everything criteria, and the model reads an empty criteria set as match-everything too, so the two agree. They
 * would not agree for a criteria tree that OR-ed match-everything together with a tagged criterion, because flattening
 * drops the former while the interpreted form keeps it. No workload in this suite builds that shape.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class AxonModelCodec {

    private AxonModelCodec() {
        // Utility class.
    }

    /**
     * Translates the condition an append was validated against.
     *
     * @param condition the framework's append condition
     * @return the model's equivalent
     */
    public static ModelAppendCondition toModelCondition(AppendCondition condition) {
        Objects.requireNonNull(condition, "The condition cannot be null.");
        return new ModelAppendCondition(GlobalIndexConsistencyMarker.position(condition.consistencyMarker()),
                                        toModelCriteria(condition.criteria().flatten()));
    }

    /**
     * Translates a flattened Dynamic Consistency Boundary.
     *
     * @param criteria the flattened criteria
     * @return the model's equivalent; empty means match everything, in both vocabularies
     */
    public static Set<ModelCriterion> toModelCriteria(Set<EventCriterion> criteria) {
        Objects.requireNonNull(criteria, "The criteria cannot be null.");
        Set<ModelCriterion> translated = new LinkedHashSet<>();
        for (EventCriterion criterion : criteria) {
            translated.add(new ModelCriterion(criterion.types().stream()
                                                       .map(QualifiedName::toString)
                                                       .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                                              toModelTags(criterion.tags())));
        }
        return Set.copyOf(translated);
    }

    /**
     * Translates a batch of tagged events.
     *
     * @param events the events offered to the store, in offer order
     * @return the model's equivalents, in the same order
     */
    public static List<ModelEvent> toModelEvents(List<TaggedEventMessage<?>> events) {
        Objects.requireNonNull(events, "The events cannot be null.");
        return events.stream().map(AxonModelCodec::toModelEvent).toList();
    }

    /**
     * Translates one tagged event.
     *
     * @param event the event offered to the store
     * @return the model's equivalent
     */
    public static ModelEvent toModelEvent(TaggedEventMessage<?> event) {
        Objects.requireNonNull(event, "The event cannot be null.");
        return new ModelEvent(event.event().identifier(),
                              event.event().type().qualifiedName().toString(),
                              toModelTags(event.tags()));
    }

    private static Set<ModelTag> toModelTags(Set<Tag> tags) {
        Set<ModelTag> translated = new LinkedHashSet<>();
        for (Tag tag : tags) {
            translated.add(new ModelTag(tag.key(), tag.value()));
        }
        return Set.copyOf(translated);
    }
}
