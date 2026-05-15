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

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.extension.tracing.opentelemetry.OpenTelemetrySpanFactory;
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

@Disabled("TODO #3594")
class AxonAutoConfigurationWithOpenTelemetryTest {

    @Test
    void spanFactoryIsOpenTelemetrySpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("spanFactory"));
                    assertNotNull(context.getBean(SpanFactory.class));
                    assertEquals(OpenTelemetrySpanFactory.class, context.getBean(SpanFactory.class).getClass());
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
}
