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
import org.axonframework.messaging.eventhandling.EventMessage;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Describes the criteria for {@code EventStoreTransaction#source(SourcingCondition) sourcing} or
 * {@link StreamableEventSource#open(StreamingCondition,
 * org.axonframework.messaging.core.unitofwork.ProcessingContext) streaming} events. The criteria are
 * used to filter the events to read from a streamable event source (like an Event Store).
 * <p>
 * <h3>Filtering</h3>
 * Filtering happens based on the tags of the event, indicating an association during
 * publishing of the event. For example, a student enrolling in a course may have the "student" tag with the value of
 * its id. This value is determined during publishing by the {@code TagResolver}. If an instance of a criteria contains
 * multiple tags, the event must contain all of them to be considered a match.
 *
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         .havingTags(Tag.of("student", "matchingStudent"))
 *    }</pre>
 * <p>
 * After first defining the tags to filter on through {@link #havingTags(Tag...)} or one of its variants, the scope of
 * the read on the event store can further be limited on the {@link EventMessage#type() type}, through
 * {@link EventTypeRestrictableEventCriteria#andBeingOneOfTypes(QualifiedName...)}. This is optional, and will default
 * to all types if not specified.
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         // Reads all events with this tag
 *         .havingTags(Tag.of("student", "matchingStudent"))
 *         .or()
 *         // Only reads events with this tag and type
 *         .havingTags("course", "matchingCourse")
 *         .andBeingOneOfTypes(new QualifiedName("CourseRegistered"))
 *
 *    }
 *    </pre>
 * <p>
 * When using an event store that supports it, using such a type-filtered criteria will narrow the scope of your
 * transaction, leading to better performance and a lower chance of concurrency conflicts. So while optional, it's
 * recommended to use it when possible.
 *
 * <h3>Combining</h3>
 * You can combine multiple criteria using {@link #either(EventCriteria...)}, or in a fluent fashion using
 * {@link #or()}. This allows you to create more complex criteria that match events based on multiple tags or types.
 * However, it's not possible to create AND conditions between multiple criteria.
 *
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         .havingTags(Tag.of("student", "matchingStudent"))
 *         .andBeingOneOfTypes(new QualifiedName("StudentEnrolled"))
 *         .or()
 *         .havingTags("course", "matchingCourse")
 *         .andBeingOneOfTypes(new QualifiedName("CourseRegistered"))
 *    }
 *    </pre>
 *
 * <h3>Examples</h2>
 * To make it easier to understand how the criteria work, here are some examples. These examples all have the following
 * events in the event store:
 * <ul>
 *     <li> Event [StudentRegistered, student -> matchingStudent]</li>
 *     <li> Event [CourseRegistered, course -> matchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> nonMatchingStudent, course -> matchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> matchingStudent, course -> nonMatchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> matchingStudent, course -> matchingStudent]</li>
 *     <li> Event [StudentRegistered, student -> nonMatchingStudent]</li>
 *     <li> Event [CourseRegistered, course -> nonMatchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> nonMatchingStudent, course -> nonMatchingCourse]</li>
 * </ul>
 * <h4>Example 1</h4>
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         .havingTags(Tag.of("student", "matchingStudent"))
 *    }</pre>
 * This criteria will match all events with the tag "student" and the value "matchingStudent". The following events will
 * match:
 * <ul>
 *     <li> Event [StudentRegistered, student -> matchingStudent]</li>
 *     <li> Event [StudentAssignedToCourse, student -> matchingStudent, course -> nonMatchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> matchingStudent, course -> matchingStudent]</li>
 * </ul>
 * <h4>Example 2</h4>
 * We don't have to pass in {@link Tag#of(String, String) tags} as {@link Tag} instances. We can also pass them in as
 * key-value pairs:
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         .havingTags("student", "matchingStudent", "course", "matchingCourse")
 *    }</pre>
 * <p>
 * This criteria will match all events with both the "student" tag of value "matchingStudent" and the "course" tag with
 * value "matchingStudent". The following events will match:
 * <ul>
 *     <li> Event [StudentRegistered, student -> matchingStudent]</li>
 *     <li> Event [StudentAssignedToCourse, student -> matchingStudent, course -> matchingStudent]</li>
 * </ul>
 * <h4>Example 3</h4>
 * We can also combine multiple criteria using {@link #or(EventCriteria)}, such as searching for multiple students:
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         .havingTags("student", "matchingStudent")
 *         .or()
 *         .havingTags("student", "nonMatchingStudent")
 *    }</pre>
 * <p>
 * This works as an OR condition, meaning that the following events will match:
 * <ul>
 *     <li> Event [StudentRegistered, student -> matchingStudent]</li>
 *     <li> Event [StudentAssignedToCourse, student -> matchingStudent, course -> nonMatchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> nonMatchingStudent, course -> matchingCourse]</li>
 *     <li> Event [StudentAssignedToCourse, student -> nonMatchingStudent, course -> nonMatchingCourse]</li>
 * </ul>
 * <h4>Example 4</h4>
 * Last but not least, let's say that we only want the "StudentRegistered" events for the "matchingStudent". This
 * can be done by using the {@link EventTypeRestrictableEventCriteria#andBeingOneOfTypes(QualifiedName...)} method:
 * <pre>
 *     {@code
 *     EventCriteria criteria = EventCriteria
 *         .havingTags("student", "matchingStudent")
 *         .andBeingOneOfTypes(new QualifiedName("StudentRegistered"))
 *    }</pre>
 * The following events will match:
 * <ul>
 *     <li> Event [StudentRegistered, student -> matchingStudent]</li>
 * </ul>
 * See {@link EventTypeRestrictableEventCriteria} for all the methods available to you.
 *
 * <h3>Flattening</h3>
 * The criteria can be flattened into a {@link Set} of {@link EventCriterion} instances, which are a specialized
 * representation of the criteria that are guaranteed to be non-nested. As such, these instances can be used to read
 * their {@link EventCriterion#tags()} and {@link EventCriterion#types()} without the need to interpret conditions,
 * as this is already done by the {@code flatten} method.
 *
 * @author Michal Negacz
 * @author Milan Savić
 * @author Marco Amann
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public sealed interface EventCriteria
        permits OrEventCriteria, TagAndTypeFilteredEventCriteria, TagFilteredEventCriteria, AnyEvent, EventCriterion,
        EventTypeRestrictableEventCriteria {

    /**
     * Construct a {@code EventCriteria} that allows <b>any</b> events.
     * <p>
     * Use this instance when all events are of interest during
     * {@link StreamableEventSource#open(StreamingCondition, org.axonframework.messaging.core.unitofwork.ProcessingContext) streaming}
     * or when there are no consistency boundaries to validate during appending. Note that this {@code EventCriteria} does not make
     * sense for sourcing, as it is <b>not</b> recommended to source the entire event store.
     * <p>
     * Event though this criteria will not filter any tags, you can limit the types of events to be matched by using the
     * {@link EventTypeRestrictableEventCriteria#andBeingOneOfTypes(Set)} method.
     *
     * @return An {@code EventCriteria} that contains no criteria at all.
     */
    static EventTypeRestrictableEventCriteria havingAnyTag() {
        return AnyEvent.INSTANCE;
    }

    /**
     * Define that the event must contain all the provided {@code tags} to match. These tags function in an AND
     * relation, meaning that an event must have all tags to match. A partial match is not sufficient.
     * <p>
     * You can further limit the types of events to be matched by using the
     * {@link EventTypeRestrictableEventCriteria#andBeingOneOfTypes(Set)} method.
     *
     * @param tags The tags to match against.
     * @return The completed EventCriteria instance.
     */
    static EventTypeRestrictableEventCriteria havingTags(Set<Tag> tags) {
        if (tags.isEmpty()) {
            return AnyEvent.INSTANCE;
        }
        return new TagFilteredEventCriteria(tags);
    }

    /**
     * Define that the event must contain all the provided {@code tags} to match. These tags function in an AND
     * relation, meaning that an event must have all tags to match. A partial match is not sufficient.
     * <p>
     * You can further limit the types of events to be matched by using the
     * {@link EventTypeRestrictableEventCriteria#andBeingOneOfTypes(Set)} method.
     *
     * @param tags The tags to match against.
     * @return The completed EventCriteria instance.
     */
    static EventTypeRestrictableEventCriteria havingTags(Tag... tags) {
        return havingTags(Set.of(tags));
    }

    /**
     * Define, as key-value pairs, that the event must contain all the provided {@code tags} to match. These tags
     * function in an AND relation, meaning that an event must have all tags to match. A partial match is not
     * sufficient.
     * <p>
     * You can further limit the types of events to be matched by using the
     * {@link EventTypeRestrictableEventCriteria#andBeingOneOfTypes(Set)} method.
     *
     * @param tags The tags to match against.
     * @return The completed EventCriteria instance.
     */
    static EventTypeRestrictableEventCriteria havingTags(String... tags) {
        if ((tags.length & 1) == 1) {
            throw new IllegalArgumentException("Tags must be in pairs of key and value");
        }
        var tagSet = new HashSet<Tag>();
        for (int i = 0; i < tags.length; i += 2) {
            tagSet.add(new Tag(tags[i], tags[i + 1]));
        }

        return havingTags(tagSet);
    }

    /**
     * Create an {@code EventCriteria} that matches events from either {@code this} or the given
     * {@code criteria EventCriteria}.
     *
     * @param criteria The {@code EventCriteria} to match in addition to this {@code EventCriteria}.
     * @return An {@code EventCriteria} that matches events that match either this {@code EventCriteria} or the given
     * {@code EventCriteria}.
     */
    EventCriteria or(EventCriteria criteria);


    /**
     * Create an {@code EventCriteria} that matches events that match either of the given {@code EventCriteria}.
     *
     * @param eventCriteria The {@code EventCriteria} of which one must match.
     * @return An {@code EventCriteria} that matches events that match either of the given {@code EventCriteria}.
     */
    static EventCriteria either(Collection<EventCriteria> eventCriteria) {
        Objects.requireNonNull(eventCriteria, "The eventCriteria cannot be null.");
        return new OrEventCriteria(new HashSet<>(eventCriteria));
    }

    /**
     * Create an {@code EventCriteria} that matches events that match either of the given {@code EventCriteria}.
     *
     * @param eventCriteria The {@code EventCriteria} of which one must match.
     * @return An {@code EventCriteria} that matches events that match either of the given {@code EventCriteria}.
     */
    static EventCriteria either(EventCriteria... eventCriteria) {
        return new OrEventCriteria(Arrays.stream(eventCriteria).collect(Collectors.toSet()));
    }


    /**
     * Start a builder to construct an additional {@code EventCriteria} that will be combined with this one using
     * {@link #or(EventCriteria)}. The resulting {@code EventCriteria} will match events that match either this
     * {@code EventCriteria} or the built one.
     *
     * @return A builder to construct an EventCriteria instance that will match events that match either this
     * {@code EventCriteria} or the built one.
     */
    default OrEventCriteriaBuilder or() {
        return new OrEventCriteriaBuilder(this);
    }

    /**
     * Indicates whether the given {@code type} and {@code tags} matches the types and tags defined in this or these
     * criteria. If no types are defined, any given {@code type} will be considered a match.
     *
     * @param type The type to match against this criteria instance.
     * @param tags The tags to match against this criteria instance.
     * @return {@code true} if the type matches, otherwise {@code false}.
     */
    boolean matches(QualifiedName type, Set<Tag> tags);

    /**
     * Flatten this, possibly nested, {@code EventCriteria} into a {@link Set} of {@link EventCriterion}. These
     * {@code EventCriterion} instances can be used by event stores to construct queries without the need
     * to interpret the criteria.
     *
     * @return The flattened set of {@code EventCriteria}.
     */
    Set<EventCriterion> flatten();

    /**
     * Indicates whether this {@code EventCriteria} instance has any criteria defined.
     *
     * @return {@code true} if this {@code EventCriteria} instance has criteria defined, otherwise {@code false}.
     */
    boolean hasCriteria();
}
