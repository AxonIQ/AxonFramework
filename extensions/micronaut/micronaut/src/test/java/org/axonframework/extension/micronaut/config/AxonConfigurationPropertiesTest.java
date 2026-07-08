package org.axonframework.extension.micronaut.config;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static io.micronaut.core.util.StringUtils.FALSE;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(rebuildContext = true)
class AxonConfigurationPropertiesTest {

    @Inject
    AxonConfigurationProperties configurationProperties;

    @Property(name = AxonConfigurationProperties.PREFIX + ".enhancer-scanning", value = FALSE)
    @Test
    void canDisableEnhancerScanning() {
        assertFalse(configurationProperties.enhancerScanning());
    }

    @Test
    void enhancerScanningDefaultsToTrue() {
        assertTrue(configurationProperties.enhancerScanning());
    }
}