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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating type replacement on complete {@link EventCriteria} instances.
 */
class EventCriteriaReplaceEventTypesTest {

    private static final QualifiedName TOPPED_UP = new QualifiedName("credits.CreditsToppedUp");
    private static final QualifiedName USED = new QualifiedName("credits.CreditsUsed");
    private static final QualifiedName CLOSED = new QualifiedName("credits.AccountClosed");
    private static final Tag FIRST_ACCOUNT = Tag.of("accountId", "one");
    private static final Tag SECOND_ACCOUNT = Tag.of("accountId", "two");

    @Nested
    class ReplaceEventTypes {

        @Test
        void addsTypesToAnUntypedTaggedCriterion() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT);

            // when
            EventCriteria result = criteria.replaceEventTypes(Set.of(USED));

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
            EventCriteria result = criteria.replaceEventTypes(USED.fullName());

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
            EventCriteria result = criteria.replaceEventTypes(Set.of(USED, CLOSED));

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
            EventCriteria result = criteria.replaceEventTypes(Set.of(USED, CLOSED));

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
            EventCriteria result = criteria.replaceEventTypes(Set.of(USED));

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion -> {
                assertThat(criterion.tags()).containsExactly(FIRST_ACCOUNT);
                assertThat(criterion.types()).containsExactly(USED);
            });
            assertThat(result.matches(USED, Set.of(FIRST_ACCOUNT))).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of(FIRST_ACCOUNT))).isFalse();
        }

        @Test
        void acceptsClassesForTheReplacementTypes() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT).andBeingOneOfTypes(TOPPED_UP);

            // when
            EventCriteria result = criteria.replaceEventTypes(CreditsUsed.class, CreditsExpired.class);

            // then
            assertThat(result.flatten()).singleElement().satisfies(criterion ->
                    assertThat(criterion.types()).containsExactlyInAnyOrder(
                            new QualifiedName(CreditsUsed.class),
                            new QualifiedName(CreditsExpired.class)
                    )
            );
        }

        @Test
        void emptyReplacementClearsTheTypeRestrictionButPreservesTags() {
            // given
            EventCriteria criteria = EventCriteria.havingTags(FIRST_ACCOUNT).andBeingOneOfTypes(TOPPED_UP);

            // when
            EventCriteria result = criteria.replaceEventTypes(Set.of());

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
            EventCriteria result = criteria.replaceEventTypes(Set.of(USED));

            // then
            assertThat(result.matches(USED, Set.of())).isTrue();
            assertThat(result.matches(TOPPED_UP, Set.of())).isFalse();
        }
    }

    private static class CreditsUsed {
    }

    private static class CreditsExpired {
    }
}
