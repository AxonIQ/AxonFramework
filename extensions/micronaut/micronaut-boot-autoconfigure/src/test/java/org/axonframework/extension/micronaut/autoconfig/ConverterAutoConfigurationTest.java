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

package org.axonframework.extension.micronaut.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.json.JacksonConverter;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ConverterAutoConfiguration}, {@link ObjectMapperAutoConfiguration}, and
 * {@link CBORMapperAutoConfiguration}.
 *
 * @author Steven van Beelen
 */
class ConverterAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner().withUserConfiguration(TestContext.class);
    }

    @Test
    void defaultConverterConfigurationUsesJacksonConverterThroughout() {
        testContext.run(context -> {
            Converter generalConverter = context.getBean(Converter.class);
            MessageConverter messageConverter = context.getBean(MessageConverter.class);
            EventConverter eventConverter = context.getBean(EventConverter.class);

            assertThat(generalConverter).isInstanceOf(JacksonConverter.class);
            assertThat(messageConverter).isInstanceOf(DelegatingMessageConverter.class);
            Converter wrappedMessageConverter = ((DelegatingMessageConverter) messageConverter).delegate();
            assertThat(wrappedMessageConverter).isEqualTo(generalConverter);
            assertThat(eventConverter).isInstanceOf(DelegatingEventConverter.class);
            MessageConverter wrappedEventConverter = ((DelegatingEventConverter) eventConverter).delegate();
            assertThat(wrappedEventConverter).isEqualTo(messageConverter);
        });
    }

    @Test
    void overrideAllConverterOptions() {
        testContext.withUserConfiguration(CustomContext.class).run(context -> {
            assertThat(context.getBeansOfType(Converter.class).size()).isEqualTo(3);
            assertThat(context).hasBean("customConverter");
            assertThat(context).hasSingleBean(MessageConverter.class);
            assertThat(context).hasBean("customMessageConverter");
            assertThat(context).hasSingleBean(EventConverter.class);
            assertThat(context).hasBean("customEventConverter");
        });
    }

    @Test
    void defaultObjectMapperIsUsedForExpectedConverters() {
        // Jackson is used for all Converters.
        testContext.withPropertyValues(
                "axon.axonserver.enabled=false",
                "axon.converter.general=jackson",
                "axon.converter.messages=jackson",
                "axon.converter.events=jackson"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = context.getBean(Converter.class);
            Converter messageConverter = getWrappedMessageConverter(context.getBean(MessageConverter.class));
            Converter eventConverter = getWrappedEventConverter(context.getBean(EventConverter.class));

            ObjectMapper objectMapper = context.getBean("defaultAxonObjectMapper", ObjectMapper.class);

            Object resultGeneralMapper = ReflectionUtils.getFieldValue(objectMapperField, generalConverter);
            Object resultMessageMapper = ReflectionUtils.getFieldValue(objectMapperField, messageConverter);
            Object resultEventMapper = ReflectionUtils.getFieldValue(objectMapperField, eventConverter);

            assertThat(generalConverter).isInstanceOf(JacksonConverter.class);
            assertThat(messageConverter).isInstanceOf(JacksonConverter.class);
            assertThat(eventConverter).isInstanceOf(JacksonConverter.class);

            assertThat(objectMapper).isEqualTo(resultGeneralMapper);
            assertThat(objectMapper).isEqualTo(resultMessageMapper);
            assertThat(objectMapper).isEqualTo(resultEventMapper);
            assertThat(resultGeneralMapper).isEqualTo(resultMessageMapper);
            assertThat(resultMessageMapper).isEqualTo(resultEventMapper);
        });
    }

    @Test
    void customObjectMapperIsUsedForExpectedConverters() {
        // Jackson is used for general and events. CBOR for messages.
        testContext.withUserConfiguration(CustomMapperContext.class).withPropertyValues(
                "axon.axonserver.enabled=false",
                "axon.converter.general=jackson",
                "axon.converter.messages=cbor",
                "axon.converter.events=jackson"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = context.getBean(Converter.class);
            Converter messageConverter = getWrappedMessageConverter(context.getBean(MessageConverter.class));
            Converter eventConverter = getWrappedEventConverter(context.getBean(EventConverter.class));
            ObjectMapper objectMapper = context.getBean("testObjectMapper", ObjectMapper.class);

            ObjectMapper resultGeneralMapper = ReflectionUtils.getFieldValue(objectMapperField, generalConverter);
            ObjectMapper resultMessageMapper = ReflectionUtils.getFieldValue(objectMapperField, messageConverter);
            ObjectMapper resultEventMapper = ReflectionUtils.getFieldValue(objectMapperField, eventConverter);

            assertInstanceOf(JacksonConverter.class, generalConverter);
            assertInstanceOf(JacksonConverter.class, messageConverter);
            assertInstanceOf(JacksonConverter.class, eventConverter);
            assertThat(objectMapper).isEqualTo(resultGeneralMapper);
            assertThat(objectMapper).isNotEqualTo(resultMessageMapper);
            assertThat(objectMapper).isEqualTo(resultEventMapper);
            assertThat(resultGeneralMapper).isEqualTo(resultEventMapper);
        });
    }

    @Test
    void defaultCBORMapperIsUsedForExpectedConverters() {
        // Jackson is used for general, CBOR for messages and events.
        testContext.withPropertyValues(
                "axon.axonserver.enabled=false",
                "axon.converter.general=jackson",
                "axon.converter.messages=cbor",
                "axon.converter.events=cbor"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = context.getBean(Converter.class);
            Converter messageConverter = getWrappedMessageConverter(context.getBean(MessageConverter.class));
            Converter eventConverter = getWrappedEventConverter(context.getBean(EventConverter.class));

            ObjectMapper resultGeneralMapper = ReflectionUtils.getFieldValue(objectMapperField, generalConverter);
            ObjectMapper resultMessageMapper = ReflectionUtils.getFieldValue(objectMapperField, messageConverter);
            ObjectMapper resultEventMapper = ReflectionUtils.getFieldValue(objectMapperField, eventConverter);

            ObjectMapper objectMapper = context.getBean("defaultAxonObjectMapper", ObjectMapper.class);
            CBORMapper cborMapper = context.getBean("defaultAxonCborMapper", CBORMapper.class);

            assertThat(generalConverter).isInstanceOf(JacksonConverter.class);
            assertThat(messageConverter).isInstanceOf(JacksonConverter.class);
            assertThat(eventConverter).isInstanceOf(JacksonConverter.class);

            assertThat(objectMapper).isEqualTo(resultGeneralMapper);
            assertThat(cborMapper).isEqualTo(resultMessageMapper);
            assertThat(cborMapper).isEqualTo(resultEventMapper);
            assertThat(resultGeneralMapper).isNotEqualTo(resultMessageMapper);
            assertThat(resultMessageMapper).isEqualTo(resultEventMapper);
        });
    }

    @Test
    void customCBORMapperIsUsedForExpectedConverters() {
        // Jackson is used for general and events. CBOR for messages.
        testContext.withUserConfiguration(CustomMapperContext.class).withPropertyValues(
                "axon.axonserver.enabled=false",
                "axon.converter.general=jackson",
                "axon.converter.messages=cbor",
                "axon.converter.events=jackson"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = context.getBean(Converter.class);
            Converter messageConverter = getWrappedMessageConverter(context.getBean(MessageConverter.class));
            Converter eventConverter = getWrappedEventConverter(context.getBean(EventConverter.class));
            CBORMapper cborMapper = context.getBean("testCborMapper", CBORMapper.class);

            ObjectMapper resultGeneralMapper = ReflectionUtils.getFieldValue(objectMapperField, generalConverter);
            ObjectMapper resultMessageMapper = ReflectionUtils.getFieldValue(objectMapperField, messageConverter);
            ObjectMapper resultEventMapper = ReflectionUtils.getFieldValue(objectMapperField, eventConverter);

            assertInstanceOf(JacksonConverter.class, generalConverter);
            assertInstanceOf(JacksonConverter.class, messageConverter);
            assertInstanceOf(JacksonConverter.class, eventConverter);
            assertThat(cborMapper).isNotEqualTo(resultGeneralMapper);
            assertThat(cborMapper).isEqualTo(resultMessageMapper);
            assertThat(cborMapper).isNotEqualTo(resultEventMapper);
        });
    }

    private static Converter getWrappedMessageConverter(MessageConverter messageConverter) {
        assertThat(messageConverter).isInstanceOf(DelegatingMessageConverter.class);
        return ((DelegatingMessageConverter) messageConverter).delegate();
    }

    private static Converter getWrappedEventConverter(EventConverter eventConverter) {
        assertThat(eventConverter).isInstanceOf(DelegatingEventConverter.class);
        return getWrappedMessageConverter(((DelegatingEventConverter) eventConverter).delegate());
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
    })
    public static class TestContext {


    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
    })
    public static class CustomContext {

        @Bean
        public Converter customConverter() {
            return mock(Converter.class);
        }

        @Bean
        public MessageConverter customMessageConverter() {
            return mock(MessageConverter.class);
        }

        @Bean
        public EventConverter customEventConverter() {
            return mock(EventConverter.class);
        }
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
    })
    public static class CustomMapperContext {

        @Bean("testObjectMapper")
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean("testCborMapper")
        public CBORMapper cborMapper() {
            return new CBORMapper();
        }
    }
}
