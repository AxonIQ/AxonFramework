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

package org.axonframework.springboot.integration;

import org.axonframework.conversion.upcasting.event.EventUpcaster;
import org.axonframework.conversion.upcasting.event.IntermediateEventRepresentation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;

/**
 * Test class validating that usage of the {@link org.springframework.core.annotation.Order} on
 * {@link org.axonframework.conversion.upcasting.event.EventUpcaster} beans is taken into account when constructing
 * an {@link org.axonframework.conversion.upcasting.event.EventUpcasterChain}.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #3597")
class UpcasterOrderingTest {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner().withUserConfiguration(
                                                                       DefaultContext.class, OtherContext.class
                                                               );
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    void upcastersAreRegisteredInOrder() {
        //noinspection unchecked
        Stream<IntermediateEventRepresentation> mockStream = mock(Stream.class);
        testApplicationContext.run(context -> {
//            EventUpcasterChain testSubject = context.getBean(LegacyConfiguration.class).upcasterChain();

//            testSubject.upcast(mockStream);

            // Since InOrder only works with mocks, we verify the invoked methods on the test stream.
            InOrder upcasterOrder = inOrder(mockStream);
            upcasterOrder.verify(mockStream).sorted(); // Invoked in FirstUpcaster
            upcasterOrder.verify(mockStream).filter(any()); // Invoked in SecondUpcaster
            upcasterOrder.verify(mockStream).distinct(); // Invoked in ThirdUpcaster
            upcasterOrder.verify(mockStream).map(any()); // Invoked in UnorderedUpcaster
        });
    }

    @org.springframework.context.annotation.Configuration
    @EnableAutoConfiguration
    static class DefaultContext {

        // Normally constructed through Spring Boot autoconfig.
        // As this is the plain Spring module, we need to construct it ourselves.
//        @Bean
//        public EventProcessingModule eventProcessingModule() {
//            return new EventProcessingModule();
//        }

        @SuppressWarnings({"unused", "RedundantStreamOptionalCall", "ResultOfMethodCallIgnored", "DataFlowIssue"})
        @Component
        public static class UnorderedUpcaster implements EventUpcaster {

            @Override
            public @NonNull Stream<IntermediateEventRepresentation> upcast(
                    @NonNull Stream<IntermediateEventRepresentation> intermediateRepresentations
            ) {
                intermediateRepresentations.map(ier -> ier);
                return intermediateRepresentations;
            }
        }

        @SuppressWarnings({"unused", "ResultOfMethodCallIgnored", "DataFlowIssue"})
        @Order(0)
        @Component
        public static class FirstUpcaster implements EventUpcaster {

            @Override
            public @NonNull Stream<IntermediateEventRepresentation> upcast(
                    @NonNull Stream<IntermediateEventRepresentation> intermediateRepresentations
            ) {
                intermediateRepresentations.sorted();
                return intermediateRepresentations;
            }
        }
    }

    @org.springframework.context.annotation.Configuration
    public static class OtherContext {

        @SuppressWarnings({"unused", "RedundantStreamOptionalCall", "ResultOfMethodCallIgnored", "DataFlowIssue"})
        @Order(1)
        @Component
        public static class SecondUpcaster implements EventUpcaster {

            @Override
            public @NonNull Stream<IntermediateEventRepresentation> upcast(
                    @NonNull Stream<IntermediateEventRepresentation> intermediateRepresentations
            ) {
                intermediateRepresentations.filter(ier -> true);
                return intermediateRepresentations;
            }
        }

        @SuppressWarnings("ClassEscapesDefinedScope")
        @Bean
        @Order(2)
        public ThirdUpcaster someSecondUpcaster() {
            return new ThirdUpcaster();
        }
    }

    @SuppressWarnings({"ResultOfMethodCallIgnored", "DataFlowIssue"})
    private static class ThirdUpcaster implements EventUpcaster {

        @Override
        public @NonNull Stream<IntermediateEventRepresentation> upcast(
                @NonNull Stream<IntermediateEventRepresentation> intermediateRepresentations
        ) {
            intermediateRepresentations.distinct();
            return intermediateRepresentations;
        }
    }
}
