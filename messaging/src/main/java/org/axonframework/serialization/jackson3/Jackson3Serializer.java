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

package org.axonframework.serialization.jackson3;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ObjectUtils;
import org.axonframework.messaging.MetaData;
import org.axonframework.serialization.AnnotationRevisionResolver;
import org.axonframework.serialization.ChainingConverter;
import org.axonframework.serialization.Converter;
import org.axonframework.serialization.RevisionResolver;
import org.axonframework.serialization.SerializationException;
import org.axonframework.serialization.SerializedObject;
import org.axonframework.serialization.SerializedType;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.SimpleSerializedObject;
import org.axonframework.serialization.SimpleSerializedType;
import org.axonframework.serialization.UnknownSerializedType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.TypeFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import javax.annotation.Nonnull;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Serializer implementation that uses Jackson 3 to serialize objects into a JSON format. Although the Jackson serializer
 * requires classes to be compatible with this specific serializer, it provides much more compact serialization, while
 * still being human-readable.
 *
 * @author Allard Buijze
 * @author John Hendrikx
 * @since 4.13.0
 */
public class Jackson3Serializer implements Serializer {

    private static final PolymorphicTypeValidator ALLOW_ALL = BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType(Object.class)
        .build();

    private static final PolymorphicTypeValidator AXON_ONLY = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("org.axonframework.")
        .build();

    private final RevisionResolver revisionResolver;
    private final Converter converter;
    private final ObjectMapper objectMapper;
    private final Set<String> unknownClasses = new ConcurrentSkipListSet<>();
    private final boolean cacheUnknownClasses;

    /**
     * Instantiate a Builder to be able to create a {@link Jackson3Serializer}.
     * <p>
     * The {@link RevisionResolver} is defaulted to an {@link AnnotationRevisionResolver}, the {@link Converter} to a
     * {@link ChainingConverter} and the {@link ClassLoader} to the ClassLoader of {@code this} class.
     * <p>
     * Upon instantiation, an ObjectMapper will be created with the {@link MetaDataDeserializer} registered to it.
     * Lastly, if the provided converter is of type ChainingConverter, the {@link Jackson3Serializer#registerConverters}
     * is performed to automatically add the {@link JsonNodeToByteArrayConverter} and {@link ByteArrayToJsonNodeConverter}.
     *
     * @return a Builder to be able to create a {@link Jackson3Serializer}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Instantiate a default {@link JacksonSerializer3}.
     * <p>
     * The {@link RevisionResolver} is defaulted to an {@link AnnotationRevisionResolver}, the {@link Converter} to a
     * {@link ChainingConverter}, and the {@link ClassLoader} to the ClassLoader of {@code this} class.
     * <p>
     * Upon instantiation, an ObjectMapper will be created with the {@link MetaDataDeserializer} registered to it.
     * Lastly, if the provided converter is of type ChainingConverter, the {@link Jackson3Serializer#registerConverters}
     * is performed to automatically add the {@link JsonNodeToByteArrayConverter} and {@link ByteArrayToJsonNodeConverter}.
     *
     * @return a {@link Jackson3Serializer}
     */
    public static Jackson3Serializer defaultSerializer() {
        return builder().build();
    }

    /**
     * Instantiate a {@link Jackson3Serializer} based on the fields contained in the {@link Builder}.
     * <p>
     * If the provided converter is of type ChainingConverter, {@link Jackson3Serializer#registerConverters}
     * is called to automatically add the {@link JsonNodeToByteArrayConverter} and {@link ByteArrayToJsonNodeConverter}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link Jackson3Serializer} instance
     */
    protected Jackson3Serializer(Builder builder) {
        builder.validate();
        this.revisionResolver = builder.revisionResolver;
        this.converter = builder.converter;

        JsonMapper.Builder jmb = JsonMapper.builder();

        jmb.addModule(new SimpleModule("Axon-Jackson3 Module").addDeserializer(MetaData.class, new MetaDataDeserializer()));
        jmb.findAndAddModules();
        if (builder.lenientDeserialization) {
            jmb.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
            jmb.enable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS);
            jmb.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }

        PolymorphicTypeValidator typeValidator = builder.defaultTyping ? ALLOW_ALL : AXON_ONLY;

        if (builder.defaultTyping) {
            jmb.activateDefaultTyping(typeValidator, DefaultTyping.NON_CONCRETE_AND_ARRAYS);
        }

        jmb.polymorphicTypeValidator(typeValidator);

        jmb.typeFactory(builder.classLoader == null
            ? TypeFactory.createDefaultInstance()
            : TypeFactory.createDefaultInstance().withClassLoader(builder.classLoader)
        );

        if (builder.customizer != null) {
            builder.customizer.accept(jmb);
        }

        this.objectMapper = jmb.build();

