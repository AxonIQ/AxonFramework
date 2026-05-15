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
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import static java.util.Objects.requireNonNull;

/**
 * Autoconfigures the {@link JacksonConverter} if configured via the {@link ConverterProperties}.
 *
 * @author Jakob Hatzl
 * @since 5.1.0
 */
@AutoConfiguration
@AutoConfigureBefore({
        AxonAutoConfiguration.class,
        ConverterAutoConfiguration.class,
        Jackson2ConverterAutoConfiguration.class
})
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
})
@ConditionalOnClass(name = "tools.jackson.databind.ObjectMapper")
@EnableConfigurationProperties(value = ConverterProperties.class)
public class JacksonConverterAutoConfiguration implements BeanClassLoaderAware {

    private ClassLoader classLoader;

    /**
     * Bean creation method constructing a {@link JacksonConverter} as the {@link GeneralConverter} to be used by Axon
     * Framework.
     * <p>
     * This bean acts as fallback and gets created in case {@code axon.converter.general} is not set or set to
     * {@code default}.
     *
     * @param objectMapper the {@link ObjectMapper} to be used
     * @return the {@link GeneralConverter} to be used by Axon Framework
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(ignored = {MessageConverter.class, EventConverter.class})
    @ConditionalOnExpression("'${axon.converter.general}' == 'jackson' || '${axon.converter.general:default}' == 'default'")
    public GeneralConverter converter(ObjectMapper objectMapper) {
        return new DelegatingGeneralConverter(buildConverter(objectMapper));
    }

    /**
     * Bean creation method constructing a {@link MessageConverter} delegating to the {@link GeneralConverter} in case
     * both use {@code jackson/default}.
     *
     * @param generalConverter the {@link GeneralConverter}, used to construct the {@link MessageConverter} in case both
     *                         use {@code jackson/default}
     * @return the {@link MessageConverter} to be used by Axon Framework
     */
    @Bean(name = "messageConverter")
    @ConditionalOnMissingBean
    @ConditionalOnExpression("""
            '${axon.converter.messages}' == 'jackson'
            && ('${axon.converter.general}' == 'jackson' || '${axon.converter.general:default}' == 'default')
            """)
    public MessageConverter delegatingMessageConverter(GeneralConverter generalConverter) {
        return new DelegatingMessageConverter(generalConverter);
    }

    /**
     * Bean creation method constructing a {@link JacksonConverter} as the {@link MessageConverter} to be used by Axon
     * Framework.
     *
     * @param objectMapper the {@link ObjectMapper} to be used
     * @return the {@link MessageConverter} to be used by Axon Framework
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("""
            '${axon.converter.messages}' == 'jackson'
            && !('${axon.converter.general}' == 'jackson' || '${axon.converter.general:default}' == 'default')
            """)
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new DelegatingMessageConverter(buildConverter(objectMapper));
    }

    /**
     * Bean creation method constructing an {@link EventConverter} delegating to the {@link MessageConverter} in case
     * both use {@code jackson}.
     *
     * @param messageConverter the {@link MessageConverter}, used to construct the {@link EventConverter} in case both
     *                         use {@code jackson}
     * @return the {@link EventConverter} to be used by Axon Framework
     */
    @Bean(name = "eventConverter")
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${axon.converter.events}' == 'jackson' && '${axon.converter.messages}' == 'jackson'")
    public EventConverter delegatingEventConverter(MessageConverter messageConverter) {
        return new DelegatingEventConverter(messageConverter);
    }

    /**
     * Bean creation method constructing a {@link JacksonConverter} as the {@link EventConverter} to be used by Axon
     * Framework.
     *
     * @param objectMapper the {@link ObjectMapper} to be used
     * @return the {@link EventConverter} to be used by Axon Framework
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${axon.converter.events}' == 'jackson' && '${axon.converter.messages}' != 'jackson'")
    public EventConverter eventConverter(ObjectMapper objectMapper) {
        return new DelegatingEventConverter(buildConverter(objectMapper));
    }

    private Converter buildConverter(ObjectMapper objectMapper) {
        return new JacksonConverter(objectMapper, new ChainingContentTypeConverter(classLoader));
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
