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
package migration.paths.tracing;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class MultipleExportersConfiguration {

    // tag::multiple-exporters-configuration-api[]
    public AxonConfiguration configureTracing() {
        OtlpGrpcSpanExporter otlpExporter = OtlpGrpcSpanExporter.builder()
                                                               .setEndpoint("http://localhost:4317")
                                                               .build();
        SpanExporter loggingExporter = OtlpJsonLoggingSpanExporter.create();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                                                            .addSpanProcessor(
                                                                    BatchSpanProcessor.builder(otlpExporter).build()
                                                            )
                                                            .addSpanProcessor(
                                                                    SimpleSpanProcessor.create(loggingExporter)
                                                            )
                                                            .build();

        ContextPropagators contextPropagators =
                ContextPropagators.create(W3CTraceContextPropagator.getInstance());
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                                                         .setTracerProvider(tracerProvider)
                                                         .setPropagators(contextPropagators)
                                                         .build();

        io.opentelemetry.api.trace.Tracer openTelemetryTracer =
                openTelemetry.getTracer("AxoniqFramework");
        Tracer tracer = new OtelTracer(
                openTelemetryTracer,
                new OtelCurrentTraceContext(),
                event -> {
                }
        );
        Propagator propagator = new OtelPropagator(contextPropagators, openTelemetryTracer);

        return MessagingConfigurer.create()
                                  .componentRegistry(registry -> registry
                                          .registerComponent(Tracer.class, config -> tracer)
                                          .registerComponent(Propagator.class, config -> propagator))
                                  .lifecycleRegistry(registry -> registry.onShutdown(tracerProvider::close))
                                  .start();
    }
    // end::multiple-exporters-configuration-api[]
}
