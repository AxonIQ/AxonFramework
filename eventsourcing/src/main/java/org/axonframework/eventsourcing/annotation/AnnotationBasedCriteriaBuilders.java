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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Shared, single-pass scan of an {@link EventSourcedEntity}-annotated class for {@link EventCriteriaBuilder},
 * {@link SourcingCriteriaBuilder}, and {@link AppendCriteriaBuilder} methods, resolving {@link EventCriteria} for
 * sourcing and for appending with independent precedence:
 * <ol>
 *     <li>Sourcing: {@link SourcingCriteriaBuilder} -> {@link EventCriteriaBuilder} -> tag-based fallback.</li>
 *     <li>Appending: {@link AppendCriteriaBuilder} -> {@link EventCriteriaBuilder} -> tag-based fallback.</li>
 * </ol>
 * Backs both {@link AnnotationBasedSourcingCriteriaResolver} and {@link AnnotationBasedAppendCriteriaResolver}.
 *
 * @param <E>  The type of the entity to create.
 * @param <ID> The type of the identifier of the entity to create.
 * @author Mateusz Nowak
 * @since 5.3.0
 */
final class AnnotationBasedCriteriaBuilders<E, ID> {

    private final Configuration configuration;
    private final Class<E> entityType;
    private final String tagKey;

    private final Map<Class<?>, WrappedCriteriaBuilderMethod> sourcingBuilders;
    private final Map<Class<?>, WrappedCriteriaBuilderMethod> appendBuilders;
    private final Map<Class<?>, WrappedCriteriaBuilderMethod> sharedBuilders;

    AnnotationBasedCriteriaBuilders(Class<E> entityType, Class<ID> idType, Configuration configuration) {
        this.entityType = requireNonNull(entityType, "The entity type cannot be null.");
        requireNonNull(idType, "The id type cannot be null.");
        this.configuration = requireNonNull(configuration, "The configuration cannot be null.");

        Map<String, Object> attributes = AnnotationUtils
                .findAnnotationAttributes(entityType, EventSourcedEntity.class)
                .orElseThrow(() -> new IllegalArgumentException("The given class is not an @EventSourcedEntity"));
        String annotationTagKey = (String) attributes.get("tagKey");
        this.tagKey = annotationTagKey.isEmpty() ? null : annotationTagKey;

        List<Method> methods = Arrays.stream(entityType.getDeclaredMethods()).toList();
        validateAtMostOneCriteriaBuilderAnnotation(methods);

        this.sourcingBuilders = groupByIdentifierType(methods, SourcingCriteriaBuilder.class,
                                                       "@SourcingCriteriaBuilder", false);
        this.appendBuilders = groupByIdentifierType(methods, AppendCriteriaBuilder.class,
                                                     "@AppendCriteriaBuilder", true);
        this.sharedBuilders = groupByIdentifierType(methods, EventCriteriaBuilder.class,
                                                     "@EventCriteriaBuilder", false);
    }

    EventCriteria resolveSourcing(Object id, ProcessingContext context) {
        WrappedCriteriaBuilderMethod method = findMatching(sourcingBuilders, id);
        if (method == null) {
            method = findMatching(sharedBuilders, id);
        }
        return method != null ? method.invoke(id, context, null) : fallback(id);
    }

    EventCriteria resolveAppend(Object id, ProcessingContext context) {
        WrappedCriteriaBuilderMethod method = findMatching(appendBuilders, id);
        if (method == null) {
            method = findMatching(sharedBuilders, id);
        }
        if (method == null) {
            return fallback(id);
        }
        return method.invoke(id, context, () -> resolveSourcing(id, context));
    }

    Map<Class<?>, WrappedCriteriaBuilderMethod> sourcingBuilders() {
        return sourcingBuilders;
    }

    Map<Class<?>, WrappedCriteriaBuilderMethod> appendBuilders() {
        return appendBuilders;
    }

    Map<Class<?>, WrappedCriteriaBuilderMethod> sharedBuilders() {
        return sharedBuilders;
    }

    private EventCriteria fallback(Object id) {
        String key = Objects.requireNonNullElseGet(tagKey, entityType::getSimpleName);
        return EventCriteria.havingTags(Tag.of(key, id.toString()));
    }

    private static WrappedCriteriaBuilderMethod findMatching(Map<Class<?>, WrappedCriteriaBuilderMethod> map,
                                                              Object id) {
        return map.keySet().stream()
                  .filter(c -> c.isInstance(id))
                  .findFirst()
                  .map(map::get)
                  .orElse(null);
    }

    private static void validateAtMostOneCriteriaBuilderAnnotation(List<Method> methods) {
        for (Method method : methods) {
            long annotationCount = Stream
                    .of(EventCriteriaBuilder.class, SourcingCriteriaBuilder.class, AppendCriteriaBuilder.class)
                    .filter(method::isAnnotationPresent)
                    .count();
            if (annotationCount > 1) {
                throw new IllegalArgumentException(
                        "Method must not be annotated with more than one of @EventCriteriaBuilder, "
                                + "@SourcingCriteriaBuilder, @AppendCriteriaBuilder. Violating method: %s".formatted(
                                        ReflectionUtils.toDiscernibleSignature(method)));
            }
        }
    }

