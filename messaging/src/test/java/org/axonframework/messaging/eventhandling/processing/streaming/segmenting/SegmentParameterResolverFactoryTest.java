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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.annotation.SegmentParameterResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentParameterResolverFactoryTest {

    private Method method;
    private SegmentParameterResolverFactory testSubject;

    @BeforeEach
    void setUp() throws Exception {
        method = getClass().getDeclaredMethod("method1", Object.class, Segment.class);
        testSubject = new SegmentParameterResolverFactory();
    }

    @Test
    void createsAResolverOnlyForASegmentParameter() {
        // given / when / then -- no resolver for the Object parameter, one for the Segment parameter
        assertThat(testSubject.createInstance(method, method.getParameters(), 0)).isNull();
        assertThat(testSubject.createInstance(method, method.getParameters(), 1)).isNotNull();
    }

    @Test
    void matchesRegardlessOfWhetherASegmentIsPresent() {
        // given -- a context without a Segment resource (e.g. a non-segmenting processor)
        ParameterResolver<?> resolver = testSubject.createInstance(method, method.getParameters(), 1);
        EventMessage message = new GenericEventMessage(new MessageType("event"), "test");
        ProcessingContext contextWithoutSegment = StubProcessingContext.forMessage(message);

        // when / then -- matches unconditionally so the handler is never silently skipped; absence surfaces on resolve
        assertThat(resolver.matches(contextWithoutSegment)).isTrue();
    }

    @Test
    void resolvesToNullWhenNoSegmentIsPresent() {
        // given -- a context without a Segment resource
        ParameterResolver<?> resolver = testSubject.createInstance(method, method.getParameters(), 1);
        EventMessage message = new GenericEventMessage(new MessageType("event"), "test");
        ProcessingContext contextWithoutSegment = StubProcessingContext.forMessage(message);

        // when / then -- a null Segment is harmless to receive, so resolution yields null rather than failing
        assertThat(resolver.resolveParameterValue(contextWithoutSegment).join()).isNull();
    }

    @Test
    void resolvesTheSegmentFromTheContext() {
        // given
        ParameterResolver<?> resolver = testSubject.createInstance(method, method.getParameters(), 1);
        Segment segment = Segment.ROOT_SEGMENT;
        EventMessage message = new GenericEventMessage(new MessageType("event"), "test");
        ProcessingContext contextWithSegment =
                StubProcessingContext.forMessage(message).withResource(Segment.RESOURCE_KEY, segment);

        // when / then
        assertThat(resolver.matches(contextWithSegment)).isTrue();
        assertThat(resolver.resolveParameterValue(contextWithSegment).join()).isSameAs(segment);
    }

    @Test
    void isDiscoveredViaServiceLoaderOnTheClasspath() {
        // given -- the factory resolved purely through the META-INF/services registration (catches a typo there)
        ParameterResolverFactory classpathFactory = ClasspathParameterResolverFactory.forClass(getClass());

        // when / then -- the classpath factory creates a resolver for the Segment parameter
        assertThat(classpathFactory.createInstance(method, method.getParameters(), 1)).isNotNull();
    }

    @SuppressWarnings("unused")
    private void method1(Object param1, Segment segment) {
        // signature under test: the Segment parameter at index 1
    }
}
