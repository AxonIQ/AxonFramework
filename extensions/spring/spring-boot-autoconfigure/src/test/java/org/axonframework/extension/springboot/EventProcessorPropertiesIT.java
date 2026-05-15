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

import org.axonframework.extension.spring.config.EventProcessorSettings;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating how the
 * {@link org.axonframework.extension.springboot.autoconfig.EventProcessingAutoConfiguration} class constructs an
 * {@link EventProcessorSettings.MapWrapper} based on the properties set by the {@link EventProcessorSettings}.
 *
 * @author Simon Zambrovski
 * @author Steven van Beelen
 */
class EventProcessorPropertiesIT {

    @SpringBootTest(classes = MyContext.class)
    @Nested
    class LoadDefaultProperties {

        @Autowired(required = false)
        private EventProcessorSettings.MapWrapper eventProcessorProperties;

        @Test
        void constructedMapWrapperOnlyContainsDefaultProperties() {
            assertThat(eventProcessorProperties).isNotNull();
            EventProcessorSettings defaultSettings = eventProcessorProperties.settings()
                                                                             .get(EventProcessorSettings.DEFAULT);
            assertThat(defaultSettings).isNotNull();
            assertThat(defaultSettings.processorMode()).isEqualTo(EventProcessorSettings.ProcessorMode.POOLED);
        }
    }

    @SpringBootTest(
            classes = MyContext.class,
            properties = {
                    "axon.eventhandling.processors[this.is.a.package].mode=subscribing"
            }
    )
    @Nested
    class LoadPropertiesForPackageBasedBinding {

        @Autowired(required = false)
        private EventProcessorSettings.MapWrapper eventProcessorProperties;

        @Test
        void constructedMapWrapperContainsDefaultAndCustomProperties() {
            assertThat(eventProcessorProperties).isNotNull();
            assertThat(eventProcessorProperties.settings()).containsKeys("this.is.a.package");
            assertThat(eventProcessorProperties.settings().get("this.is.a.package").processorMode())
                    .isEqualTo(EventProcessorSettings.ProcessorMode.SUBSCRIBING);
        }
    }

    @SpringBootTest(
            classes = MyContext.class,
            properties = {
                    "axon.eventhandling.processors.foo.batch-size=1",
                    "axon.eventhandling.processors.foo.initial-segment-count=2",
                    "axon.eventhandling.processors.foo.mode=pooled",
                    "axon.eventhandling.processors.foo.sequencing-policy=sp",
                    "axon.eventhandling.processors.foo.source=source",
                    "axon.eventhandling.processors.foo.thread-count=3",
                    "axon.eventhandling.processors.foo.token-claim-interval=4",
                    "axon.eventhandling.processors.foo.token-claim-interval-time-unit=SECONDS",
                    "axon.eventhandling.processors.bar.initial-segment-count=77",
            }
    )
    @Nested
    class ConfigureProcessorsWithProperties {

        @Autowired(required = false)
        private EventProcessorSettings.MapWrapper eventProcessorProperties;

        @Test
        void processorsConfigured() {
            assertThat(eventProcessorProperties).isNotNull();
            assertThat(eventProcessorProperties.settings()).containsKeys("foo", "bar");
            EventProcessorSettings fooSettings = eventProcessorProperties.settings().get("foo");
            assertThat(fooSettings).isInstanceOf(EventProcessorProperties.ProcessorSettings.class);
            EventProcessorProperties.ProcessorSettings castedFooSettings =
                    (EventProcessorProperties.ProcessorSettings) fooSettings;
            assertThat(castedFooSettings).isNotNull();
            assertThat(castedFooSettings.batchSize()).isEqualTo(1);
            assertThat(castedFooSettings.initialSegmentCount()).isEqualTo(2);
            assertThat(castedFooSettings.processorMode()).isEqualTo(EventProcessorSettings.ProcessorMode.POOLED);
            assertThat(castedFooSettings.sequencingPolicy()).isEqualTo("sp");
            assertThat(castedFooSettings.source()).isEqualTo("source");
            assertThat(castedFooSettings.threadCount()).isEqualTo(3);
            assertThat(castedFooSettings.getTokenClaimInterval()).isEqualTo(4);
            assertThat(castedFooSettings.getTokenClaimIntervalTimeUnit()).isEqualTo(TimeUnit.SECONDS);

            EventProcessorProperties.ProcessorSettings defaultSettings = new EventProcessorProperties.ProcessorSettings();
            EventProcessorSettings barSettings = eventProcessorProperties.settings().get("bar");
            assertThat(barSettings).isNotNull();
            assertThat(barSettings).isInstanceOf(EventProcessorProperties.ProcessorSettings.class);
            EventProcessorProperties.ProcessorSettings castedBarSettings =
                    (EventProcessorProperties.ProcessorSettings) barSettings;
            assertThat(castedBarSettings.batchSize()).isEqualTo(defaultSettings.batchSize());
            assertThat(castedBarSettings.initialSegmentCount()).isEqualTo(77); // the only modified value
            assertThat(castedBarSettings.getMode()).isEqualTo(defaultSettings.getMode());
            assertThat(castedBarSettings.sequencingPolicy()).isEqualTo(defaultSettings.sequencingPolicy());
            assertThat(castedBarSettings.source()).isEqualTo(defaultSettings.source());
            assertThat(castedBarSettings.threadCount()).isEqualTo(defaultSettings.threadCount());
            assertThat(castedBarSettings.getTokenClaimInterval()).isEqualTo(defaultSettings.getTokenClaimInterval());
            assertThat(castedBarSettings.getTokenClaimIntervalTimeUnit()).isEqualTo(defaultSettings.getTokenClaimIntervalTimeUnit());
        }
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class MyContext {

    }
}