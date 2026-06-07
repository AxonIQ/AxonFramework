package org.axonframework.extension.micronaut.config;

import com.google.common.primitives.Ints;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.graceful.GracefulShutdownConfiguration;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static io.micronaut.core.util.StringUtils.FALSE;
import static io.micronaut.core.util.StringUtils.TRUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Property(name = "expected-invocations", value = "3,2,1,9,5")
@Property(name = MicronautLifecycleShutdownHandlerTest.TestLifecycleShutdownHandlerFactory.ENABLED_PROPERTY, value = TRUE)
@Property(name = GracefulShutdownConfiguration.ENABLED, value = TRUE)
@MicronautTest(rebuildContext = true)
class MicronautLifecycleShutdownHandlerTest {

    @Inject
    private BeanContext beanContext;

    @Inject
    TestLifecycleShutdownHandlerFactory lifecycleStartHandlerFactory;

    @Property(name = "expected-invocations")
    private int[] expectedInvocations;

    /**
     * We are stopping the BeanContext for our tests to simulate application shutdown, new tests with throw an error if
     * the beanContext is stopped, therefore we are ensuring the context is up after each test
     */
    @AfterEach
    void afterEach() {
        beanContext.start();
    }

    @Test
    void shouldCallShutdownHandlersInPhaseOrder() {
        beanContext.stop();
        assertEquals(expectedInvocations.length,
                     lifecycleStartHandlerFactory.invocations.size());
        assertArrayEquals(Arrays.stream(expectedInvocations).sorted().toArray(),
                          Ints.toArray(lifecycleStartHandlerFactory.invocations));
    }

    @Test
    void shouldInvokeShutdownHandlersAfterContextStop() {
        assertEquals(0, lifecycleStartHandlerFactory.invocations.size());
        beanContext.stop();
        assertNotEquals(0, lifecycleStartHandlerFactory.invocations.size());
    }

    @Context
    @Requires(property = TestLifecycleShutdownHandlerFactory.ENABLED_PROPERTY, value = TRUE)
    public static class TestLifecycleShutdownHandlerFactory {

        protected static final String ENABLED_PROPERTY = "spec.MicronautLifecycleShutdownHandlerTest.TestLifecycleShutdownHandlerFactory.enabled";

        public ArrayList<Integer> invocations = new ArrayList<>();

        public TestLifecycleShutdownHandlerFactory(
                BeanContext beanContext,
                @Property(name = GracefulShutdownConfiguration.ENABLED,defaultValue = FALSE) boolean gracefulShutdownEnabled,
                @Property(name = "expected-invocations") int[] expectedInvocations
        ) {
            for (var i : expectedInvocations) {
                beanContext.registerSingleton(
                        MicronautLifecycleShutdownHandler.class,
                        new MicronautLifecycleShutdownHandler(mock(), gracefulShutdownEnabled, i, (c) -> {
                            invocations.add(i);
                            return CompletableFuture.completedFuture(null);
                        }),
                        Qualifiers.byName(String.valueOf(i))
                );
            }
        }
    }
}
