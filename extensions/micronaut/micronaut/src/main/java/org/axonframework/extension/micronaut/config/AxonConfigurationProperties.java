package org.axonframework.extension.micronaut.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

import static io.micronaut.core.util.StringUtils.TRUE;


/**
 * Configuration properties for altering Axons behavior
 *
 * @param enhancerScanning sets whether classpath scanning of
 *                         {@link org.axonframework.common.configuration.ConfigurationEnhancer} is enabled
 */
@ConfigurationProperties(AxonConfigurationProperties.PREFIX)
public record AxonConfigurationProperties(
        @Bindable(defaultValue = TRUE)
        boolean enhancerScanning) {

    public static final String PREFIX = "axon";
}
