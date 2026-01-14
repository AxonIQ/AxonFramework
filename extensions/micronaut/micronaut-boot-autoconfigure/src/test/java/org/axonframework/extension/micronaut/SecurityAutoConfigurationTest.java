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

import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.extension.micronaut.authorization.SecuredMessageHandlerDefinition;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("TODO #3496")
class SecurityAutoConfigurationTest {

    @Test
    void handlerEnhancerDefinitionIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("securedMessageHandlerDefinition"));
                    assertTrue(context.getBeansOfType(HandlerEnhancerDefinition.class).containsKey("securedMessageHandlerDefinition"));
                    assertFalse(context.getBeansOfType(SecuredMessageHandlerDefinition.class).isEmpty());
                });
    }

    @Test
    void handlerEnhancerDefinitionIsNotRegisteredWithoutSecuredAnnotation() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.security.access.annotation.Secured"))
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertFalse(context.containsBean("securedMessageHandlerDefinition"));
                });
    }

    @EnableAutoConfiguration
    @ContextConfiguration
    public static class Context {

    }

}
