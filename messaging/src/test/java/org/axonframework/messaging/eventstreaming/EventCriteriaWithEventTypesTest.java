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

import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link EventCriteria#withEventTypes(Set)} and its overloads on complete criteria.
 */
class EventCriteriaWithEventTypesTest {

    private static final QualifiedName TOPPED_UP = new QualifiedName("credits.CreditsToppedUp");
    private static final QualifiedName USED = new QualifiedName("credits.CreditsUsed");
    private static final QualifiedName CLOSED = new QualifiedName("credits.AccountClosed");
    private static final Tag FIRST_ACCOUNT = Tag.of("accountId", "one");
    private static final Tag SECOND_ACCOUNT = Tag.of("accountId", "two");

    @Nested
    class WithEventTypes {

        @Test
        void addsTypesToAnUntypedTaggedCriterion() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT);

            // when
            EventCriteria result = criteria.withEventTypes(Set.of(USED));

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion -> {
                assertThat(criterion.tags()).containsExactly(FIRST_ACCOUNT);
                assertThat(criterion.types()).containsExactly(USED);
            });
        }

        @Test
        void replacesTypesOnEveryBranchOfAnOrUnion() {
            // given
            EventCriteria criteria = EventCriteria
                    .havingTags(FIRST_ACCOUNT)
                    .andBeingOneOfTypes(TOPPED_UP, USED)
                    .or(EventCriteria.havingTags(SECOND_ACCOUNT));

            // when
            EventCriteria result = criteria.withEventTypes(USED.fullName());

            // then
            assertThat(result.flatten()).allSatisfy(criterion -> assertThat(criterion.types()).containsExactly(USED));
            assertThat(result.matches(USED, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(USED, Set.of(SECOND_ACCOUNT))).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of(FIRST_ACCOUNT))).isFalse();
        }

        @Test
        void replacesDifferingTypesOnEveryBranchWithTheCompleteRequestedSet() {
            // given
            EventCriteria criteria = EventCriteria
                    .havingTags(FIRST_ACCOUNT)
                    .andBeingOneOfTypes(TOPPED_UP, USED)
                    .or(EventCriteria.havingTags(SECOND_ACCOUNT)
                                     .andBeingOneOfTypes(TOPPED_UP, CLOSED));

            // when
            EventCriteria result = criteria.withEventTypes(Set.of(USED, CLOSED));

            // then
            assertThat(result.matches(USED, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(CLOSED, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(CLOSED, Set.of(SECOND_ACCOUNT))).isTrue();
            assertThat(result.matches(USED, Set.of(SECOND_ACCOUNT))).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of(FIRST_ACCOUNT))).isFalse();
            assertThat(result.matches(TOPPED_UP, Set.of(SECOND_ACCOUNT))).isFalse();
        }

        @Test
        void replacesAnExistingTypeRestrictionWithABroaderSet() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT)
                                                    .andBeingOneOfTypes(TOPPED_UP, USED);

            // when
            EventCriteria result = criteria.withEventTypes(Set.of(USED, CLOSED));

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion -> {
                assertThat(criterion.tags()).containsExactly(FIRST_ACCOUNT);
                assertThat(criterion.types()).containsExactlyInAnyOrder(USED, CLOSED);
            });
        }

        @Test
        void replacesAnExistingTypeRestrictionWithANarrowerSet() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT)
                                                    .andBeingOneOfTypes(TOPPED_UP, USED);

            // when
            EventCriteria result = criteria.withEventTypes(Set.of(USED));

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion -> {
                assertThat(criterion.tags()).containsExactly(FIRST_ACCOUNT);
                assertThat(criterion.types()).containsExactly(USED);
            });
            assertThat(result.matches(USED, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of(FIRST_ACCOUNT))).isFalse();
        }

        @Test
        void acceptsQualifiedNamesForTheReplacementTypes() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT).andBeingOneOfTypes(TOPPED_UP);

            // when
            EventCriteria result = criteria.withEventTypes(USED, CLOSED);

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion ->
                    assertThat(criterion.types()).containsExactlyInAnyOrder(USED, CLOSED)
            );
        }

        @Test
        void resolvesClassesThroughTheGivenMessageTypeResolver() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT).andBeingOneOfTypes(TOPPED_UP);
            MessageTypeResolver messageTypeResolver = new AnnotationMessageTypeResolver();

            // when
            EventCriteria result = criteria.withEventTypes(messageTypeResolver,
                                                           CreditsUsed.class,
                                                           CreditsExpired.class);

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion ->
                    assertThat(criterion.types()).containsExactlyInAnyOrder(
                            new QualifiedName(CreditsUsed.class),
                            new QualifiedName(CreditsExpired.class)
                    )
            );
        }

        @Test
        void resolvedNameFollowsTheNameAnEventIsAnnotatedWith() {
            // given an event whose stored name differs from its class name
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT);
            MessageTypeResolver messageTypeResolver = new AnnotationMessageTypeResolver();

            // when
            EventCriteria result = criteria.withEventTypes(messageTypeResolver, CreditsRefunded.class);

            // then
            assertThat(result.matches(new QualifiedName("credits", "CreditsRefunded"), Set.of(FIRST_ACCOUNT)))
                    .isTrue();
            assertThat(result.matches(new QualifiedName(CreditsRefunded.class), Set.of(FIRST_ACCOUNT))).isFalse();
        }

        @Test
        void rejectsANullMessageTypeResolver() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT);

            // when / then
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> criteria.withEventTypes((MessageTypeResolver) null, CreditsUsed.class))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MessageTypeResolver");
        }

        @Test
        void emptyReplacementClearsTheTypeRestrictionButPreservesTags() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT).andBeingOneOfTypes(TOPPED_UP);

            // when
            EventCriteria result = criteria.withEventTypes(Set.of());

            // then
            assertThat(result.matches(TOPPED_UP, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(USED, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(CLOSED, Set.of())).isFalse();
        }

        @Test
        void restrictsCriteriaThatPreviouslyMatchedEveryEvent() {
            // given
            EventCriteria criteria = EventCriteria.havingAnyTag();

            // when
            EventCriteria result = criteria.withEventTypes(Set.of(USED));

            // then
            assertThat(result.matches(USED, Set.of())).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of())).isFalse();
        }

        @Test
        void unionWithAnUnrestrictedBranchStaysUnrestrictedByTag() {
            // given a union in which one branch matches every event
            EventCriteria criteria = EventCriteria.either(EventCriteria.havingAnyTag(),
                                                          EventCriteria.havingTags(FIRST_ACCOUNT));

            // when
            EventCriteria result = criteria.withEventTypes(Set.of(USED));

            // then events of the requested type match under any tag, as they did before the replacement
            assertThat(result.matches(USED, Set.of(Tag.of("accountId", "other")))).isTrue();
            assertThat(result.matches(USED, Set.of())).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of(FIRST_ACCOUNT))).isFalse();
        }
    }

    private static class CreditsUsed {
    }

    private static class CreditsExpired {
    }

    @Event(namespace = "credits", name = "CreditsRefunded")
    private static class CreditsRefunded {
    }
}
