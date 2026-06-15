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

package org.axonframework.springboot;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.tracing.SpanFactory;
import org.axonframework.tracing.opentelemetry.OpenTelemetrySpanFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;

import static org.junit.jupiter.api.Assertions.*;

class AxonAutoConfigurationWithOpenTelemetryTest {

    @Test
    void spanFactoryIsOpenTelemetrySpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("spanFactory"));
                    assertNotNull(context.getBean(SpanFactory.class));
                    assertEquals(OpenTelemetrySpanFactory.class, context.getBean(SpanFactory.class).getClass());
                });
    }

    @Test
    void spanFactoryUsesSpringManagedOpenTelemetryWhenAvailable() {
        TextMapPropagator expectedPropagator = W3CTraceContextPropagator.getInstance();
        //noinspection resource
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                                                      .setTracerProvider(SdkTracerProvider.builder().build())
                                                      .setPropagators(ContextPropagators.create(expectedPropagator))
                                                      .build();
        Tracer expectedTracer = openTelemetry.getTracer("AxonFramework-OpenTelemetry");

        new ApplicationContextRunner()
                .withUserConfiguration(ContextWithOpenTelemetry.class)
                .withBean(OpenTelemetry.class, () -> openTelemetry)
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(context -> {
                    SpanFactory spanFactory = context.getBean(SpanFactory.class);
                    assertEquals(OpenTelemetrySpanFactory.class, spanFactory.getClass());

                    // The Spring-managed OpenTelemetry's tracer must be wired into the factory,
                    // not the no-op GlobalOpenTelemetry fallback.
                    Tracer wiredTracer = ReflectionUtils.getFieldValue(
                            OpenTelemetrySpanFactory.class.getDeclaredField("tracer"),
                            spanFactory
                    );
                    assertSame(expectedTracer, wiredTracer,
                               "Factory must use the tracer from the Spring-managed OpenTelemetry bean");

                    // The Spring-managed OpenTelemetry's propagator must be wired into the factory,
                    // not the no-op GlobalOpenTelemetry fallback.
                    TextMapPropagator wired = ReflectionUtils.getFieldValue(
                            OpenTelemetrySpanFactory.class.getDeclaredField("textMapPropagator"),
                            spanFactory
                    );

                    assertSame(expectedPropagator, wired,
                               "Factory must use the propagator from the Spring-managed OpenTelemetry bean");

                    // Behavioral confirmation: propagateContext injects the W3C "traceparent" header.
                    Tracer tracer = openTelemetry.getTracer("test");
                    Span span = tracer.spanBuilder("root").startSpan();
                    EventMessage<String> propagated;
                    try (Scope ignored = span.makeCurrent()) {
                        propagated = spanFactory.propagateContext(GenericEventMessage.asEventMessage("payload"));
                    } finally {
                        span.end();
                    }
                    assertTrue(propagated.getMetaData().containsKey("traceparent"),
                               "W3C trace context must be injected into message metadata for async handlers to extract it");
                });
    }

    @Test
    void spanFactoryFallsBackToGlobalOpenTelemetryWhenNoBeanAvailable() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(context -> {
                    SpanFactory spanFactory = context.getBean(SpanFactory.class);
                    assertEquals(OpenTelemetrySpanFactory.class, spanFactory.getClass());

                    // Without a Spring-managed OpenTelemetry bean, the builder defaults apply —
                    // tracer and propagator come from GlobalOpenTelemetry (Java agent / manual SDK
                    // installations that call buildAndRegisterGlobal()).
                    TextMapPropagator wired = ReflectionUtils.getFieldValue(
                            OpenTelemetrySpanFactory.class.getDeclaredField("textMapPropagator"),
                            spanFactory
                    );
                    assertNotNull(wired, "Propagator must be set (default from GlobalOpenTelemetry)");
                });
    }

    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
    })
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @Configuration
    public static class Context {

    }

    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
    })
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @Configuration
    public static class ContextWithOpenTelemetry {

    }
}
