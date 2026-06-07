package org.axonframework.extension.micronaut.config;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.junit.jupiter.api.*;

import static io.micronaut.core.util.StringUtils.TRUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@MicronautTest(rebuildContext = true)
class MicronautLifecycleRegistryTest {

    @Inject
    BeanContext beanContext;
    @Inject
    LifecycleRegistry lifecycleRegistry;

    @Property(name = CustomLifecycleRegistryContext.ENABLED_PROPERTY, value = TRUE)
    @Test
    void shouldBeOverridable() {
        assertTrue(lifecycleRegistry instanceof StubLifecycleRegistry);
    }

    @Test
    void onStartRegistersMicronautLifecycleStartHandlerWithBeanContext() {
        assertFalse(beanContext.containsBean(MicronautLifecycleStartHandler.class));
        lifecycleRegistry.onStart(1, mock(LifecycleHandler.class));
        lifecycleRegistry.onStart(2, mock(LifecycleHandler.class));
        assertEquals(2, beanContext.getBeansOfType(MicronautLifecycleStartHandler.class).size());
    }


    @Test
    void onShutdownRegistersMicronautLifecycleShutdownHandlerWithBeanContext() {
        assertFalse(beanContext.containsBean(MicronautLifecycleShutdownHandler.class));
        lifecycleRegistry.onShutdown(1, mock(LifecycleHandler.class));
        lifecycleRegistry.onShutdown(2, mock(LifecycleHandler.class));
        assertEquals(2, beanContext.getBeansOfType(MicronautLifecycleShutdownHandler.class).size());
    }


    @Test
    void expectToBeAvailableInContext() {
        assertTrue(beanContext.containsBean(LifecycleRegistry.class));
    }


    @Factory
    @Requires(property = CustomLifecycleRegistryContext.ENABLED_PROPERTY, value = TRUE)
    public static class CustomLifecycleRegistryContext {

        private static final String ENABLED_PROPERTY = "spec.MicronuatLifecycleRegistryTest.CustomLifecycleRegistryContext";

        @Singleton
        LifecycleRegistry stubLifecycleRegistry() {
            return new StubLifecycleRegistry();
        }
    }

    @MockBean(Configuration.class)
    Configuration configuration() {
        return mock();
    }
}

