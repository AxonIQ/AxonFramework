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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link EventTypeResolver}.
 *
 * @author Steven van Beelen
 */
class EventTypeResolverTest {

    @Nested
    class DefaultResolver {

        @Test
        void passesStoredVersionThroughWhenPresent() {
            // given
            EventTypeResolver resolver = EventTypeResolver.DEFAULT;

            // when
            MessageType result = resolver.resolve("com.example.OrderPlaced", "1.2.3");

            // then
            assertThat(result).isEqualTo(new MessageType("com.example.OrderPlaced", "1.2.3"));
        }

        @Test
        void substitutesMissingVersionDefaultForNullVersion() {
            // given
            EventTypeResolver resolver = EventTypeResolver.DEFAULT;

            // when
            MessageType result = resolver.resolve("com.example.OrderPlaced", null);

            // then
            assertThat(result.version()).isEqualTo(EventTypeResolver.MISSING_VERSION_DEFAULT);
        }

        @Test
        void substitutesMissingVersionDefaultForEmptyVersion() {
            // given
            EventTypeResolver resolver = EventTypeResolver.DEFAULT;

            // when
            MessageType result = resolver.resolve("com.example.OrderPlaced", "");

            // then
            assertThat(result.version()).isEqualTo(EventTypeResolver.MISSING_VERSION_DEFAULT);
        }
    }

    @Nested
    class WithDefaultVersion {

        @Test
        void passesStoredVersionThroughWhenPresent() {
            // given
            EventTypeResolver resolver = EventTypeResolver.withDefaultVersion("custom");

            // when
            MessageType result = resolver.resolve("com.example.OrderPlaced", "2.0.0");

            // then
            assertThat(result.version()).isEqualTo("2.0.0");
        }

        @Test
        void substitutesConfiguredDefaultForNullVersion() {
            // given
            EventTypeResolver resolver = EventTypeResolver.withDefaultVersion("custom");

            // when
            MessageType result = resolver.resolve("com.example.OrderPlaced", null);

            // then
            assertThat(result.version()).isEqualTo("custom");
        }

        @Test
        void substitutesConfiguredDefaultForEmptyVersion() {
            // given
            EventTypeResolver resolver = EventTypeResolver.withDefaultVersion("custom");

            // when
            MessageType result = resolver.resolve("com.example.OrderPlaced", "");

            // then
            assertThat(result.version()).isEqualTo("custom");
        }

        @Test
        void nullDefaultVersionThrowsException() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> EventTypeResolver.withDefaultVersion(null))
                    .isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void emptyDefaultVersionThrowsException() {
            assertThatThrownBy(() -> EventTypeResolver.withDefaultVersion(""))
                    .isInstanceOf(AxonConfigurationException.class);
        }
    }
}
