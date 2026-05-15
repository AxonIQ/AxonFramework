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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.conversion.jackson2.Jackson2Converter;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
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
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.cbor.CBORMapper;

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
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class);
    }

    @Test
    void defaultConverterConfigurationUsesJacksonConverterThroughout() {
        testContext.run(context -> {
            GeneralConverter generalConverter = context.getBean(GeneralConverter.class);
            MessageConverter messageConverter = context.getBean(MessageConverter.class);
            EventConverter eventConverter = context.getBean(EventConverter.class);

            assertThat(generalConverter).isInstanceOf(DelegatingGeneralConverter.class);
            assertThat(getWrappedGeneralConverter(generalConverter)).isInstanceOf(JacksonConverter.class);
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
                "axon.converter.general=jackson",
                "axon.converter.messages=jackson",
                "axon.converter.events=jackson"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = getWrappedGeneralConverter(context.getBean(GeneralConverter.class));
            Converter messageConverter = getWrappedGeneralConverter((GeneralConverter) getWrappedMessageConverter(
                    context.getBean(MessageConverter.class)));
            Converter eventConverter = getWrappedGeneralConverter((GeneralConverter) getWrappedEventConverter(context.getBean(
                    EventConverter.class)));

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
                "axon.converter.general=jackson",
                "axon.converter.messages=cbor",
                "axon.converter.events=jackson"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = getWrappedGeneralConverter(context.getBean(GeneralConverter.class));
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
                "axon.converter.general=jackson",
                "axon.converter.messages=cbor",
                "axon.converter.events=cbor"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = getWrappedGeneralConverter(context.getBean(GeneralConverter.class));
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
                "axon.converter.general=jackson",
                "axon.converter.messages=cbor",
                "axon.converter.events=jackson"
        ).run(context -> {
            Field objectMapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = getWrappedGeneralConverter(context.getBean(GeneralConverter.class));
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

    @Test
    void defaultAxonJackson2MapperIsUsedForExpectedConverters() {
        // Jackson is used for all Converters.
        testContext.withPropertyValues(
                "axon.converter.general=jackson2",
                "axon.converter.messages=jackson2",
                "axon.converter.events=jackson2"
        ).run(context -> {
            Field objectMapperField = Jackson2Converter.class.getDeclaredField("objectMapper");

            Converter generalConverter = getWrappedGeneralConverter(context.getBean(GeneralConverter.class));
            Converter messageConverter = getWrappedGeneralConverter((GeneralConverter) getWrappedMessageConverter(
                    context.getBean(MessageConverter.class)));
            Converter eventConverter = getWrappedGeneralConverter((GeneralConverter) getWrappedEventConverter(context.getBean(
                    EventConverter.class)));

            com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                    context.getBean("defaultAxonJackson2Mapper", com.fasterxml.jackson.databind.ObjectMapper.class);

            Object resultGeneralMapper = ReflectionUtils.getFieldValue(objectMapperField, generalConverter);
            Object resultMessageMapper = ReflectionUtils.getFieldValue(objectMapperField, messageConverter);
            Object resultEventMapper = ReflectionUtils.getFieldValue(objectMapperField, eventConverter);

            assertThat(generalConverter).isInstanceOf(Jackson2Converter.class);
            assertThat(messageConverter).isInstanceOf(Jackson2Converter.class);
            assertThat(eventConverter).isInstanceOf(Jackson2Converter.class);

            assertThat(objectMapper).isEqualTo(resultGeneralMapper);
            assertThat(objectMapper).isEqualTo(resultMessageMapper);
            assertThat(objectMapper).isEqualTo(resultEventMapper);
            assertThat(resultGeneralMapper).isEqualTo(resultMessageMapper);
            assertThat(resultMessageMapper).isEqualTo(resultEventMapper);
        });
    }

    @Test
    void customJackson2ObjectMapperIsUsedForExpectedConverters() {
        // Jackson is used for general and events. CBOR for messages.
        testContext.withUserConfiguration(CustomJackson2MapperContext.class).withPropertyValues(
                "axon.converter.general=jackson2",
                "axon.converter.messages=cbor",
                "axon.converter.events=jackson2"
        ).run(context -> {
            Field mapper2Field = Jackson2Converter.class.getDeclaredField("objectMapper");
            Field mapperField = JacksonConverter.class.getDeclaredField("objectMapper");

            Converter generalConverter = getWrappedGeneralConverter(context.getBean(GeneralConverter.class));
            Converter messageConverter = getWrappedMessageConverter(context.getBean(MessageConverter.class));
            Converter eventConverter = getWrappedEventConverter(context.getBean(EventConverter.class));
            com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                    context.getBean("testObjectMapper", com.fasterxml.jackson.databind.ObjectMapper.class);

            com.fasterxml.jackson.databind.ObjectMapper resultGeneralMapper =
                    ReflectionUtils.getFieldValue(mapper2Field, generalConverter);
            ObjectMapper resultMessageMapper =
                    ReflectionUtils.getFieldValue(mapperField, messageConverter);
            com.fasterxml.jackson.databind.ObjectMapper resultEventMapper =
                    ReflectionUtils.getFieldValue(mapper2Field, eventConverter);

            assertInstanceOf(Jackson2Converter.class, generalConverter);
            assertInstanceOf(JacksonConverter.class, messageConverter);
            assertInstanceOf(Jackson2Converter.class, eventConverter);
            assertThat(objectMapper).isEqualTo(resultGeneralMapper);
            assertThat(objectMapper).isNotEqualTo(resultMessageMapper);
            assertThat(objectMapper).isEqualTo(resultEventMapper);
            assertThat(resultGeneralMapper).isEqualTo(resultEventMapper);
        });
    }

    private static Converter getWrappedGeneralConverter(GeneralConverter messageConverter) {
        assertThat(messageConverter).isInstanceOf(DelegatingGeneralConverter.class);
        return ((DelegatingGeneralConverter) messageConverter).delegate();
    }

    private static Converter getWrappedMessageConverter(MessageConverter messageConverter) {
        assertThat(messageConverter).isInstanceOf(DelegatingMessageConverter.class);
        return ((DelegatingMessageConverter) messageConverter).delegate();
    }

    private static Converter getWrappedEventConverter(EventConverter eventConverter) {
        assertThat(eventConverter).isInstanceOf(DelegatingEventConverter.class);
        return getWrappedMessageConverter(((DelegatingEventConverter) eventConverter).delegate());
    }

    @Test
    void noObjectMapperBeanIsCreatedIfNoJacksonConverterIsSpecified() {
        testContext.withUserConfiguration(JacksonlessTestContext.class).withPropertyValues(
                "axon.converter.general=cbor",
                "axon.converter.messages=avro",
                "axon.converter.events=avro"
        ).run(context -> {
            assertThat(context).doesNotHaveBean("defaultAxonObjectMapper");
            assertThat(context).doesNotHaveBean("defaultAxonJackson2Mapper");
        });
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
        @Primary
        public GeneralConverter customConverter() {
            return mock(GeneralConverter.class);
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
        @Primary
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean("testCborMapper")
        public CBORMapper cborMapper() {
            return new CBORMapper();
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
    public static class CustomJackson2MapperContext {

        @Bean("testObjectMapper")
        public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            JmxAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            DataSourceAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class
    })
    public static class JacksonlessTestContext {

    }
}
