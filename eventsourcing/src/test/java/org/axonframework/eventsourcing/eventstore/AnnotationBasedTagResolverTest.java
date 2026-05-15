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

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.EventTags;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the behaviour of the {@link AnnotationBasedTagResolver}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AnnotationBasedTagResolverTest {

    private final AnnotationBasedTagResolver tagResolver = new AnnotationBasedTagResolver();

    @Test
    void shouldThrowExceptionOnNullEvent() {
        // when/then
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> tagResolver.resolve(null));
    }

    @Nested
    class RecordTests {

        record TestRecord(
                @EventTag String id,
                @EventTag(key = "customKey") String value,
                String nonTagged
        ) {

        }

        record NullableRecord(
                @EventTag String id,
                @EventTag String nullValue
        ) {

        }

        @Test
        void shouldResolveTagsFromRecord() {
            // given
            var payload = new TestRecord("123", "test", "ignored");
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(2, result.size());
            assertTrue(result.contains(new Tag("id", "123")));
            assertTrue(result.contains(new Tag("customKey", "test")));
        }

        @Test
        void shouldIgnoreTagsWithNullValues() {
            // given
            var payload = new NullableRecord("123", null);
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            var nullValueTagNotPresent = result.stream().noneMatch(it -> it.key().equals("nullValue"));
            assertTrue(nullValueTagNotPresent);
        }
    }

    @Nested
    class ClassTests {

        static class TestClass {

            @EventTag
            private final String id;

            @EventTag(key = "customField")
            private final Integer value;

            @SuppressWarnings("unused")
            private final String nonTagged;

            TestClass(String id, Integer value, String nonTagged) {
                this.id = id;
                this.value = value;
                this.nonTagged = nonTagged;
            }

            @EventTag
            public String getTaggedMethod() {
                return "methodValue";
            }

            @EventTag(key = "customMethod")
            public String getCustomTaggedMethod() {
                return "customMethodValue";
            }
        }

        @Test
        void shouldResolveTagsFromFieldsAndMethods() {
            // given
            var payload = new TestClass("123", 456, "ignored");
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(4, result.size());
            assertTrue(result.contains(new Tag("id", "123")));
            assertTrue(result.contains(new Tag("customField", "456")));
            assertTrue(result.contains(new Tag("taggedMethod", "methodValue")));
            assertTrue(result.contains(new Tag("customMethod", "customMethodValue")));
        }

        @Test
        void shouldNotResolveTagsFromNotAnnotatedMembers() {
            // given
            var payload = new TestClass("123", 456, "ignored");
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertFalse(result.contains(new Tag("nonTagged", "ignored")));
        }
    }

    @Nested
    class ComplexCases {

        record ComplexRecord(
                @EventTag String id,
                @EventTag(key = "count") Integer count,
                @EventTag(key = "complexTag") ComplexTag complexTag
        ) {

        }

        record ComplexTag(String value1, boolean value2) {

        }

        @Test
        void shouldHandleComplexTypes() {
            // given
            var complexTag = new ComplexTag("value1", true);
            var payload = new ComplexRecord("123",
                                            42,
                                            complexTag);
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(3, result.size());
            assertTrue(result.contains(new Tag("id", "123")));
            assertTrue(result.contains(new Tag("complexTag", complexTag.toString())));
            assertTrue(result.contains(new Tag("count", "42")));
        }
    }

    @Nested
    class CollectionTests {

        static class CollectionTagClass {

            @EventTag
            private final List<Integer> numbers = List.of(1, 2, 3);

            @EventTag(key = "customKey")
            private final Set<String> words = Set.of("hello", "world");

            @EventTag
            public Collection<Double> getScores() {
                return List.of(95.5, 87.3);
            }
        }

        @Test
        void shouldCreateTagsFromCollection() {
            // given
            var payload = new CollectionTagClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(7, result.size());
            // Tags from numbers list
            assertTrue(result.contains(new Tag("numbers", "1")));
            assertTrue(result.contains(new Tag("numbers", "2")));
            assertTrue(result.contains(new Tag("numbers", "3")));
            // Tags from words set with custom key
            assertTrue(result.contains(new Tag("customKey", "hello")));
            assertTrue(result.contains(new Tag("customKey", "world")));
            // Tags from scores method
            assertTrue(result.contains(new Tag("scores", "95.5")));
            assertTrue(result.contains(new Tag("scores", "87.3")));
        }

        static class ListWithNullClass {

            @EventTag
            private final List<String> withNull = Arrays.asList("valid", null, "alsoValid");
        }

        @Test
        void shouldSkipTagsWithNullValue() {
            // given
            var payload = new ListWithNullClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(2, result.size());
            assertTrue(result.contains(new Tag("withNull", "valid")));
            assertTrue(result.contains(new Tag("withNull", "alsoValid")));
        }
    }

    @Nested
    class IterableTests {

        static class CustomIterable implements Iterable<String> {

            @Override
            public @NonNull Iterator<String> iterator() {
                return Arrays.asList("one", "two", "three").iterator();
            }
        }

        static class IterableTagClass {

            @EventTag
            private final CustomIterable customIterable = new CustomIterable();

            @EventTag(key = "range")
            private final Iterable<Integer> numberRange = () ->
                    IntStream.range(1, 4).boxed().iterator();
        }

        @Test
        void shouldHandleCustomIterables() {
            // given
            var payload = new IterableTagClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(6, result.size());
            // Tags from custom iterable
            assertTrue(result.contains(new Tag("customIterable", "one")));
            assertTrue(result.contains(new Tag("customIterable", "two")));
            assertTrue(result.contains(new Tag("customIterable", "three")));
            // Tags from number range
            assertTrue(result.contains(new Tag("range", "1")));
            assertTrue(result.contains(new Tag("range", "2")));
            assertTrue(result.contains(new Tag("range", "3")));
        }
    }

    @Nested
    class MapTests {

        static class MapTagClass {

            @EventTag
            private final Map<String, Integer> counts = Map.of(
                    "apple", 5,
                    "banana", 3
            );

            @EventTag(key = "fruit")
            private final Map<String, String> fruits = Map.of(
                    "color1", "red",
                    "color2", "yellow"
            );

            @EventTag
            public Map<String, Double> getPrices() {
                return Map.of(
                        "item1", 10.5,
                        "item2", 20.75
                );
            }
        }

        @Test
        void shouldUseMapKeysWhenNoAnnotationKeyProvided() {
            // given
            var payload = new MapTagClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(6, result.size());
            // Tags from counts map (using map keys, not property name)
            assertTrue(result.contains(new Tag("apple", "5")));
            assertTrue(result.contains(new Tag("banana", "3")));
            // Tags from fruits map (using annotation key)
            assertTrue(result.contains(new Tag("fruit", "red")));
            assertTrue(result.contains(new Tag("fruit", "yellow")));
            // Tags from prices method (using map keys, not method name)
            assertTrue(result.contains(new Tag("item1", "10.5")));
            assertTrue(result.contains(new Tag("item2", "20.75")));

            // Verify property/method names are NOT used as keys
            assertFalse(result.stream().anyMatch(tag -> tag.key().equals("counts")));
            assertFalse(result.stream().anyMatch(tag -> tag.key().equals("prices")));
        }

        static class NullableMapClass {

            @EventTag
            private final Map<String, String> withNullValues = new HashMap<>() {{
                put("key1", "value1");
                put("key2", null);
                put(null, "value3");
            }};

            @EventTag(key = "customKey")
            private final Map<String, String> withCustomKey = new HashMap<>() {{
                put("key1", "value1");
                put("key2", null);
                put(null, "value3");
            }};
        }

        @Test
        void shouldHandleNullKeysAndValues() {
            // given
            var payload = new NullableMapClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(3, result.size());
            // For map without custom key - use map keys, skip null entries
            assertTrue(result.contains(new Tag("key1", "value1")));
            // For map with custom key - use custom key, skip null values
            assertTrue(result.contains(new Tag("customKey", "value1")));
            assertTrue(result.contains(new Tag("customKey", "value3")));
        }
    }

    @Nested
    class InvalidMethodTests {

        static class InvalidMethodClass {

            @EventTag
            public String methodWithParameters(String param) {
                return param;
            }
        }

        static class VoidMethodClass {

            @EventTag
            public void voidMethod() {
                // Method with void return type
            }
        }

        static class ExceptionThrowingMethodClass {

            @EventTag
            private String methodThrowingException() {
                throw new RuntimeException("Test exception");
            }
        }

        static class GetterMethodClass {

            @EventTag
            public String getName() {
                return "name";
            }

            @EventTag
            public String getidentifier() { // doesn't follow getter convention (lowercase after 'get')
                return "123";
            }

            @EventTag
            public String get() { // too short to be a getter
                return "value";
            }
        }

        @Test
        void shouldThrowExceptionForVoidMethod() {
            // given
            var payload = new VoidMethodClass();
            var event = anEventMessage(payload);

            // when/then
            var exception = assertThrows(
                    AnnotationBasedTagResolver.TagResolutionException.class,
                    () -> tagResolver.resolve(event)
            );
            assertTrue(exception.getMessage().contains("should not return void"));
        }

        @Test
        void shouldThrowExceptionForMethodWithParameters() {
            // given
            var payload = new InvalidMethodClass();
            var event = anEventMessage(payload);

            // when/then
            var exception = assertThrows(
                    AnnotationBasedTagResolver.TagResolutionException.class,
                    () -> tagResolver.resolve(event)
            );
            assertTrue(exception.getMessage().contains("should not contain any parameters"));
        }

        @Test
        void shouldStripGetPartFromGetterMethods() {
            // given
            var payload = new GetterMethodClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(3, result.size());
            assertTrue(result.contains(new Tag("name", "name")));
            assertTrue(result.contains(new Tag("getidentifier", "123"))); // not a proper getter
            assertTrue(result.contains(new Tag("get", "value"))); // not a proper getter
        }
    }

    @Nested
    class InheritanceAndVisibilityTests {

        static class BaseClass {

            @EventTag
            private final String privateBaseField = "privateBaseValue";

            @EventTag(key = "customBase")
            protected String protectedBaseField = "protectedBaseValue";

            @EventTag
            private String getBaseValue() {
                return "baseMethodValue";
            }
        }

        static class InheritedTagClass extends BaseClass {

            @EventTag
            private final String privateChildField = "privateChildValue";

            @EventTag
            public String publicChildField = "publicChildValue";

            @EventTag
            public String getChildValue() {
                return "childMethodValue";
            }
        }

        @Test
        void shouldResolveAllFieldsAndMethodsRegardlessOfVisibility() {
            // given
            var payload = new InheritedTagClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(6, result.size());
            // Parent class tags
            assertTrue(result.contains(new Tag("privateBaseField", "privateBaseValue")));
            assertTrue(result.contains(new Tag("customBase", "protectedBaseValue")));
            assertTrue(result.contains(new Tag("baseValue", "baseMethodValue")));
            // Child class tags
            assertTrue(result.contains(new Tag("privateChildField", "privateChildValue")));
            assertTrue(result.contains(new Tag("publicChildField", "publicChildValue")));
            assertTrue(result.contains(new Tag("childValue", "childMethodValue")));
        }
    }

    @Nested
    class RepeatableAnnotationTests {

        static class MultiTagClass {

            @EventTag(key = "classId")
            @EventTag(key = "identifier")
            @EventTag // will use field name
            private final String id = "123";

            @EventTag(key = "value")
            @EventTag(key = "amount")
            private final Integer value = 456;

            @EventTag // will use method name (without 'get')
            public String getName() {
                return "test";
            }

            @EventTag(key = "customKey1")
            @EventTag(key = "customKey2")
            public String getCustomValue() {
                return "customValue";
            }
        }

        @Test
        void shouldHandleRepeatableAnnotationsOnFields() {
            // given
            var payload = new MultiTagClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(8, result.size());
            // Tags from 'id' field
            assertTrue(result.contains(new Tag("classId", "123")));
            assertTrue(result.contains(new Tag("identifier", "123")));
            assertTrue(result.contains(new Tag("id", "123")));
            // Tags from 'value' field
            assertTrue(result.contains(new Tag("value", "456")));
            assertTrue(result.contains(new Tag("amount", "456")));
            // Tags from getName method
            assertTrue(result.contains(new Tag("name", "test")));
            // Tags from getCustomValue method
            assertTrue(result.contains(new Tag("customKey1", "customValue")));
            assertTrue(result.contains(new Tag("customKey2", "customValue")));
        }
    }

    @Nested
    class ContainerAnnotationTests {

        static class ContainerAnnotationClass {

            @EventTags({
                    @EventTag(key = "identifier"),
                    @EventTag // will use field name
            })
            private final String id = "123";

            // Using repeatable syntax for comparison
            @EventTag(key = "value")
            @EventTag(key = "amount")
            private final Integer value = 456;

            @EventTags({
                    @EventTag(key = "methodName"),
                    @EventTag // will use method name (without 'get')
            })
            public String getName() {
                return "test";
            }
        }

        static class MixedAnnotationClass {

            // Using container annotation
            @EventTags({
                    @EventTag(key = "first"),
                    @EventTag(key = "second")
            })
            // Adding repeatable annotation
            @EventTag(key = "third")
            private final String mixedField = "mixedValue";

            // Using container annotation
            @EventTags({
                    @EventTag(key = "one"),
                    @EventTag(key = "two")
            })
            // Adding repeatable annotation
            @EventTag(key = "three")
            public String getMixedValue() {
                return "mixed";
            }
        }

        @Test
        void shouldHandleContainerAnnotation() {
            // given
            var payload = new ContainerAnnotationClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(6, result.size());
            // Tags from container annotation on 'id' field
            assertTrue(result.contains(new Tag("id", "123")));
            assertTrue(result.contains(new Tag("identifier", "123")));
            // Tags from repeatable annotation on 'value' field
            assertTrue(result.contains(new Tag("value", "456")));
            assertTrue(result.contains(new Tag("amount", "456")));
            // Tags from container annotation on getName method
            assertTrue(result.contains(new Tag("methodName", "test")));
            assertTrue(result.contains(new Tag("name", "test")));
        }

        @Test
        void shouldHandleMixedContainerAndRepeatableAnnotations() {
            // given
            var payload = new MixedAnnotationClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertEquals(6, result.size());
            // Tags from field with mixed annotation
            assertTrue(result.contains(new Tag("first", "mixedValue")));
            assertTrue(result.contains(new Tag("second", "mixedValue")));
            assertTrue(result.contains(new Tag("third", "mixedValue")));
            // Tags from method with mixed annotation
            assertTrue(result.contains(new Tag("one", "mixed")));
            assertTrue(result.contains(new Tag("two", "mixed")));
            assertTrue(result.contains(new Tag("three", "mixed")));
        }

        static class InvalidContainerClass {

            @EventTags({}) // Empty container
            private final String emptyContainer = "value";
        }

        @Test
        void shouldHandleEmptyContainer() {
            // given
            var payload = new InvalidContainerClass();
            var event = anEventMessage(payload);

            // when
            var result = tagResolver.resolve(event);

            // then
            assertTrue(result.isEmpty());
        }
    }

    private EventMessage anEventMessage(Object payload) {
        return new GenericEventMessage(new MessageType("event"), payload);
    }
}