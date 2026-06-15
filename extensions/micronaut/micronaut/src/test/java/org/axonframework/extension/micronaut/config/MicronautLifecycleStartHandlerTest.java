package org.axonframework.extension.micronaut.config;

import com.google.common.primitives.Ints;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static io.micronaut.core.util.StringUtils.TRUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@MicronautTest(rebuildContext = true)
class MicronautLifecycleStartHandlerTest {

    @Property(name = TestLifecycleStartHandlerFactory.ENABLED_PROPERTY, value = TRUE)
    @Property(name = "expected-invocations", value = "3,2,1,9,5")
    @Test
    void tests(
            TestLifecycleStartHandlerFactory lifecycleStartHandlerFactory,
            @Property(name = "expected-invocations") int[] expectedInvocations
    ) {
        assertEquals(expectedInvocations.length,
                     lifecycleStartHandlerFactory.invocations.size());
        assertArrayEquals(Arrays.stream(expectedInvocations).sorted().toArray(),
                          Ints.toArray(lifecycleStartHandlerFactory.invocations));
    }

    @Context
    @Requires(property = TestLifecycleStartHandlerFactory.ENABLED_PROPERTY, value = TRUE)
    static class TestLifecycleStartHandlerFactory {

        private static final String ENABLED_PROPERTY = "spec.MicronautLifecycleStartHandlerTest.TestLifecycleStartHandlerFactory";

        public ArrayList<Integer> invocations = new ArrayList<>();

        public TestLifecycleStartHandlerFactory(
                BeanContext beanContext,
                @Property(name = "expected-invocations") int[] expectedInvocations
        ) {
            for (var i : expectedInvocations) {

                beanContext.registerSingleton(
                        MicronautLifecycleStartHandler.class,
                        new MicronautLifecycleStartHandler(mock(), i, (c) -> {
                            invocations.add(i);
                            return CompletableFuture.completedFuture(null);
                        })
                );
            }
        }
    }
}