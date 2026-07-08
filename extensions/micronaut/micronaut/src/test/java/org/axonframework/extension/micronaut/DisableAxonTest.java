package org.axonframework.extension.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Property;
import org.axonframework.extension.micronaut.config.AxonConfigurationProperties;
import org.axonframework.extension.micronaut.config.MicronautAxonApplication;
import org.axonframework.extension.micronaut.config.MicronautComponentRegistry;
import org.axonframework.extension.micronaut.config.MicronautLifecycleRegistry;
import org.junit.jupiter.api.*;

import java.util.Map;

import static io.micronaut.core.util.StringUtils.FALSE;
import static org.junit.jupiter.api.Assertions.*;

class DisableAxonTest {

    @AutoClose
    BeanContext applicationContext = ApplicationContext
            .builder(
                    Map.of(AxonConfigurationProperties.PREFIX + ".enabled", FALSE))
            .eagerBeansEnabled(false)
            .start();

    @Test
    void settingAxonEnabledToFalseDisablesAllAxonBeans() {
        assertFalse(applicationContext.containsBean(MicronautAxonApplication.class));
        assertFalse(applicationContext.containsBean(MicronautComponentRegistry.class));
        assertFalse(applicationContext.containsBean(MicronautLifecycleRegistry.class));
    }
}