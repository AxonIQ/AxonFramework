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

package org.axonframework.eventsourcing.eventstore.jpa;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventsourcing.eventstore.EventTypeResolver;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link AggregateBasedJpaEventStorageEngineConfiguration}.
 *
 * @author Steven van Beelen
 */
class AggregateBasedJpaEventStorageEngineConfigurationTest {

    @Test
    void nullFinalBatchPredicateThrowsException() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.finalBatchPredicate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroBatchSizeThrowsException() {
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.batchSize(0))
                .isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void negativeBatchSizeThrowsException() {
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.batchSize(-1))
                .isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void negativeGapCleaningThresholdThrowsException() {
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.gapCleaningThreshold(-1))
                .isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void negativeMaxGapOffsetThrowsException() {
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.maxGapOffset(-1))
                .isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void negativeLowestGlobalSequenceThrowsException() {
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.lowestGlobalSequence(-1))
                .isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void negativeGapTimeoutThrowsException() {
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.gapTimeout(-1))
                .isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void nullEventTypeResolverThrowsException() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.eventTypeResolver(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eventTypeResolverSetsGivenEventTypeResolver() {
        // given
        EventTypeResolver custom = (qualifiedName, version) -> EventTypeResolver
                .withDefaultVersion("custom").resolve(qualifiedName, version);

        // when
        AggregateBasedJpaEventStorageEngineConfiguration result =
                AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT.eventTypeResolver(custom);

        // then
        assertThat(result.eventTypeResolver()).isSameAs(custom);
    }
}