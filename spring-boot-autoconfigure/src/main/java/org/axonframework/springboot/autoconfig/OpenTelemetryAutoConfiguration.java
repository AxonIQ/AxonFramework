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

package org.axonframework.springboot.autoconfig;

import io.opentelemetry.api.OpenTelemetry;
import org.axonframework.tracing.SpanFactory;
import org.axonframework.tracing.opentelemetry.OpenTelemetrySpanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Automatically configured the {@link OpenTelemetrySpanFactory} as the method of providing tracing in Axon Framework.
 * For this to take effect, the {@code axon-tracing-opentelemetry} dependency must be on the classpath.
 * <p>
 * When a Spring-managed {@link OpenTelemetry} bean is present (e.g. Spring Boot 3's
 * {@code OpenTelemetryAutoConfiguration} together with {@code micrometer-tracing-bridge-otel}), its
 * {@link io.opentelemetry.api.trace.Tracer Tracer} and
 * {@link io.opentelemetry.context.propagation.TextMapPropagator TextMapPropagator} are passed into the
 * {@link OpenTelemetrySpanFactory.Builder} explicitly. Without this, the builder falls back to
 * {@link io.opentelemetry.api.GlobalOpenTelemetry}, which Spring Boot 3 does not register — leaving the
 * factory with a no-op propagator that silently drops W3C trace context across asynchronous Axon
 * boundaries (event processors, deadlines, distributed command/query buses).
 * <p>
 * If no {@link OpenTelemetry} bean is available the builder defaults are kept, preserving compatibility
 * with the OpenTelemetry Java agent and with manual SDK installations that call
 * {@link io.opentelemetry.sdk.OpenTelemetrySdkBuilder#buildAndRegisterGlobal()}.
 *
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
@AutoConfiguration
@AutoConfigureBefore({AxonTracingAutoConfiguration.class, AxonAutoConfiguration.class})
@ConditionalOnClass(name = "org.axonframework.tracing.opentelemetry.OpenTelemetrySpanFactory")
public class OpenTelemetryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SpanFactory.class)
    public SpanFactory spanFactory(ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
        if (openTelemetry == null) {
            return OpenTelemetrySpanFactory.builder().build();
        }
        return OpenTelemetrySpanFactory.builder()
                                       .tracer(openTelemetry.getTracer("AxonFramework-OpenTelemetry"))
                                       .contextPropagators(openTelemetry.getPropagators().getTextMapPropagator())
                                       .build();
    }
}
