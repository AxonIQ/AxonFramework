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

import org.apache.avro.Schema;
import org.apache.avro.message.SchemaStore;
import org.axonframework.conversion.Converter;
import org.axonframework.extension.micronaut.conversion.avro.AvroSchemaPackages;
import org.axonframework.extension.micronaut.conversion.avro.AvroSchemaScan;
import org.axonframework.extension.micronaut.conversion.avro.ClasspathAvroSchemaLoader;
import org.axonframework.extension.micronaut.conversion.avro.SpecificRecordBaseClasspathAvroSchemaLoader;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Autoconfigures required beans for the Avro {@link Converter}.
 *
 * @author Simon Zambrovski
 * @author Jan Galinski
 * @since 4.11.0
 */
@AutoConfiguration
@AutoConfigureBefore(ConverterAutoConfiguration.class)
@ConditionalOnClass(name = {"org.apache.avro.message.SchemaStore"})
public class AvroSchemaStoreAutoConfiguration {

    /**
     * Constructs a simple default in-memory schema store filled with schemas.
     *
     * @param schemas Avro schemas to put into the store.
     * @return Schema store instance.
     */
    @Bean("defaultAxonSchemaStore")
    @Conditional({AvroConfiguredCondition.class, OnMissingDefaultSchemaStoreCondition.class})
    public SchemaStore defaultAxonSchemaStore(Set<Schema> schemas) {
        SchemaStore.Cache cachingSchemaStore = new SchemaStore.Cache();
        schemas.forEach(cachingSchemaStore::addSchema);
        return cachingSchemaStore;
    }

    /**
     * Scans classpath for schemas, configured using {@link AvroSchemaScan}
     * annotations.
     *
     * @param beanFactory  Spring bean factory.
     * @param schemaLoader List of schema loaders.
     * @return Set of schemas detected on the classpath.
     */
    @Bean
    @Conditional({AvroConfiguredCondition.class})
    public Set<Schema> collectAvroSchemasFromClassPath(BeanFactory beanFactory,
                                                       List<ClasspathAvroSchemaLoader> schemaLoader) {
        final List<String> packagesCandidates = AvroSchemaPackages.get(beanFactory).getPackages();
        final List<String> packagesToScan = new ArrayList<>();
        if (packagesCandidates.isEmpty() && AutoConfigurationPackages.has(beanFactory)) {
            packagesToScan.addAll(AutoConfigurationPackages.get(beanFactory));
        } else {
            packagesToScan.addAll(packagesCandidates);
        }
        return schemaLoader
                .stream()
                .map(loader -> loader.load(packagesToScan))
                .flatMap(List::stream)
                .collect(Collectors.toSet());
    }

    /**
     * Constructs default schema loader from Avro-Java-Maven-Generated classes.
     *
     * @param resourceLoader The resource loader.
     * @return ClasspathAvroSchemaLoader instance.
     */
    @Bean("specificRecordBaseClasspathAvroSchemaLoader")
    @Conditional({AvroConfiguredCondition.class})
    public ClasspathAvroSchemaLoader specificRecordBaseClasspathAvroSchemaLoader(ResourceLoader resourceLoader) {
        return new SpecificRecordBaseClasspathAvroSchemaLoader(resourceLoader);
    }

    /**
     * An {@link AnyNestedCondition} implementation, to support the following use cases:
     * <ul>
     *     <li>The {@code messages} serializer property is set to {@code avro}</li>
     *     <li>The {@code events} serializer property is set to {@code avro}</li>
     * </ul>
     */
    private static class AvroConfiguredCondition extends AnyNestedCondition {

        public AvroConfiguredCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @SuppressWarnings("unused")
        @ConditionalOnProperty(name = "axon.converter.messages", havingValue = "avro")
        static class MessagesAvroCondition {

        }

        @SuppressWarnings("unused")
        @ConditionalOnProperty(name = "axon.converter.events", havingValue = "avro")
        static class EventsAvroCondition {

        }
    }

    /**
     * Condition checking if a schema store exists.
     */
    private static class OnMissingDefaultSchemaStoreCondition extends AllNestedConditions {

        public OnMissingDefaultSchemaStoreCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnMissingBean(SchemaStore.class)
        @SuppressWarnings("unused")
        static class SchemaStoreIsMissingCondition {

        }
    }
}
