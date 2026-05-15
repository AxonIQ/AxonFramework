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

import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.extension.springboot.ConverterProperties;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.dataformat.cbor.CBORMapper;

import static java.util.Objects.requireNonNull;

/**
 * Autoconfigures the CBOR based {@link JacksonConverter} if configured via the {@link ConverterProperties}.
 *
 * @author Jakob Hatzl
 * @since 5.1.0
 */
@AutoConfiguration
@AutoConfigureBefore(ConverterAutoConfiguration.class)
@ConditionalOnClass(name = {"tools.jackson.dataformat.cbor.CBORMapper"})
@EnableConfigurationProperties(value = ConverterProperties.class)
public class CBORConverterAutoConfiguration implements BeanClassLoaderAware {

    private ClassLoader classLoader;

    /**
     * Bean creation method constructing a CBOR based {@link JacksonConverter} as the {@link GeneralConverter} to be
     * used by Axon Framework.
     *
     * @param cborMapper the {@link CBORMapper} to be used
     * @return the {@link GeneralConverter} to be used by Axon Framework
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(ignored = {MessageConverter.class, EventConverter.class})
    @ConditionalOnProperty(name = "axon.converter.general", havingValue = "cbor")
    public GeneralConverter converter(CBORMapper cborMapper) {
        return new DelegatingGeneralConverter(buildConverter(cborMapper));
    }

    /**
     * Bean creation method constructing a {@link MessageConverter} delegating to the {@link GeneralConverter} in case
     * both use {@code cbor}.
     *
     * @param generalConverter the {@link GeneralConverter}, used to construct the {@link MessageConverter} in case both
     *                         use {@code cbor}
     * @return the {@link MessageConverter} to be used by Axon Framework
     */
    @Bean(name = "messageConverter")
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${axon.converter.messages}' == 'cbor' && '${axon.converter.general}' == 'cbor'")
    public MessageConverter delegatingMessageConverter(GeneralConverter generalConverter) {
        return new DelegatingMessageConverter(generalConverter);
    }

    /**
     * Bean creation method constructing a CBOR based {@link JacksonConverter} as the {@link MessageConverter} to be
     * used by Axon Framework.
     *
     * @param cborMapper the {@link CBORMapper} to be used
     * @return the {@link MessageConverter} to be used by Axon Framework
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${axon.converter.messages}' == 'cbor' && '${axon.converter.general}' != 'cbor'")
    public MessageConverter messageConverter(CBORMapper cborMapper) {
        return new DelegatingMessageConverter(buildConverter(cborMapper));
    }

    /**
     * Bean creation method constructing an {@link EventConverter} delegating to the {@link MessageConverter} in case
     * both use {@code cbor}.
     *
     * @param messageConverter the {@link MessageConverter}, used to construct the {@link EventConverter} in case both
     *                         use {@code cbor}
     * @return the {@link EventConverter} to be used by Axon Framework
     */
    @Bean(name = "eventConverter")
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${axon.converter.events}' == 'cbor' && '${axon.converter.messages}' == 'cbor'")
    public EventConverter delegatingEventConverter(MessageConverter messageConverter) {
        return new DelegatingEventConverter(messageConverter);
    }

    /**
     * Bean creation method constructing a CBOR based {@link JacksonConverter} as the {@link EventConverter} to be used
     * by Axon Framework.
     *
     * @param cborMapper the {@link CBORMapper} to be used
     * @return the {@link EventConverter} to be used by Axon Framework
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${axon.converter.events}' == 'cbor' && '${axon.converter.messages}' != 'cbor'")
    public EventConverter eventConverter(CBORMapper cborMapper) {
        return new DelegatingEventConverter(buildConverter(cborMapper));
    }

    private Converter buildConverter(CBORMapper cborMapper) {
        return new JacksonConverter(cborMapper, new ChainingContentTypeConverter(classLoader));
    }

    /**
     * Sets the class loader used by the {@link ChainingContentTypeConverter} to load
     * {@link org.axonframework.conversion.ContentTypeConverter ContentTypeConverters}.
     *
     * @param classLoader the class loader used by the {@link ChainingContentTypeConverter} to load
     *                    {@link org.axonframework.conversion.ContentTypeConverter ContentTypeConverters}
     */
    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = requireNonNull(classLoader, "The ClassLoader cannot be null.");
    }
}
