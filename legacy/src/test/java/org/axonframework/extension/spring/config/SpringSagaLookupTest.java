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

import org.axonframework.spring.stereotype.Saga;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Test class validating the {@link SpringSagaLookup}, the post processor turning every
 * {@link Saga @Saga} bean into a {@link SpringSagaConfigurer} bean definition.
 *
 * @author Mateusz Nowak
 */
class SpringSagaLookupTest {

    @Nested
    class RegistrarRegistration {

        @Test
        void registersAConfigurerPerSagaBean() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            sagaBean(beanFactory, "fooSaga", FooSaga.class);
            sagaBean(beanFactory, "barSaga", BarSaga.class);

            // when
            new SpringSagaLookup().postProcessBeanFactory(beanFactory);

            // then
            assertThat(sagaTypeOf(beanFactory, "fooSaga$$Registrar")).isEqualTo(FooSaga.class);
            assertThat(sagaTypeOf(beanFactory, "barSaga$$Registrar")).isEqualTo(BarSaga.class);
            assertThat(sagaBeanNameOf(beanFactory, "fooSaga$$Registrar")).isEqualTo("fooSaga");
        }

        @Test
        void propagatesTheSagaStoreAttribute() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            sagaBean(beanFactory, "storedSaga", StoredSaga.class);

            // when
            new SpringSagaLookup().postProcessBeanFactory(beanFactory);

            // then
            BeanDefinition registrar = beanFactory.getBeanDefinition("storedSaga$$Registrar");
            assertThat(registrar.getPropertyValues().get("sagaStore")).isEqualTo("myStore");
        }

        @Test
        void leavesTheSagaStorePropertyUnsetForAnEmptyAttribute() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            sagaBean(beanFactory, "fooSaga", FooSaga.class);

            // when
            new SpringSagaLookup().postProcessBeanFactory(beanFactory);

            // then
            BeanDefinition registrar = beanFactory.getBeanDefinition("fooSaga$$Registrar");
            assertThat(registrar.getPropertyValues().contains("sagaStore")).isFalse();
        }
    }

    @Nested
    class AxonFramework4Quirks {

        /**
         * An existing registrar aborts the entire loop instead of skipping the one Saga it belongs to, so every Saga
         * discovered after it silently loses its processor. This is a literal port of the Axon Framework 4 lookup:
         * suppressing a single Saga by declaring its registrar is a documented escape hatch, and changing what it does
         * to the remaining Sagas would change behaviour for applications relying on it. Fixing it belongs in Axon
         * Framework 4 first.
         */
        @Test
        void anExistingRegistrarSkipsEveryRemainingSaga() {
            // given
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            sagaBean(beanFactory, "fooSaga", FooSaga.class);
            sagaBean(beanFactory, "barSaga", BarSaga.class);
            beanFactory.registerBeanDefinition(
                    "fooSaga$$Registrar",
                    BeanDefinitionBuilder.genericBeanDefinition(SpringSagaConfigurer.class)
                                         .addConstructorArgValue(FooSaga.class)
                                         .getBeanDefinition()
            );

            // when
            new SpringSagaLookup().postProcessBeanFactory(beanFactory);

            // then
            assertThat(beanFactory.containsBeanDefinition("barSaga$$Registrar")).isFalse();
        }
    }

    @Nested
    class UnsupportedBeanFactory {

        /**
         * A {@link ConfigurableListableBeanFactory} that is not a bean definition registry cannot be hand-rolled
         * without implementing the whole interface, so this single case uses a mock to assert the lookup returns
         * before touching it.
         */
        @Test
        void skipsABeanFactoryThatIsNotABeanDefinitionRegistry() {
            // given
            ConfigurableListableBeanFactory beanFactory = mock();

            // when / then
            assertThatNoException().isThrownBy(() -> new SpringSagaLookup().postProcessBeanFactory(beanFactory));
            verify(beanFactory, never()).getBeanNamesForAnnotation(any());
        }
    }

    private static void sagaBean(DefaultListableBeanFactory beanFactory, String beanName, Class<?> sagaType) {
        beanFactory.registerBeanDefinition(beanName,
                                           BeanDefinitionBuilder.genericBeanDefinition(sagaType)
                                                                .setScope(BeanDefinition.SCOPE_PROTOTYPE)
                                                                .getBeanDefinition());
    }

    private static Class<?> sagaTypeOf(DefaultListableBeanFactory beanFactory, String registrarBeanName) {
        BeanDefinition registrar = beanFactory.getBeanDefinition(registrarBeanName);
        assertThat(registrar.getBeanClassName()).isEqualTo(SpringSagaConfigurer.class.getName());
        return (Class<?>) registrar.getConstructorArgumentValues()
                                   .getArgumentValue(1, Class.class)
                                   .getValue();
    }

    private static String sagaBeanNameOf(DefaultListableBeanFactory beanFactory, String registrarBeanName) {
        BeanDefinition registrar = beanFactory.getBeanDefinition(registrarBeanName);
        return (String) registrar.getConstructorArgumentValues()
                                 .getArgumentValue(0, String.class)
                                 .getValue();
    }

    @Saga
    static class FooSaga {

    }

    @Saga
    static class BarSaga {

    }

    @Saga(sagaStore = "myStore")
    static class StoredSaga {

    }
}
