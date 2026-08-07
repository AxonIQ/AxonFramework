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

package org.axonframework.extension.spring.config;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SpringEventSourcedEntityLookup}.
 *
 * @author Steven van Beelen
 */
class SpringEventSourcedEntityLookupTest {

    @Test
    void registersConfigurerForRootDiscoveredByWalkingUpFromIndividuallyAnnotatedSubtypeBean() {
        // given
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(beanFactory).register(IndividuallyAnnotatedSubtype.class);

        // when
        new SpringEventSourcedEntityLookup().postProcessBeanFactory(beanFactory);

        // then
        String[] registrarBeanNames = beanFactory.getBeanNamesForType(SpringEventSourcedEntityConfigurer.class);
        assertThat(registrarBeanNames).hasSize(1);
        assertConfigurerTargets(beanFactory, registrarBeanNames[0], IndividuallyAnnotatedRoot.class, String.class);
    }

    @Test
    void registersConfigurerForAbstractRootDiscoveredByClasspathScanWithoutAnnotatedSubtypes() {
        // given
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        var lookup = new SpringEventSourcedEntityLookup(List.of(getClass().getPackageName()));

        // when
        lookup.postProcessBeanFactory(beanFactory);

        // then
        assertThat(beanFactory.containsBeanDefinition("abstractRootWithConcreteTypes$$Registrar")).isTrue();
        assertConfigurerTargets(beanFactory,
                                "abstractRootWithConcreteTypes$$Registrar",
                                AbstractRootWithConcreteTypes.class,
                                String.class);
    }

    @Test
    void doesNotScanForAbstractRootsWhenNoBasePackagesAreConfigured() {
        // given
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

        // when
        new SpringEventSourcedEntityLookup().postProcessBeanFactory(beanFactory);

        // then
        assertThat(beanFactory.containsBeanDefinition("abstractRootWithConcreteTypes$$Registrar")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static void assertConfigurerTargets(DefaultListableBeanFactory beanFactory,
                                                String registrarBeanName,
                                                Class<?> expectedEntityType,
                                                Class<?> expectedIdType) {
        var configurer = (SpringEventSourcedEntityConfigurer<Object, Object>) beanFactory.getBean(registrarBeanName);

        ComponentRegistry registry = mock();
        configurer.enhance(registry);

        var moduleCaptor = ArgumentCaptor.forClass(Module.class);
        verify(registry).registerModule(moduleCaptor.capture());
        assertThat(moduleCaptor.getValue()).isInstanceOf(EventSourcedEntityModule.class);
        assertThat(moduleCaptor.getValue().name())
                .isEqualTo("AnnotatedEventSourcedEntityModule<"
                                   + expectedIdType.getName() + ", " + expectedEntityType.getName() + ">");
    }

    @EventSourced(idType = String.class)
    abstract static class IndividuallyAnnotatedRoot {

    }

    @EventSourced(idType = String.class)
    static class IndividuallyAnnotatedSubtype extends IndividuallyAnnotatedRoot {

    }

    @EventSourced(idType = String.class, concreteTypes = ConcreteWithoutOwnAnnotation.class)
    abstract static class AbstractRootWithConcreteTypes {

    }

    static class ConcreteWithoutOwnAnnotation extends AbstractRootWithConcreteTypes {

    }
}
