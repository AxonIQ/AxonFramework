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

package org.axonframework.test.matchers;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.hamcrest.Matcher;

import java.util.List;
import java.util.function.Predicate;

/**
 * Utility class containing static methods to obtain instances of (list) {@link Matcher Matchers}.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public abstract class Matchers {

    /**
     * Returns a {@link Matcher} that matches a list of {@link Message} if a list containing their respective payloads
     * matches the given {@code matcher}.
     *
     * @param matcher The matcher to match against {@link Message#payload() Message's payload}.
     * @return A matcher that matches against the {@link Message#payload() Message payloads}.
     */
    public static Matcher<List<? extends Message>> payloadsMatching(final Matcher<? extends List<?>> matcher) {
        return new PayloadsMatcher(matcher);
    }

    /**
     * Returns a {@link Matcher} that matches a single {@link Message} if the given {@code payloadMatcher} matches that
     * message's payload.
     *
     * @param payloadMatcher The matcher to match against the {@link Message#payload() Message's payload}.
     * @param <T>            The generic type of {@link Message} matched on by the resulting payload matcher.
     * @return A matcher that evaluates a the {@link Message#payload() Message payload}.
     */
    public static <T extends Message> Matcher<T> messageWithPayload(Matcher<?> payloadMatcher) {
        return new PayloadMatcher<>(payloadMatcher);
    }

    /**
     * Returns a {@link Matcher} that matches a List where all the given matchers must match with at least one of the
     * items in that list.
     *
     * @param matchers the matchers that should match against one of the items in the List.
     * @param <T>      The generic item type matched by the given {@code matchers}.
     * @return A matcher that matches a number of matchers against a list.
     */
    @SafeVarargs
    public static <T> Matcher<List<T>> listWithAllOf(Matcher<T>... matchers) {
        return new ListWithAllOfMatcher<>(matchers);
    }

    /**
     * Returns a {@link Matcher} that matches a list of items of type {@code T} where at least one of the given
     * {@code matchers} matches any of the items in that list.
     *
     * @param matchers The matchers that should match against one of the items of type {@code T} in the list.
     * @param <T>      The generic item type matched by the given {@code matchers}.
     * @return A matcher that matches a number of generic-type-matchers against a list of objects.
     */
    @SafeVarargs
    public static <T> Matcher<List<T>> listWithAnyOf(Matcher<T>... matchers) {
        return new ListWithAnyOfMatcher<>(matchers);
    }

    /**
     * Returns a {@link Matcher} that matches a list of items of type {@code T} if each of the {@code matchers} match
     * against an item that comes after the item that the previous matcher matched against.
     * <p>
     * This means that the given {@code matchers} must match in order, but there may be "gaps" of unmatched items in
     * between. To match the exact sequence of items (i.e. without gaps), use
     * {@link #exactSequenceOf(org.hamcrest.Matcher[])}.
     *
     * @param matchers The matchers to match against the list of items of type {@code T}.
     * @param <T>      The generic item type matched by the given {@code matchers}.
     * @return A matcher that matches a number of item-matchers against a list of items of type {@code T}.
     */
    @SafeVarargs
    public static <T> Matcher<List<T>> sequenceOf(Matcher<T>... matchers) {
        return new SequenceMatcher<>(matchers);
    }

    /**
     * Returns a {@link Matcher} that matches a list of items of type {@code T} if each of the given {@code matchers}
     * matches against the item at the respective index in the list.
     * <p>
     * This means the first matcher must match the first item, the second matcher the second item, and so on. Any excess
     * items are ignored. If there are excess {@link Matcher Matchers}, they will be evaluated against {@code null}. To
     * make sure the number of items matches the number of {@code Matchers}, you can append an extra
     * {@link #andNoMore()} matcher.
     * <p/>
     * To allow "gaps" of unmatched items, use {@link #sequenceOf(org.hamcrest.Matcher[])} instead.
     *
     * @param matchers The matchers to match against the list of items of type {@code T}.
     * @param <T>      The generic item type matched by the given {@code matchers}.
     * @return A matcher that matches a number of item-matchers against a list of items of type {@code T}.
     */
    @SafeVarargs
    public static <T> Matcher<List<T>> exactSequenceOf(Matcher<T>... matchers) {
        return new ExactSequenceMatcher<>(matchers);
    }

    /**
     * Returns a {@link Matcher} that matches with values of type {@code T} defined by the given {@code predicate}.
     * <p>
     * This method is a synonym for {@link #predicate(Predicate)} to allow for better readability.
     *
     * @param predicate The predicate defining matching values.
     * @param <T>       The generic item type verified by the given {@code predicate}.
     * @return A matcher that matches against values of type {@code T} defined by the predicate.
     */
    public static <T> Matcher<T> matches(Predicate<T> predicate) {
        return predicate(predicate);
    }

    /**
     * Returns a {@link Matcher} that matches with values of type {@code T} defined by the given {@code predicate}.
     * <p>
     * This method is a synonym for {@link #matches(Predicate)} to allow for better readability.
     *
     * @param predicate The predicate defining matching values
     * @param <T>       The generic item type verified by the given {@code predicate}.
     * @return A matcher that matches against values of type {@code T} defined by the predicate.
     */
    public static <T> Matcher<T> predicate(Predicate<T> predicate) {
        return new PredicateMatcher<>(predicate);
    }

    /**
     * Returns a {@link Matcher} that matches an empty {@link List} of {@link EventMessage events}.
     *
     * @return A matcher that matches an empty {@link List} of {@link EventMessage events}.
     */
    public static Matcher<List<EventMessage>> noEvents() {
        return new EmptyCollectionMatcher<>("events");
    }

    /**
     * Returns a {@link Matcher} that matches an empty {@link List} of {@link CommandMessage command}.
     *
     * @return A matcher that matches an empty {@link List} of {@link CommandMessage command}.
     */
    public static Matcher<List<CommandMessage>> noCommands() {
        return new EmptyCollectionMatcher<>("commands");
    }

    /**
     * Returns a {@link Matcher} that matches with exact class type defined by the given {@code expected}.
     *
     * @param expected The expected class.
     * @param <T>      The object type to match the given {@code expected} class with.
     * @return A matcher that matches based on the {@code expected} class.
     */
    public static <T> Matcher<T> exactClassOf(Class<T> expected) {
        return new ExactClassMatcher<>(expected);
    }

    /**
     * Constructs a deep equals {@link Matcher}.
     * <p>
     * This {@code Matcher} will first perform a regular equals check based on the given {@code expected} and
     * {@code actual} (provided during the {@link Matcher#matches(Object)} invocation). If this fails and given type
     * does not override {@link Object#equals(Object)}, this {@code Matcher} will match the fields of the
     * given {@code expected} and {@code actual}.
     *
     * @param expected The object to match against.
     * @param <T>      The type of the {@code expected} object to match against.
     * @return A matcher matching on {@link Object#equals(Object)} firstly, followed by field value equality if equals
     * isn't implemented.
     */
    public static <T> Matcher<T> deepEquals(T expected) {
        return deepEquals(expected, AllFieldsFilter.instance());
    }

    /**
     * Constructs a deep equals {@link Matcher}.
     * <p>
     * This {@code Matcher} will first perform a regular equals check based on the given {@code expected} and
     * {@code actual} (provided during the {@link Matcher#matches(Object)} invocation). If this fails and given type
     * does not override {@link Object#equals(Object)}, this {@code Matcher} will match the fields of the
     * given {@code expected} and {@code actual}. Fields can be in- or excluded for this last step through the
     * {@code filter}.
     *
     * @param expected The object to match against.
     * @param filter   The filter describing the Fields to include in the comparison.
     * @param <T>      The type of the {@code expected} object to match against.
     * @return A matcher matching on {@link Object#equals(Object)} firstly, followed by field value equality if equals
     * isn't implemented.
     */
    public static <T> Matcher<T> deepEquals(T expected, FieldFilter filter) {
        return new DeepEqualsMatcher<>(expected, filter);
    }

    /**
     * Returns a {@link Matcher} that matches against {@code null} or {@code void}.
     * <p>
     * Can be used to make sure no trailing items remain when using an
     * {@link #exactSequenceOf(org.hamcrest.Matcher[]) exact sequence matcher}.
     *
     * @param <T> The type of the object to match against.
     * @return A matcher that matches against "nothing".
     */
    public static <T> Matcher<T> andNoMore() {
        return nothing();
    }

    /**
     * Returns a {@link Matcher} that matches against {@code null} or {@code void}.
     * <p>
     * Can be used to make sure no trailing items remain when using an
     * {@link #exactSequenceOf(org.hamcrest.Matcher[]) exact sequence matcher}.
     *
     * @param <T> The type of the object to match against.
     * @return A matcher that matches against "nothing".
     */
    public static <T> Matcher<T> nothing() {
        return new NullOrVoidMatcher<>();
    }

    private Matchers() {
    }
}
