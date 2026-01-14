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
import jakarta.annotation.Nonnull;
import org.apache.avro.message.SchemaStore;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.conversion.ContentTypeConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.avro.AvroConverter;
import org.axonframework.conversion.avro.AvroConverterConfiguration;
import org.axonframework.conversion.avro.AvroConverterStrategy;
import org.axonframework.conversion.json.JacksonConverter;
import org.axonframework.extension.micronaut.ConverterProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.springframework.beans.factory.BeanFactoryUtils.beansOfTypeIncludingAncestors;

/**
 * Autoconfiguration class dedicated to configuring the {@link Converter}.
 * <p>
 * Users can influence the configuration through the {@link ConverterProperties}.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
@AutoConfiguration
@AutoConfigureBefore(AxonAutoConfiguration.class)
@EnableConfigurationProperties(ConverterProperties.class)
public class ConverterAutoConfiguration implements ApplicationContextAware, BeanClassLoaderAware {

    private ApplicationContext applicationContext;
    private ClassLoader classLoader;

    /**
     * Bean creation method constructing the "general" {@link Converter} to be used by Axon Framework.
     * <p>
     * The type of {@code Converter} constructed depends on the given {@code converterProperties}.
     *
     * @param converterProperties The properties dictating the type of {@link Converter} this bean creation method will
     *                            construct.
     * @return The "general" {@link Converter} to be used by Axon Framework.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    public Converter converter(ConverterProperties converterProperties) {
        ConverterProperties.ConverterType generalConverterType = converterProperties.getGeneral();
        if (ConverterProperties.ConverterType.AVRO == generalConverterType) {
            throw new AxonConfigurationException(format(
                    "Invalid converter type [%s] configured as general converter. "
                            + "The Avro Converter can be used as message or event converter only.",
                    generalConverterType.name()
            ));
        }
        return buildConverter(generalConverterType);
    }

    /**
     * Bean creation method constructing the {@link MessageConverter} to be used by Axon Framework.
     *
     * @param generalConverter    The "general" {@link Converter}, used to construct the {@link MessageConverter}
     *                            whenever the {@link ConverterProperties#getMessages()} type equals the
     *                            {@link ConverterProperties#getGeneral()} type.
     * @param converterProperties The properties dictating the type of {@link MessageConverter} this bean creation
     *                            method will construct.
     * @return The {@link MessageConverter} to be used by Axon Framework.
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(Converter generalConverter, ConverterProperties converterProperties) {
        ConverterProperties.ConverterType messagesConverterType = converterProperties.getMessages();
        if (ConverterProperties.ConverterType.DEFAULT == messagesConverterType
                || converterProperties.getGeneral() == messagesConverterType) {
            return new DelegatingMessageConverter(generalConverter);
        } else {
            return new DelegatingMessageConverter(buildConverter(messagesConverterType));
        }
    }

    /**
     * Bean creation method constructing the {@link EventConverter} to be used by Axon Framework.
     *
     * @param generalConverter    The "general" {@link Converter}, used to construct the {@link EventConverter} whenever
     *                            the {@link ConverterProperties#getEvents()} type equals the
     *                            {@link ConverterProperties#getGeneral()} type.
     * @param messageConverter    The {@link MessageConverter}, used to construct the {@link EventConverter} whenever
     *                            the {@link ConverterProperties#getEvents()} type equals the
     *                            {@link ConverterProperties#getMessages()} type.
     * @param converterProperties The properties dictating the type of {@link Converter} this bean creation method will
     *                            construct.
     * @return The {@link EventConverter} to be used by Axon Framework.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventConverter eventConverter(Converter generalConverter,
                                         MessageConverter messageConverter,
                                         ConverterProperties converterProperties) {
        ConverterProperties.ConverterType eventsConverterType = converterProperties.getEvents();
        if (ConverterProperties.ConverterType.DEFAULT == eventsConverterType
                || converterProperties.getMessages() == eventsConverterType) {
            return new DelegatingEventConverter(messageConverter);
        } else if (converterProperties.getGeneral() == eventsConverterType) {
            return new DelegatingEventConverter(generalConverter);
        }
        return new DelegatingEventConverter(buildConverter(eventsConverterType));
    }

    @Nonnull
    private Converter buildConverter(@Nonnull ConverterProperties.ConverterType converterType) {
        switch (converterType) {
            case AVRO:
                Map<String, SchemaStore> schemaStoreBeans =
                        beansOfTypeIncludingAncestors(applicationContext, SchemaStore.class);
                SchemaStore schemaStore = schemaStoreBeans.containsKey("defaultAxonSchemaStore")
                        ? schemaStoreBeans.get("defaultAxonSchemaStore")
                        : schemaStoreBeans.values()
                                          .stream()
                                          .findFirst()
                                          .orElseThrow(() -> new NoSuchBeanDefinitionException(SchemaStore.class));

                Map<String, AvroConverterStrategy> converterStrategies = beansOfTypeIncludingAncestors(
                        applicationContext, AvroConverterStrategy.class);

                return new AvroConverter(
                        schemaStore,
                        (c) -> {
                            AvroConverterConfiguration result = c;
                            for (AvroConverterStrategy strategy : converterStrategies.values()) {
                                result = result.addConverterStrategy(strategy);
                            }
                            return result;
                        },
                        new ChainingContentTypeConverter(classLoader)
                );

            case CBOR:
                Map<String, CBORMapper> cborMapperBeans =
                        beansOfTypeIncludingAncestors(applicationContext, CBORMapper.class);
                ObjectMapper cborMapper = cborMapperBeans.containsKey("defaultAxonCborObjectMapper")
                        ? cborMapperBeans.get("defaultAxonCborObjectMapper")
                        : cborMapperBeans.values()
                                         .stream()
                                         .findFirst()
                                         .orElseThrow(() -> new NoSuchBeanDefinitionException(CBORMapper.class));
                return new JacksonConverter(cborMapper, new ChainingContentTypeConverter(classLoader));
            case JACKSON:
            case DEFAULT:
            default:
                Map<String, ObjectMapper> objectMapperBeans =
                        beansOfTypeIncludingAncestors(applicationContext, ObjectMapper.class);
                ObjectMapper objectMapper = objectMapperBeans.containsKey("defaultAxonObjectMapper")
                        ? objectMapperBeans.get("defaultAxonObjectMapper")
                        : objectMapperBeans.values()
                                           .stream()
                                           .findFirst()
                                           .orElseThrow(() -> new NoSuchBeanDefinitionException(ObjectMapper.class));
                return new JacksonConverter(objectMapper, new ChainingContentTypeConverter(classLoader));
        }
    }

    /**
     * Sets the application context used to validate for the existence of other required properties to construct the
     * specified {@link Converter Converters}.
     *
     * @param applicationContext The application context used to validate for the existence of other required properties
     *                           to construct the specified
     *                           {@link Converter Converters}.
     */
    @Override
    public void setApplicationContext(@Nonnull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = requireNonNull(applicationContext, "The ApplicationContext cannot be null.");
    }

    /**
     * Sets the class loader used by the {@link ChainingContentTypeConverter} to load
     * {@link ContentTypeConverter ContentTypeConverters}.
     *
     * @param classLoader The class loader used by the {@link ChainingContentTypeConverter} to load
     *                    {@link ContentTypeConverter ContentTypeConverters}.
     */
    @Override
    public void setBeanClassLoader(@Nonnull ClassLoader classLoader) {
        this.classLoader = requireNonNull(classLoader, "The ClassLoader cannot be null.");
    }
}
