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
package migration.paths.tracing.springboot;

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::multiple-exporters-spring[]
@Configuration(proxyBeanMethods = false)
public class MultipleExportersConfiguration {

    @Bean
    OtlpGrpcSpanExporter otlpSpanExporter() {
        return OtlpGrpcSpanExporter.builder()
                                   .setEndpoint("http://localhost:4317")
                                   .build();
    }

    @Bean
    SpanExporter loggingSpanExporter() {
        return OtlpJsonLoggingSpanExporter.create();
    }
}
// end::multiple-exporters-spring[]
