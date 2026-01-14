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

package org.axonframework.extension.micronaut;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EventProcessorPropertiesTest {

    @SpringBootTest(classes = MyContext.class)
    @Nested
    class LoadDefaultProperties {

        @Autowired(required = false)
        private EventProcessorProperties eventProcessorProperties;

        @Test
        void noProcessors() {
            assertThat(eventProcessorProperties).isNotNull();
            assertThat(eventProcessorProperties.getProcessors()).isEmpty();
        }
    }

    @SpringBootTest(classes = MyContext.class, properties = {
            "axon.eventhandling.processors[this.is.a.package].mode=subscribing"
    })
    @Nested
    class LoadPropertiesForPackageBasedBinding {

        @Autowired(required = false)
        private EventProcessorProperties eventProcessorProperties;

        @Test
        void noProcessors() {
            assertThat(eventProcessorProperties).isNotNull();
            assertThat(eventProcessorProperties.getProcessors()).containsKeys("this.is.a.package");
            assertThat(eventProcessorProperties.getProcessors().get("this.is.a.package").getMode()).isEqualTo(
                    EventProcessorProperties.Mode.SUBSCRIBING
            );
        }
    }


    @SpringBootTest(classes = MyContext.class, properties = {
            "axon.eventhandling.processors.foo.batch-size=1",
            "axon.eventhandling.processors.foo.initial-segment-count=2",
            "axon.eventhandling.processors.foo.mode=subscribing",
            "axon.eventhandling.processors.foo.sequencing-policy=sp",
            "axon.eventhandling.processors.foo.source=source",
            "axon.eventhandling.processors.foo.thread-count=3",
            "axon.eventhandling.processors.foo.token-claim-interval=4",
            "axon.eventhandling.processors.foo.token-claim-interval-time-unit=SECONDS",
            "axon.eventhandling.processors.bar.initial-segment-count=77",
    })
    @Nested
    class ConfigureProcessorsWithProperties {

        @Autowired(required = false)
        private EventProcessorProperties eventProcessorProperties;

        @Test
        void processorsConfigured() {
            assertThat(eventProcessorProperties).isNotNull();
            assertThat(eventProcessorProperties.getProcessors()).containsKeys("foo", "bar");
            var foo = eventProcessorProperties.getProcessors().get("foo");
            assertThat(foo).isNotNull();
            assertThat(foo.batchSize()).isEqualTo(1);
            assertThat(foo.initialSegmentCount()).isEqualTo(2);
            assertThat(foo.getMode()).isEqualTo(EventProcessorProperties.Mode.SUBSCRIBING);
            assertThat(foo.sequencingPolicy()).isEqualTo("sp");
            assertThat(foo.source()).isEqualTo("source");
            assertThat(foo.threadCount()).isEqualTo(3);
            assertThat(foo.getTokenClaimInterval()).isEqualTo(4);
            assertThat(foo.getTokenClaimIntervalTimeUnit()).isEqualTo(TimeUnit.SECONDS);

            var defaultSettings = new EventProcessorProperties.ProcessorSettings();
            var bar = eventProcessorProperties.getProcessors().get("bar");
            assertThat(bar).isNotNull();
            assertThat(bar.batchSize()).isEqualTo(defaultSettings.batchSize());

            assertThat(bar.initialSegmentCount()).isEqualTo(77); // the only modified value

            assertThat(bar.getMode()).isEqualTo(defaultSettings.getMode());
            assertThat(bar.sequencingPolicy()).isEqualTo(defaultSettings.sequencingPolicy());
            assertThat(bar.source()).isEqualTo(defaultSettings.source());
            assertThat(bar.threadCount()).isEqualTo(defaultSettings.threadCount());
            assertThat(bar.getTokenClaimInterval()).isEqualTo(defaultSettings.getTokenClaimInterval());
            assertThat(bar.getTokenClaimIntervalTimeUnit()).isEqualTo(defaultSettings.getTokenClaimIntervalTimeUnit());

        }
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class MyContext {

    }
}