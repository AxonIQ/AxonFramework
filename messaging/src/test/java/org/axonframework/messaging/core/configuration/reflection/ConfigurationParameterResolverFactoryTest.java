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

package org.axonframework.messaging.core.configuration.reflection;

import org.axonframework.common.configuration.AmbiguousComponentMatchException;
import org.axonframework.common.configuration.Component;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;

import static org.axonframework.messaging.commandhandling.CommandBusTestUtils.aCommandBus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ConfigurationParameterResolverFactory}.
 *
 * @author Allard Buijze
 */
class ConfigurationParameterResolverFactoryTest {

    private Method method;
    private Parameter[] parameters;
    private Configuration configuration;
    private CommandBus commandBus;

    private ConfigurationParameterResolverFactory testSubject;

    @BeforeEach
    void setUp() throws Exception {
        method = getClass().getMethod("donorMethod", String.class, CommandBus.class);
        parameters = method.getParameters();
        configuration = mock(Configuration.class);
        commandBus = aCommandBus();
        when(configuration.getOptionalComponent(CommandBus.class)).thenReturn(Optional.of(commandBus));

        testSubject = new ConfigurationParameterResolverFactory(configuration);
    }

    @Test
    void returnsNullOnUnavailableParameter() {
        assertNull(testSubject.createInstance(method, parameters, 0));

        verify(configuration).getOptionalComponent(String.class);
    }

    @Test
    void returnsNullWhenSeveralComponentsMatchTheParameter() {
        // given - another factory, like Spring's, can tell the matching components apart by qualifier
        when(configuration.getOptionalComponent(String.class))
                .thenThrow(new AmbiguousComponentMatchException(new Component.Identifier<>(String.class, null)));

        // when / then
        assertNull(testSubject.createInstance(method, parameters, 0));
    }

    @Test
    void configurationContainsRequestedParameter() {
        ParameterResolver<?> actual = testSubject.createInstance(method, parameters, 1);
        Message testMessage = new GenericMessage(new MessageType("message"), "test");

        assertNotNull(actual);
        assertSame(commandBus, actual.resolveParameterValue(StubProcessingContext.forMessage(testMessage)).join());

        verify(configuration).getOptionalComponent(CommandBus.class);
    }

    @SuppressWarnings({"unused", "WeakerAccess"})
    public void donorMethod(String type, CommandBus commandBus) {

    }
}
