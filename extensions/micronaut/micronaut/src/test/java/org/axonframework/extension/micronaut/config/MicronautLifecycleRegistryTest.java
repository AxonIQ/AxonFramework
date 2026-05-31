package org.axonframework.extension.micronaut.config;

import io.micronaut.context.BeanContext;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.axonframework.common.configuration.Configuration;
import org.junit.jupiter.api.*;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MicronautLifecycleShutdownHandler}.
 *
 * @author Daniel Karapishchenko
 */
@MicronautTest(startApplication = false)
class MicronautLifecycleRegistryTest {

    @Inject
    MicronautLifecycleRegistry micronautLifecycleRegistry;
    @Inject
    BeanContext beanContext;


    @MockBean(Configuration.class)
    Configuration configuration() {
        return mock();
    }

    @Test
    void shouldCallStartHandlersInPhaseOrder() {
        ArrayList<Integer> invocations = new ArrayList<>();
        micronautLifecycleRegistry.onStart(2, () -> invocations.add(2));
        micronautLifecycleRegistry.onStart(1, () -> invocations.add(1));
        micronautLifecycleRegistry.onStart(3, () -> invocations.add(3));
        beanContext.start();
        assertEquals(3, invocations.size());
        assertEquals(invocations.stream().sorted().toList(), invocations);
    }
}
