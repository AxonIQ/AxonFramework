/*
 * Copyright (c) 2010-2025. Axon Framework
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

package org.axonframework.extension.micronaut.autoconfig;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link ObjectMapperAutoConfiguration}.
 *
 * @author Theo Emanuelsson
 */
class ObjectMapperAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner().withUserConfiguration(TestContext.class);
    }

    @Test
    void springDataPageJacksonModuleIsRegisteredByDefault() {
        testContext.run(context -> {
            assertThat(context).hasBean("springDataPageJacksonModule");
            Module module = context.getBean("springDataPageJacksonModule", Module.class);
            assertThat(module).isNotNull();
        });
    }

    @Test
    void springDataPageJacksonModuleIsRegisteredWhenJacksonIsGeneralConverter() {
        testContext.withPropertyValues("axon.converter.general=jackson").run(context -> {
            assertThat(context).hasBean("springDataPageJacksonModule");
        });
    }

    @Test
    void springDataPageJacksonModuleIsRegisteredWhenJacksonIsMessagesConverter() {
        testContext.withPropertyValues("axon.converter.messages=jackson").run(context -> {
            assertThat(context).hasBean("springDataPageJacksonModule");
        });
    }

    @Test
    void springDataPageJacksonModuleIsRegisteredWhenJacksonIsEventsConverter() {
        testContext.withPropertyValues("axon.converter.events=jackson").run(context -> {
            assertThat(context).hasBean("springDataPageJacksonModule");
        });
    }

    @Test
    void springDataPageJacksonModuleIsNotRegisteredWhenCborIsUsed() {
        testContext.withPropertyValues(
                "axon.converter.general=cbor",
                "axon.converter.messages=cbor",
                "axon.converter.events=cbor"
        ).run(context -> {
            assertThat(context).doesNotHaveBean("springDataPageJacksonModule");
        });
    }

    @Test
    void springDataPageDeserializerWorksCorrectlyInContext() throws Exception {
        testContext.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            String json = "{"
                    + "\"content\":[\"item1\",\"item2\",\"item3\"],"
                    + "\"number\":0,"
                    + "\"size\":3,"
                    + "\"totalElements\":10"
                    + "}";

            Page<?> page = objectMapper.readValue(json, Page.class);

            assertThat(page).isInstanceOf(PageImpl.class);
            assertThat(page.getContent()).hasSize(3);
            assertThat(page.getNumber()).isEqualTo(0);
            assertThat(page.getSize()).isEqualTo(3);
            assertThat(page.getTotalElements()).isEqualTo(10);
        });
    }

    @Test
    void springDataPageSerializationRoundTripInContext() throws Exception {
        testContext.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            Page<String> originalPage = new PageImpl<>(
                    List.of("item1", "item2", "item3"),
                    PageRequest.of(0, 3),
                    10
            );

            String json = objectMapper.writeValueAsString(originalPage);
            Page<?> deserializedPage = objectMapper.readValue(json, Page.class);

            assertThat(deserializedPage.getContent()).hasSize(originalPage.getContent().size());
            assertThat(deserializedPage.getNumber()).isEqualTo(originalPage.getNumber());
            assertThat(deserializedPage.getSize()).isEqualTo(originalPage.getSize());
            assertThat(deserializedPage.getTotalElements()).isEqualTo(originalPage.getTotalElements());
        });
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
    })
    public static class TestContext {

    }
}
