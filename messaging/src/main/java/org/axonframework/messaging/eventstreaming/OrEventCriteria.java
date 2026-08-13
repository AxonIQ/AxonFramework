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

package org.axonframework.messaging.eventstreaming;

import org.axonframework.messaging.core.QualifiedName;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of the {@link EventCriteria} that matches when at least one of the provided criteria matches.
 *
 * @author Steven van Beelen
 * @author Allard Buijze
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
sealed class OrEventCriteria implements EventCriteria permits EventTypeRestrictableOrEventCriteria {

    private final Set<EventCriteria> criteria;

    /**
     * Constructs an {@link EventCriteria} that matches when at least one of the provided {@code criteria} matches. Any
     * {@link OrEventCriteria} are flattened into the set of criteria.
     *
     * @param criteria The set of {@link EventCriteria} to match against, of which at least one must match.
     */
    OrEventCriteria(Set<EventCriteria> criteria) {
        this.criteria = new HashSet<>();
        for (EventCriteria c : criteria) {
            if (c instanceof OrEventCriteria orEventCriteria) {
                this.criteria.addAll(orEventCriteria.criteria);
            } else {
                this.criteria.add(c);
            }
        }
    }

    @Override
    public Set<EventCriterion> flatten() {
        return criteria.stream()
                       .flatMap(c -> c.flatten().stream())
                       .collect(Collectors.toSet());
    }

    @Override
    public boolean matches(QualifiedName type, Set<Tag> tags) {
        return criteria.stream().anyMatch(criteria -> criteria.matches(type, tags));
    }

    @Override
    public EventCriteria or(EventCriteria criteria) {
        if (criteria instanceof AnyEvent) {
            return criteria;
        }
        Set<EventCriteria> combinedCriteria = new HashSet<>(this.criteria);
        if (criteria instanceof OrEventCriteria orEventCriteria) {
            combinedCriteria.addAll(orEventCriteria.criteria);
        } else {
            combinedCriteria.add(criteria);
        }
        return combinedCriteria.equals(this.criteria) ? this : new OrEventCriteria(combinedCriteria);
    }

    @Override
    public boolean hasCriteria() {
        return criteria.stream().anyMatch(EventCriteria::hasCriteria);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof OrEventCriteria orEventCriteria) {
            return Objects.equals(criteria, orEventCriteria.criteria);
        }
        return false;
    }

    @Override
    public String toString() {
        return "OrEventCriteria[" +
                "criteria=" + criteria + ']';
    }

    public Set<EventCriteria> criteria() {
        return criteria;
    }

    @Override
    public int hashCode() {
        return Objects.hash(criteria);
    }
}