    private <A extends Annotation> Map<Class<?>, WrappedCriteriaBuilderMethod> groupByIdentifierType(
            List<Method> methods,
            Class<A> annotationType,
            String annotationLabel,
            boolean allowSourcingCriteriaParameter
    ) {
        Map<Class<?>, List<WrappedCriteriaBuilderMethod>> grouped = methods.stream()
                .filter(m -> m.isAnnotationPresent(annotationType))
                .map(m -> new WrappedCriteriaBuilderMethod(m, annotationLabel, allowSourcingCriteriaParameter,
                                                            configuration))
                .collect(Collectors.groupingBy(WrappedCriteriaBuilderMethod::identifierType));

        grouped.entrySet().stream()
               .filter(entry -> entry.getValue().size() > 1)
               .findAny()
               .ifPresent(entry -> {
                   throw new IllegalArgumentException(
                           "Multiple %s methods found with the same parameter type: %s".formatted(
                                   annotationLabel,
                                   entry.getValue()
                                        .stream()
                                        .map(w -> ReflectionUtils.toDiscernibleSignature(w.method))
                                        .sorted()
                                        .collect(Collectors.joining(", "))));
               });

        return grouped.entrySet().stream()
                      .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFirst()));
    }

    /**
     * Wraps a method annotated with {@link EventCriteriaBuilder}, {@link SourcingCriteriaBuilder}, or
     * {@link AppendCriteriaBuilder}, validating it and pre-resolving its {@link Configuration}-sourced parameters.
     */
    private static final class WrappedCriteriaBuilderMethod {

        private final Method method;
        private final String annotationLabel;
        private final Class<?> identifierType;
        private final ArgKind[] argKinds;
        private final Object[] staticArgValues;

        private WrappedCriteriaBuilderMethod(Method method, String annotationLabel,
                                             boolean allowSourcingCriteriaParameter, Configuration configuration) {
            this.annotationLabel = annotationLabel;
            if (!EventCriteria.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException(
                        "Method annotated with %s must return an EventCriteria. Violating method: %s".formatted(
                                annotationLabel, ReflectionUtils.toDiscernibleSignature(method)));
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException(
                        "Method annotated with %s must be static. Violating method: %s".formatted(
                                annotationLabel, ReflectionUtils.toDiscernibleSignature(method)));
            }
            if (method.getParameterCount() == 0) {
                throw new IllegalArgumentException(
                        ("Method annotated with %s must have at least one parameter which is the identifier. "
                                + "Violating method: %s").formatted(
                                annotationLabel, ReflectionUtils.toDiscernibleSignature(method)));
            }
            this.method = ReflectionUtils.ensureAccessible(method);
            this.identifierType = method.getParameterTypes()[0];

            int extraCount = method.getParameterCount() - 1;
            this.argKinds = new ArgKind[extraCount];
            this.staticArgValues = new Object[extraCount];
            int sourcingCriteriaParameterCount = 0;

            for (int i = 0; i < extraCount; i++) {
                Class<?> parameterType = method.getParameterTypes()[i + 1];

                if (parameterType == Configuration.class) {
                    argKinds[i] = ArgKind.CONFIGURATION;
                    staticArgValues[i] = configuration;
                } else if (parameterType == ProcessingContext.class) {
                    argKinds[i] = ArgKind.PROCESSING_CONTEXT;
                } else if (allowSourcingCriteriaParameter && parameterType == EventCriteria.class) {
                    sourcingCriteriaParameterCount++;
                    if (sourcingCriteriaParameterCount > 1) {
                        throw new IllegalArgumentException(
                                ("Method annotated with %s must declare at most one EventCriteria parameter. "
                                        + "Violating method: %s").formatted(
                                        annotationLabel, ReflectionUtils.toDiscernibleSignature(method)));
                    }
                    argKinds[i] = ArgKind.SOURCING_CRITERIA;
                } else {
                    Optional<?> component = configuration.getOptionalComponent(parameterType);
                    if (component.isEmpty()) {
                        throw new IllegalArgumentException(
                                ("Method annotated with %s declared a parameter which is not a component: %s. "
                                        + "Violating method: %s").formatted(
                                        annotationLabel, parameterType.getName(),
                                        ReflectionUtils.toDiscernibleSignature(method)));
                    }
                    argKinds[i] = ArgKind.COMPONENT;
                    staticArgValues[i] = component.get();
                }
            }
        }

        private EventCriteria invoke(Object id, ProcessingContext context,
                                     Supplier<EventCriteria> sourcingCriteriaSupplier) {
            Object[] args = new Object[method.getParameterCount()];
            args[0] = id;
            for (int i = 0; i < argKinds.length; i++) {
                args[i + 1] = switch (argKinds[i]) {
                    case CONFIGURATION, COMPONENT -> staticArgValues[i];
                    case PROCESSING_CONTEXT -> context;
                    case SOURCING_CRITERIA -> requireNonNull(
                            sourcingCriteriaSupplier,
                            "The sourcingCriteriaSupplier cannot be null when a SOURCING_CRITERIA parameter is declared."
                    ).get();
                };
            }
            try {
                Object result = method.invoke(null, args);
                if (!(result instanceof EventCriteria criteria)) {
                    throw new IllegalArgumentException(
                            ("The %s method returned null. The method must return a non-null EventCriteria. "
                                    + "Violating method: %s").formatted(
                                    annotationLabel, ReflectionUtils.toDiscernibleSignature(method)));
                }
                return criteria;
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new IllegalArgumentException("Error invoking " + annotationLabel + " method", e);
            }
        }

        private Class<?> identifierType() {
            return identifierType;
        }

        @Override
        public String toString() {
            return ReflectionUtils.toDiscernibleSignature(method);
        }
    }

    private enum ArgKind {
        CONFIGURATION,
        COMPONENT,
        PROCESSING_CONTEXT,
        SOURCING_CRITERIA
    }
}
