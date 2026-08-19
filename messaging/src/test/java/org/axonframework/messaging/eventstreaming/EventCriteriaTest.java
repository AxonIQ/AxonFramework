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

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.Set;

import static org.axonframework.messaging.eventstreaming.EventCriteria.havingTags;
import static org.junit.jupiter.api.Assertions.*;

class EventCriteriaTest {

    @Test
    void criteriaForAnyEventWillAlwaysMatch() {
        EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOfAnyType();

        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of()));
        assertTrue(testSubject.matches(new QualifiedName("Another"), Set.of()));
        assertTrue(testSubject.matches(new QualifiedName("OneType"),
                                       Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"), new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));
        assertTrue(testSubject.matches(new QualifiedName("Another"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
    }

    @Test
    void canConstructCriteriaWithOnlyTagsInDifferentWaysWithSameResult() {
        EventCriteria testSubject1 = havingTags("key1", "value1").andBeingOfAnyType();
        EventCriteria testSubject2 = havingTags(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1")).andBeingOfAnyType();
        EventCriteria testSubject3 = havingTags(Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))).andBeingOfAnyType();

        assertEquals(testSubject1, testSubject2);
        assertEquals(testSubject1, testSubject3);
    }

    @Test
    void canConstructCriteriaWithOnlyEventTypesInDifferentWaysWithSameResult() {
        EventCriteria testSubject1 = EventCriteria.havingAnyTag()
                                                  .andBeingOneOfTypes("OneType");
        EventCriteria testSubject2 = EventCriteria.havingAnyTag()
                                                  .andBeingOneOfTypes(Set.of(new QualifiedName("OneType")));
        EventCriteria testSubject3 = EventCriteria.havingAnyTag()
                                                  .andBeingOneOfTypes(new QualifiedName("OneType"));
        EventCriteria testSubject4 = EventCriteria.havingAnyTag()
                                                  .andBeingOneOfTypes(
                                                          payloadType -> Optional.of(new MessageType(null, "OneType", "0.0.1")),
                                                          String.class
                                                  );

        assertEquals(testSubject1, testSubject2);
        assertEquals(testSubject1, testSubject3);
        assertEquals(testSubject1, testSubject4);
    }

    @Test
    void eventCriteriaBuiltUsingOrConditionInDifferentWaysHaveSameResult() {
        EventCriteria testSubject1 = havingTags("key1", "value1")
                .or()
                .havingTags("key2", "value2");
        EventCriteria testSubject2 = havingTags("key2", "value2")
                .or()
                .havingTags("key1", "value1");

        assertEquals(testSubject1, testSubject2);
        assertEquals(testSubject2, testSubject1);
    }

    @Test
    void buildingUsingNestedOrsShouldLeadToSameResultAsNonNesting() {
        EventCriteria testSubject1 = havingTags("key1", "value1")
                .or()
                .havingTags("key2", "value2")
                .or()
                .havingTags("key3", "value3")
                .or()
                .havingTags("key4", "value4");

        EventCriteria testSubject2 = havingTags("key1", "value1")
                .or(havingTags("key2", "value2")
                            .or(havingTags("key3", "value3")
                                        .or(havingTags("key4", "value4"))
                            )
                );

        assertEquals(testSubject1, testSubject2);
        assertEquals(testSubject2, testSubject1);
    }

    @Test
    void criteriaForEventsOfSpecificTypeIgnoresOtherTypes() {
        EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOneOfTypes("OneType");

        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of()));
        assertTrue(testSubject.matches(new QualifiedName("OneType"),
                                       Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"), new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));

        assertFalse(testSubject.matches(new QualifiedName("Another"), Set.of()));
        assertFalse(testSubject.matches(new QualifiedName("Another"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
    }

    @Test
    void criteriaWithTypesAndTagsIgnoresTagsForOtherTypes() {
        EventCriteria testSubject = havingTags("key1", "value1")
                                                 .andBeingOneOfTypes("OneType");

        assertTrue(testSubject.matches(new QualifiedName("OneType"),
                                       Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"), new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));

        assertFalse(testSubject.matches(new QualifiedName("OneType"), Set.of()));
        assertFalse(testSubject.matches(new QualifiedName("Another"), Set.of()));
        assertFalse(testSubject.matches(new QualifiedName("Another"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
    }

    @Test
    void criteriaWithTypesAndTagsIgnoresEventsWithSubsetOfTags() {
        EventCriteria testSubject = havingTags("key1", "value1", "key2", "value2")
                                                 .andBeingOneOfTypes("OneType");

        assertTrue(testSubject.matches(new QualifiedName("OneType"),
                                       Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"), new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));

        assertFalse(testSubject.matches(new QualifiedName("OneType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
        assertFalse(testSubject.matches(new QualifiedName("OneType"), Set.of()));
        assertFalse(testSubject.matches(new QualifiedName("Another"), Set.of()));
        assertFalse(testSubject.matches(new QualifiedName("Another"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
    }

    @Test
    void criteriaWithOddParameterCountForTagsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> havingTags("odd"));
        assertThrows(IllegalArgumentException.class,
                     () -> havingTags("odd", "even", "odd"));
    }

    @Test
    void criteriaWithEqualParametersAreConsideredEqual() {
        EventCriteria testSubject1 = havingTags("key1", "value1").andBeingOneOfTypes("OneType");
        EventCriteria testSubject2 = havingTags(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1")).andBeingOneOfTypes("OneType");
        EventCriteria testSubject3 = havingTags(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))
                .andBeingOneOfTypes("OtherType");
        EventCriteria testSubject4 = havingTags(new org.axonframework.messaging.eventstreaming.Tag("key2", "value2s")).andBeingOneOfTypes("OneType");
        EventCriteria testSubject5 = EventCriteria.havingAnyTag().andBeingOfAnyType();
        EventCriteria testSubject6 = havingTags(Set.of()).andBeingOfAnyType();

        assertEquals(testSubject1, testSubject2);
        assertEquals(testSubject5, testSubject6);

        assertNotEquals(testSubject1, testSubject3);
        assertNotEquals(testSubject1, testSubject4);
        assertNotEquals(testSubject1, testSubject5);
        assertNotEquals(testSubject1, testSubject6);

        assertNotEquals(testSubject2, testSubject3);
        assertNotEquals(testSubject2, testSubject4);
        assertNotEquals(testSubject2, testSubject5);
        assertNotEquals(testSubject2, testSubject6);

        assertNotEquals(testSubject3, testSubject4);
        assertNotEquals(testSubject3, testSubject5);
        assertNotEquals(testSubject3, testSubject6);

        assertNotEquals(testSubject4, testSubject5);
        assertNotEquals(testSubject4, testSubject6);
    }

    @Test
    void criteriaWithOrConditionMatchBoth() {
        EventCriteria testSubject = havingTags("key1", "value1").andBeingOneOfTypes("OneType")
                                                 .or().havingTags("key2", "value2").andBeingOneOfTypes("OtherType");

        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
        assertTrue(testSubject.matches(new QualifiedName("OtherType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));

        assertFalse(testSubject.matches(new QualifiedName("OneType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));
        assertFalse(testSubject.matches(new QualifiedName("OtherType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
    }

    @Test
    void criteriaWithOrOnAnyEventWillMatchAllEvents() {
        EventCriteria testSubject = havingTags("key1", "value1")
                .andBeingOfAnyType()
                                                 .or()
                                                 .havingAnyTag()
                .andBeingOfAnyType();

        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
        assertTrue(testSubject.matches(new QualifiedName("OtherType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));
    }

    @Test
    void criteriaWithAnyTagsWillMatchAllEventsOfThatType() {
        EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOneOfTypes("OneType");

        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key2", "value2"))));
        assertTrue(testSubject.matches(new QualifiedName("OneType"), Set.of()));
        assertFalse(testSubject.matches(new QualifiedName("TypeTwo"), Set.of(new org.axonframework.messaging.eventstreaming.Tag("key1", "value1"))));
        assertFalse(testSubject.matches(new QualifiedName("TypeTwo"), Set.of(new Tag("key2", "value2"))));
        assertFalse(testSubject.matches(new QualifiedName("TypeTwo"), Set.of()));
    }

    @Test
    void whenNestingIsDifferentSameCriteriaStillLeadToEquals() {
        EventCriteria testSubject1 = havingTags("key1", "value1").andBeingOneOfTypes("OneType")
                                                  .or().havingTags("key2", "value2").andBeingOneOfTypes("OtherType")
                                                  .or().havingAnyTag().andBeingOneOfTypes("ThirdType");
        EventCriteria testSubject2 = havingTags("key2", "value2").andBeingOneOfTypes("OtherType")
                                                  .or().havingTags("key1", "value1").andBeingOneOfTypes("OneType")
                                                  .or().havingAnyTag().andBeingOneOfTypes("ThirdType");

        assertEquals(testSubject1, testSubject2);
    }

    @Test
    void anyEventIsFlattenedToNothing() {
        EventCriteria testSubject = EventCriteria.havingAnyTag();

        assertTrue(testSubject.flatten().isEmpty());
    }


    @Nested
    class FlattenTest {


        @Test
        void flatteningTwoConditionsInOrLeadsToTwoCriteria() {
            EventCriteria testSubject1 = havingTags("key1", "value1").andBeingOneOfTypes("OneType");
            EventCriteria testSubject2 = havingTags("key2", "value2").andBeingOneOfTypes("OneType");
            EventCriteria combined = testSubject1.or(testSubject2);

            Set<EventCriterion> flattened = combined.flatten();
            assertEquals(2, flattened.size());
            assertTrue(flattened.contains(testSubject1));
            assertTrue(flattened.contains(testSubject2));
        }

        @Test
        void makingOrOfOrCriteriaWillMergeCriteria() {
            EventCriteria testSubject1 = havingTags("key1", "value1").andBeingOneOfTypes("OneType");
            EventCriteria testSubject2 = havingTags("key2", "value2").andBeingOneOfTypes("OneType");
            EventCriteria testSubject3 = havingTags("key3", "value3").andBeingOneOfTypes("OneType");
            EventCriteria combined1 = testSubject1.or(testSubject2);
            EventCriteria combined2 = testSubject1.or(testSubject3);
            EventCriteria combined = combined1.or(combined2);

            Set<EventCriterion> flattened = combined.flatten();
            assertEquals(3, flattened.size());
            assertTrue(flattened.contains(testSubject1));
            assertTrue(flattened.contains(testSubject2));
            assertTrue(flattened.contains(testSubject3));
        }

        @Test
        void combiningSameCriteriaInOrLeadsToSingularCriteriaDuringFlatten() {
            EventCriteria testSubject1 = havingTags("key1", "value1").andBeingOneOfTypes("OneType");
            EventCriteria testSubject2 = havingTags("key1", "value1").andBeingOneOfTypes("OneType");
            EventCriteria combined = testSubject1.or(testSubject2);

            assertEquals(1, combined.flatten().size());
        }

        @Test
        void combiningOrCriteriaWithItselfReturnsExistingInstance() {
            // given
            EventCriteria first = havingTags("key1", "value1");
            EventCriteria second = havingTags("key2", "value2");
            EventCriteria combined = first.or(second);

            // when
            EventCriteria result = combined.or(combined);

            // then
            assertSame(combined, result);
        }

        @Test
        void combiningOrCriteriaWithAnExistingBranchReturnsExistingInstance() {
            // given
            EventCriteria first = havingTags("key1", "value1");
            EventCriteria second = havingTags("key2", "value2");
            EventCriteria combined = first.or(second);

            // when
            EventCriteria result = combined.or(first);

            // then
            assertSame(combined, result);
        }

        @Test
        void flatteningFilteredCriteriaWithNoTagsOrTypesLeadsToEmptySet() {
            EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOfAnyType();

            assertTrue(testSubject.flatten().isEmpty());
        }
    }

    @Nested
    class HasCriteriaTest {

        @Test
        void anyEventDoesNotHaveCriteria() {
            EventCriteria testSubject = EventCriteria.havingAnyTag();

            assertFalse(testSubject.hasCriteria());
        }

        @Test
        void criteriaWithEventTypesAndTagsWillHaveCriteria() {
            EventCriteria testSubject = havingTags("key1", "value1").andBeingOneOfTypes("OneType");

            assertTrue(testSubject.hasCriteria());
        }

        @Test
        void criteriaWithEventTypesAndWithoutTagsWillHaveCriteria() {
            EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOneOfTypes("OneType");

            assertTrue(testSubject.hasCriteria());
        }

        @Test
        void criteriaWithoutEventTypesAndWithoutTagsWillNotHaveCriteria() {
            EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOfAnyType();

            assertFalse(testSubject.hasCriteria());
        }

        @Test
        void orConditionWithCriteriaWillHaveCriteria() {
            EventCriteria testSubject = havingTags("key1", "value1").andBeingOneOfTypes("OneType")
                                                     .or()
                                                     .havingTags("key2", "value2").andBeingOneOfTypes("OtherType");

            assertTrue(testSubject.hasCriteria());
        }

        @Test
        void orConditionWithAnyEventWillNotHaveCriteria() {
            EventCriteria testSubject = EventCriteria.havingAnyTag().andBeingOfAnyType()
                                                     .or().havingAnyTag().andBeingOfAnyType();

            assertFalse(testSubject.hasCriteria());
        }
    }
}
