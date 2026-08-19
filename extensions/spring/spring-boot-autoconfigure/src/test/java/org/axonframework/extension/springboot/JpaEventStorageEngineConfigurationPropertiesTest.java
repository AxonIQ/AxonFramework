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

package org.axonframework.extension.springboot;

import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link JpaEventStorageEngineConfigurationProperties} binding, verifying that an application that sets no
 * {@code axon.eventstorage.jpa.*} property is configured exactly as one that uses
 * {@link AggregateBasedJpaEventStorageEngineConfiguration#DEFAULT} directly.
 *
 * @author Stefan Dragisic
 */
class JpaEventStorageEngineConfigurationPropertiesTest {

    private static final AggregateBasedJpaEventStorageEngineConfiguration CORE_DEFAULTS =
            AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT;

    private static JpaEventStorageEngineConfigurationProperties bindWithoutAnyProperty() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("axon.eventstorage.jpa", JpaEventStorageEngineConfigurationProperties.class);
    }

    @Nested
    class DefaultsMatchTheCoreConfiguration {

        @Test
        void gapTimeoutDefaultsToTheCoreDefault() {
            // given no axon.eventstorage.jpa property is set
            // when
            JpaEventStorageEngineConfigurationProperties properties = bindWithoutAnyProperty();

            // then the gap timeout is not shortened relative to the core default, which would risk losing events
            assertThat(properties.gapTimeout()).isEqualTo(CORE_DEFAULTS.gapTimeout());
        }

        @Test
        void maxGapOffsetDefaultsToTheCoreDefault() {
            // given no axon.eventstorage.jpa property is set
            // when
            JpaEventStorageEngineConfigurationProperties properties = bindWithoutAnyProperty();

            // then
            assertThat(properties.maxGapOffset()).isEqualTo(CORE_DEFAULTS.maxGapOffset());
        }

        @Test
        void everySharedSettingDefaultsToTheCoreDefault() {
            // given no axon.eventstorage.jpa property is set
            // when
            JpaEventStorageEngineConfigurationProperties properties = bindWithoutAnyProperty();

            // then every setting the two configuration paths share agrees, so neither path silently drifts
            assertThat(properties.batchSize()).isEqualTo(CORE_DEFAULTS.batchSize());
            assertThat(properties.gapCleaningThreshold()).isEqualTo(CORE_DEFAULTS.gapCleaningThreshold());
            assertThat(properties.gapTimeout()).isEqualTo(CORE_DEFAULTS.gapTimeout());
            assertThat(properties.lowestGlobalSequence()).isEqualTo(CORE_DEFAULTS.lowestGlobalSequence());
            assertThat(properties.maxGapOffset()).isEqualTo(CORE_DEFAULTS.maxGapOffset());
        }
    }

    @Nested
    class ExplicitPropertiesOverrideTheDefaults {

        @Test
        void gapTimeoutAndMaxGapOffsetAreBoundToTheirOwnProperty() {
            // given
            MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                    "axon.eventstorage.jpa.gap-timeout", "1234",
                    "axon.eventstorage.jpa.max-gap-offset", "4321"
            ));

            // when
            JpaEventStorageEngineConfigurationProperties properties = new Binder(source)
                    .bindOrCreate("axon.eventstorage.jpa", JpaEventStorageEngineConfigurationProperties.class);

            // then
            assertThat(properties.gapTimeout()).isEqualTo(1234);
            assertThat(properties.maxGapOffset()).isEqualTo(4321);
        }
    }
}
