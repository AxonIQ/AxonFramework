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

package org.axonframework.extension.micronaut.config;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.axonframework.common.configuration.Module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

//class MicronautEventSourcedEntityConfigurerTest {
//
//    private final ComponentRegistry registry = mock();
//
//    @Test
//    void detectsAnnotatedEntitiesAndRegisterModules() {
//        var configurer = new MicronautEventSourcedEntityConfigurer<>(MyEntity1.class, MyId1.class);
//        configurer.enhance(registry);
//
//        var moduleCaptor = ArgumentCaptor.forClass(Module.class);
//        verify(registry).registerModule(moduleCaptor.capture());
//        var module = moduleCaptor.getValue();
//        assertThat(module).isInstanceOf(EventSourcedEntityModule.class);
//        assertThat(module.name()).isEqualTo("AnnotatedEventSourcedEntityModule<"
//        + MyId1.class.getName() + ", " + MyEntity1.class.getName() +  ">");
//        verifyNoMoreInteractions(registry);
//
//    }
//
//    @Test
//    void skipsEntitiesIfNotAnnotated() {
//        var configurer = new MicronautEventSourcedEntityConfigurer<>(MyEntity2.class, MyId1.class);
//        assertThrows(IllegalArgumentException.class, () -> configurer.enhance(registry));
//        verifyNoMoreInteractions(registry);
//    }
//
//    @EventSourcedEntity
//    static class MyEntity1 {
//
//    }
//    static class MyEntity2 {
//
//    }
//
//    static class MyId1 {
//
//    }
//
//}