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

package org.axonframework.messaging.core.annotation;

import org.axonframework.common.StringUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A {@link MessageTypeResolver} implementation looking for configurable annotations on the {@link Class} this resolver
 * is invoked for.
 * <p>
 * This resolver can use unique annotations per attribute. The attributes searched for are the namespace, name, and
 * version, reflecting the {@link QualifiedName#namespace()}, {@link QualifiedName#localName()}, and
 * {@link MessageType#version()}. The default annotation for the name and version is the {@link Message} annotation,
 * from which the {@link Message#name() name} and {@link Message#version()} attributes will be used respectively. The
 * namespace attribute is determined by the {@link Namespace} annotation instead. The {@code Message} annotation is
 * meta-annotated with the namespace annotation as a fallback.
 * <p>
 * An empty namespace, name, or {@code @Namespace} value is treated as "not set". In that case the value is derived from
 * the payload type: the package name for the namespace and the simple class name for the name. A payload type in the
 * default package thus results in a {@code MessageType} without a namespace.
 * <p>
 * Allows for defining a fallback {@code MessageTypeResolver}, for when the defined annotations are not present.
 * <p>
 * As annotations are static class metadata, the annotation-based resolution result is cached per payload type without
 * preventing that type from being unloaded. Only when the configured annotations do not yield a {@link MessageType} is
 * the fallback consulted.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class AnnotationMessageTypeResolver implements MessageTypeResolver {

    @Nullable
    private final MessageTypeResolver fallback;
    private final AnnotationSpecification specification;
    private final ClassValue<Optional<MessageType>> annotationCache = new ClassValue<>() {
        @Override
        protected Optional<MessageType> computeValue(Class<?> type) {
            return resolveFromAnnotations(type);
        }
    };

    /**
     * Constructs a default {@code AnnotationMessageTypeResolver}, using the {@link ClassBasedMessageTypeResolver} as
     * the fallback when there is no {@link Message} annotation present on the given {@code payloadType} for resolving
     * the name and version specifically.
     */
    public AnnotationMessageTypeResolver() {
        this(new ClassBasedMessageTypeResolver(), AnnotationSpecification.DEFAULT);
    }

    /**
     * Constructs a default {@code AnnotationMessageTypeResolver}, using the given {@code fallback} when there is no
     * {@link Message} present on the given {@code payloadType} for resolving the name and version specifically.
     *
     * @param fallback the message type resolver to use as fall back when the {@link Message} annotation is not present
     *                 on the given {@code payloadType} for resolving the name and version specifically
     */
    public AnnotationMessageTypeResolver(@Nullable MessageTypeResolver fallback) {
        this(fallback, AnnotationSpecification.DEFAULT);
    }

    /**
     * Constructs a default {@code AnnotationMessageTypeResolver}, using the given {@code fallback} when the defined
     * {@link AnnotationSpecification#nameAnnotation()} and {@link AnnotationSpecification#versionAnnotation()} in the
     * given {@code specification} are not present on the given {@code payloadType} when resolving the
     * {@link MessageType}.
     *
     * @param fallback      the message type resolver to use as fall back when the
     *                      {@link AnnotationSpecification#nameAnnotation()} and
     *                      {@link AnnotationSpecification#versionAnnotation()} as defined in the given
     *                      {@code specification} are not present on the given {@code payloadType} when resolving the
     *                      {@link MessageType}
     * @param specification the specification that determines what annotation to look for on the given
     *                      {@code payloadType} when resolving the {@link MessageType}
     */
    public AnnotationMessageTypeResolver(@Nullable MessageTypeResolver fallback,
                                         AnnotationSpecification specification) {
        this.fallback = fallback;
        this.specification = Objects.requireNonNull(specification, "The annotation specification may not be null.");
    }

    @Override
    public Optional<MessageType> resolve(Class<?> payloadType) {
        Optional<MessageType> resolved = annotationCache.get(payloadType);
        if (resolved.isPresent()) {
            return resolved;
        }
        return fallback != null ? fallback.resolve(payloadType) : Optional.empty();
    }

    private Optional<MessageType> resolveFromAnnotations(Class<?> payloadType) {
        Map<String, Object> nameAttributes = attributesFor(payloadType, specification.nameAnnotation());
        Map<String, Object> versionAttributes = attributesFor(payloadType, specification.versionAnnotation());
        if (nameAttributes.isEmpty() || versionAttributes.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> namespaceAttributes = namespaceAttributesFor(payloadType);
        // An empty annotation value means "unset", so the specification's default applies. The namespace default
        // may be null, which results in a MessageType without a namespace.
        String annotatedNamespace = (String) namespaceAttributes.get(specification.namespaceAttribute());
        String namespace = StringUtils.nonEmptyOrNull(annotatedNamespace)
                ? annotatedNamespace
                : specification.namespaceDefault().apply(payloadType);
        String annotatedName = (String) nameAttributes.get(specification.nameAttribute());
        String name = StringUtils.nonEmptyOrNull(annotatedName)
                ? annotatedName
                : specification.nameDefault().apply(payloadType);
        return Optional.of(new MessageType(
                namespace, name, (String) versionAttributes.get(specification.versionAttribute())
        ));
    }


    private Map<String, Object> attributesFor(AnnotatedElement annotatedElement,
                                              Class<? extends Annotation> annotation) {
        return AnnotationUtils.findAnnotationAttributes(annotatedElement, annotation)
                              .orElse(Collections.emptyMap());
    }


    private Map<String, Object> namespaceAttributesFor(Class<?> payloadType) {
        return AnnotationUtils.findAnnotationAttributesOnType(
                payloadType,
                specification.namespaceAnnotation(),
                attrs -> !StringUtils.emptyOrNull((String) attrs.get(specification.namespaceAttribute()))
        ).orElse(Collections.emptyMap());
    }

    /**
     * An annotation specification unique to the {@link AnnotationMessageTypeResolver}.
     *
     * @param nameAnnotation      the annotation class to search for when {@link #resolve(Class)} is invoked for the
     *                            {@code nameAttribute} specifically
     * @param nameAttribute       the attribute for the {@link MessageType#name()}, that should be present on the given
     *                            {@code annotation}
     * @param nameDefault         derives the {@link QualifiedName#localName() local name} from the payload type when
     *                            the name is not set, or empty, on the annotations. Must be deterministic, since
     *                            results are cached per payload type
     * @param versionAnnotation   the annotation class to search for when {@link #resolve(Class)} is invoked for the
     *                            {@code versionAttribute} specifically
     * @param versionAttribute    the attribute for the {@link MessageType#version()}, that should be present on the
     *                            given {@code annotation}
     * @param namespaceAnnotation the annotation class to search for when {@link #resolve(Class)} is invoked for the
     *                            {@code namespaceAttribute} specifically
     * @param namespaceAttribute  the attribute for the {@link QualifiedName#namespace()} field of the
     *                            {@link QualifiedName} set in the resolved {@link MessageType}, or just the
     *                            {@link #nameAttribute()} if {@code null}
     * @param namespaceDefault    derives the namespace from the payload type when the namespace is not set, or empty,
     *                            on the annotations. May return {@code null} to resolve a {@code MessageType} without a
     *                            namespace. Must be deterministic, since results are cached per payload type
     */
    public record AnnotationSpecification(
            Class<? extends Annotation> nameAnnotation,
            String nameAttribute,
            Function<Class<?>, String> nameDefault,
            Class<? extends Annotation> versionAnnotation,
            String versionAttribute,
            Class<? extends Annotation> namespaceAnnotation,
            @Nullable String namespaceAttribute,
            Function<Class<?>, @Nullable String> namespaceDefault
    ) {

        /**
         * The default {@link AnnotationSpecification} that looks for the {@link Message} annotation for the
         * {@link #nameAttribute()} and {@link #versionAttribute()}, and the {@link Namespace} annotation for the
         * {@link #namespaceAttribute()}. The namespace and name default to the package and simple name of the payload
         * type, as derived by {@link QualifiedName#QualifiedName(Class)}.
         */
        public static final AnnotationSpecification DEFAULT = new AnnotationSpecification(
                Message.class, "name", Message.class, "version", Namespace.class, "namespace"
        );

        /**
         * Creates a specification, asserting the given defaults are non-null.
         */
        public AnnotationSpecification {
            Objects.requireNonNull(namespaceDefault, "The namespace default may not be null.");
            Objects.requireNonNull(nameDefault, "The name default may not be null.");
        }

        /**
         * An annotation specification unique to the {@link AnnotationMessageTypeResolver}, defaulting the namespace and
         * name to the package and simple name of the payload type.
         *
         * @param nameAnnotation      the annotation class to search for the {@code nameAttribute}
         * @param nameAttribute       the attribute for the {@link MessageType#name()}, that should be present on the
         *                            {@code nameAnnotation}
         * @param versionAnnotation   the annotation class to search for the {@code versionAttribute}
         * @param versionAttribute    the attribute for the {@link MessageType#version()}, that should be present on the
         *                            {@code versionAnnotation}
         * @param namespaceAnnotation the annotation class to search for the {@code namespaceAttribute}
         * @param namespaceAttribute  the attribute for the {@link QualifiedName#namespace()}, that should be present on
         *                            the {@code namespaceAnnotation}
         */
        public AnnotationSpecification(Class<? extends Annotation> nameAnnotation,
                                       String nameAttribute,
                                       Class<? extends Annotation> versionAnnotation,
                                       String versionAttribute,
                                       Class<? extends Annotation> namespaceAnnotation,
                                       @Nullable String namespaceAttribute) {
            this(nameAnnotation, nameAttribute, type -> new QualifiedName(type).localName(),
                 versionAnnotation, versionAttribute,
                 namespaceAnnotation, namespaceAttribute, type -> new QualifiedName(type).namespace());
        }

        /**
         * An annotation specification unique to the {@link AnnotationMessageTypeResolver}.
         *
         * @param annotation         the annotation class to search for when {@link #resolve(Class)} is invoked
         * @param nameAttribute      the attribute for the {@link MessageType#name()}, that should be present on the
         *                           given {@code annotation}
         * @param versionAttribute   the attribute for the {@link MessageType#version()}, that should be present on the
         *                           given {@code annotation}
         * @param namespaceAttribute the attribute for the {@link QualifiedName#namespace()} field of the
         *                           {@link QualifiedName} set in the resolved {@link MessageType}, or just the
         *                           {@link #nameAttribute()} if {@code null}
         * @deprecated in favor of the {@link #AnnotationSpecification(Class, String, Class, String, Class, String)}
         * constructor that allows annotation configuration per resolvable attribute
         */
        @Deprecated(since = "5.1.0")
        public AnnotationSpecification(Class<? extends Annotation> annotation,
                                       String nameAttribute,
                                       String versionAttribute,
                                       @Nullable String namespaceAttribute) {
            this(annotation, nameAttribute, annotation, versionAttribute, annotation, namespaceAttribute);
        }
    }
}
