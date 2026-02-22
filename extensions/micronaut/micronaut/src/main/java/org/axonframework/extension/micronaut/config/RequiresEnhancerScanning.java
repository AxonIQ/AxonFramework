package org.axonframework.extension.micronaut.config;

import io.micronaut.context.annotation.Requires;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static io.micronaut.core.util.StringUtils.TRUE;
import static org.axonframework.extension.micronaut.config.MicronautComponentRegistry.AXON_ENHANCER_SCANNING_PROPERTY_NAME;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Requires(property = AXON_ENHANCER_SCANNING_PROPERTY_NAME, defaultValue = TRUE)
public @interface RequiresEnhancerScanning {

}