        if (converter instanceof ChainingConverter) {
            registerConverters((ChainingConverter) converter);
        }
        this.cacheUnknownClasses = builder.cacheUnknownClasses;
    }

    /**
     * Registers converters with the given {@code converter} which depend on the actual contents of the serialized form
     * to represent a JSON format.
     *
     * @param converter The ChainingConverter instance to register the converters with.
     */
    protected void registerConverters(ChainingConverter converter) {
        converter.registerConverter(new JsonNodeToByteArrayConverter(objectMapper));
        converter.registerConverter(new ByteArrayToJsonNodeConverter(objectMapper));
        converter.registerConverter(new JsonNodeToObjectNodeConverter());
        converter.registerConverter(new ObjectNodeToJsonNodeConverter());
    }

    @Override
    public <T> SerializedObject<T> serialize(Object object, @Nonnull Class<T> expectedRepresentation) {
        try {
            if (String.class.equals(expectedRepresentation)) {
                @SuppressWarnings("unchecked")
                T cast = (T) getWriter().writeValueAsString(object);
                return new SimpleSerializedObject<>(cast, expectedRepresentation,
                                                    typeForClass(ObjectUtils.nullSafeTypeOf(object)));
            }

            byte[] serializedBytes = getWriter().writeValueAsBytes(object);
            T serializedContent = converter.convert(serializedBytes, expectedRepresentation);

            return new SimpleSerializedObject<>(serializedContent, expectedRepresentation,
                                                typeForClass(ObjectUtils.nullSafeTypeOf(object)));
        } catch (JacksonException e) {
            throw new SerializationException("Unable to serialize object, details: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the ObjectMapper used by this serializer, allowing for configuration of the serialization settings.
     *
     * @return the ObjectMapper instance used by his serializer
     */
    public final ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Provides the ObjectWriter, with which objects are serialized to JSON form. This method may be overridden to
     * change the configuration of the writer to use.
     *
     * @return The writer to serialize objects with
     */
    protected ObjectWriter getWriter() {
        return objectMapper.writer();
    }

    /**
     * Provides the ObjectReader, with which objects are read from the JSON form. This method may be overridden to
     * change the configuration of the reader to use.
     *
     * @param type The type of object to create a reader for
     * @return The writer to serialize objects with
     */
    protected ObjectReader getReader(Class<?> type) {
        return objectMapper.readerFor(type);
    }

    @Override
    public <T> boolean canSerializeTo(@Nonnull Class<T> expectedRepresentation) {
        return JsonNode.class.equals(expectedRepresentation) || String.class.equals(expectedRepresentation) ||
                converter.canConvert(byte[].class, expectedRepresentation);
    }

    @Override
    public <S, T> T deserialize(@Nonnull SerializedObject<S> serializedObject) {
        try {
            if (SerializedType.emptyType().equals(serializedObject.getType())) {
                return null;
            }
            Class<?> type = classForType(serializedObject.getType());
            if (UnknownSerializedType.class.isAssignableFrom(type)) {
                @SuppressWarnings("unchecked")
                T cast = (T) new UnknownSerializedType(this, serializedObject);
                return cast;
            }
            if (JsonNode.class.equals(serializedObject.getContentType())) {
                return getReader(type)
                        .readValue((JsonNode) serializedObject.getData());
            }
            SerializedObject<byte[]> byteSerialized = converter.convert(serializedObject, byte[].class);
            return getReader(type).readValue(byteSerialized.getData());
        } catch (JacksonException e) {
            throw new SerializationException("Error while deserializing object", e);
        }
    }

    @Override
    public Class<?> classForType(@Nonnull SerializedType type) {
        if (SimpleSerializedType.emptyType().equals(type)) {
            return Void.class;
        }
        String className = resolveClassName(type);
        if (cacheUnknownClasses && unknownClasses.contains(className)) {
            return UnknownSerializedType.class;
        }
        try {
            return objectMapper.getTypeFactory().findClass(className);
        } catch (ClassNotFoundException e) {
            unknownClasses.add(className);
            return UnknownSerializedType.class;
        }
    }

    /**
     * Resolve the class name from the given {@code serializedType}. This method may be overridden to customize the
     * names used to denote certain classes, for example, by leaving out a certain base package for brevity.
     *
     * @param serializedType The serialized type to resolve the class name for
     * @return The fully qualified name of the class to load
     */
    protected String resolveClassName(SerializedType serializedType) {
        return serializedType.getName();
    }

    @Override
    public SerializedType typeForClass(Class type) {
        if (type == null || Void.TYPE.equals(type) || Void.class.equals(type)) {
            return SimpleSerializedType.emptyType();
        }
        return new SimpleSerializedType(type.getName(), revisionResolver.revisionOf(type));
    }

    @Override
    public Converter getConverter() {
        return converter;
    }

    /**
     * Returns the revision resolver used by this serializer.
     *
     * @return the revision resolver
     */
    protected RevisionResolver getRevisionResolver() {
        return revisionResolver;
    }

    /**
     * Builder class to instantiate a {@link Jackson3Serializer}.
     * <p>
     * The {@link RevisionResolver} is defaulted to an {@link AnnotationRevisionResolver}, the {@link Converter} to a
     * {@link ChainingConverter} and the {@link ClassLoader} to the ClassLoader of {@code this} class.
     * <p>
     * Upon instantiation, the created ObjectMapper will have the {@link MetaDataDeserializer} registered to it. Lastly, if
     * the provided converter is of type ChainingConverter, the {@link Jackson3Serializer#registerConverters} is
     * performed to automatically add the {@link JsonNodeToByteArrayConverter} and {@link ByteArrayToJsonNodeConverter}.
     */
    public static class Builder {

        private RevisionResolver revisionResolver = new AnnotationRevisionResolver();
        private Converter converter = new ChainingConverter();
        private Jackson3SerializerCustomizer customizer;
        private boolean lenientDeserialization = false;
        private boolean defaultTyping;
        private ClassLoader classLoader;
        private boolean cacheUnknownClasses = true;

        /**
         * Sets the {@link RevisionResolver} used to resolve the revision from an object to be serialized. Defaults to
         * an {@link AnnotationRevisionResolver} which resolves the revision based on the contents of the {@link
         * org.axonframework.serialization.Revision} annotation on the serialized classes.
         *
         * @param revisionResolver a {@link RevisionResolver} used to resolve the revision from an object to be
         *                         serialized
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder revisionResolver(RevisionResolver revisionResolver) {
            assertNonNull(revisionResolver, "RevisionResolver may not be null");
            this.revisionResolver = revisionResolver;
            return this;
        }

        /**
         * Sets the {@link Converter} used as a converter factory providing converter instances utilized by upcasters to
         * convert between different content types. Defaults to a {@link ChainingConverter}.
         *
         * @param converter a {@link Converter} used as a converter factory providing converter instances utilized by
         *                  upcasters to convert between different content types
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder converter(Converter converter) {
            assertNonNull(converter, "Converter may not be null");
            this.converter = converter;
            return this;
        }

        /**
         * Sets a customizer for the {@link JsonMapper.Builder} used to construct the final {@link ObjectMapper}.
         * This customizer is called after all Axon configuration has been applied. Setting this value to
         * {@code null} removes a previous customizer if any.
         *
         * @param customizer a customizer that is called just before the final {@link ObjectMapper} is constructed
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder jsonMapperBuilderCustomizer(Jackson3SerializerCustomizer customizer) {
            this.customizer = customizer;
            return this;
        }

        /**
         * Configures the {@link TypeFactory} to use the given {@link ClassLoader} when building the
         * {@link ObjectMapper}. This may also be done in a customizer.
         *
         * @param classLoader the {@link ClassLoader} used to load classes with when deserializing
         * @return the current Builder instance, for fluent interfacing
         * @see #objectMapper(ObjectMapper)
         * @see com.fasterxml.jackson.databind.type.TypeFactory#withClassLoader(ClassLoader)
         */
        public Builder classLoader(ClassLoader classLoader) {
            assertNonNull(classLoader, "ClassLoader may not be null");
            this.classLoader = classLoader;
            return this;
        }

        /**
         * Configures the serializer to be lenient when when deserializing JSON into Java objects. Specifically,
         * enables the {@link DeserializationFeature#ACCEPT_SINGLE_VALUE_AS_ARRAY} and {@link
         * DeserializationFeature#UNWRAP_SINGLE_VALUE_ARRAYS}, and disables {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}.
         * This may also be done in a customizer.
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder lenientDeserialization() {
            lenientDeserialization = true;
            return this;
        }

        /**
         * Disables the caching of the names for classes which couldn't be resolved to a class.
         * <p>
         * It is recommended to disable this in case the class loading is dynamic, and classes that are not available at
         * one point in time, may become available at any time later.
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder disableCachingOfUnknownClasses() {
            this.cacheUnknownClasses = false;
            return this;
        }

        /**
         * Configures the underlying {@link ObjectMapper} to include type information when serializing Java objects
         * into JSON by activating default typing.
         * <p>
         * Specifically, it uses {@link ObjectMapper#activateDefaultTyping(PolymorphicTypeValidator, ObjectMapper.DefaultTyping)}
         * with {@link ObjectMapper.DefaultTyping#NON_CONCRETE_AND_ARRAYS}. To comply with Jackson 3 security
         * requirements, this enables a {@link com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator}
         * that allows all subtypes (via {@code Object.class} base type validation).
         * <p>
         * This allows {@link java.util.Collection}s and other non-concrete types to include type information
         * automatically without requiring {@link com.fasterxml.jackson.annotation.JsonTypeInfo} annotations
         * on the classes themselves.
         *
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder defaultTyping() {
            defaultTyping = true;
            return this;
        }

        /**
         * Initializes a {@link Jackson3Serializer} as specified through this Builder.
         *
         * @return a {@link Jackson3Serializer} as specified through this Builder
         */
        public Jackson3Serializer build() {
            return new Jackson3Serializer(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            // Method kept for overriding
        }
    }
}
