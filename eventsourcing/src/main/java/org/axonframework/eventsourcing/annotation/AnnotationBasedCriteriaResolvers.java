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

import jakarta.annotation.Nonnull;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.axonframework.common.ConstructorUtils.getConstructorFunctionWithZeroArguments;

/**
 * Factory class that creates separate {@link CriteriaResolver} instances for sourcing (loading events) and
 * appending (consistency checking) based on annotations present on the entity class.
 * <p>
 * This class enables Dynamic Consistency Boundaries (DCB) where the criteria used for sourcing events
 * can differ from the criteria used for consistency checking when appending events.
 * <p>
 * <b>Annotation Precedence Rules:</b>
 * <ul>
 *   <li><b>First check:</b> If {@link EventSourcedEntity#criteriaResolverDefinition()} specifies a custom
 *       {@link CriteriaResolverDefinition}, use it for both source and append (backward compatibility).</li>
 *   <li><b>For Sourcing:</b>
 *     <ol>
 *       <li>{@link SourceCriteriaBuilder} present → use it</li>
 *       <li>Else {@link EventCriteriaBuilder} present → use it</li>
 *       <li>Else → tag-based fallback (existing behavior)</li>
 *     </ol>
 *   </li>
 *   <li><b>For Appending:</b>
 *     <ol>
 *       <li>{@link AppendCriteriaBuilder} present → use it</li>
 *       <li>Else {@link EventCriteriaBuilder} present → use it</li>
 *       <li>Else → tag-based fallback (existing behavior)</li>
 *     </ol>
 *   </li>
 * </ul>
 * <p>
 * <b>Note:</b> {@link AppendCriteriaBuilder} is allowed standalone (uses tag-based fallback for sourcing).
 * <p>
 * <b>Example - Accounting Use Case:</b>
 * <pre>{@code
 * @EventSourcedEntity(tagKey = "accountId")
 * public class Account {
 *
 *     // Source: Load CreditsIncreased AND CreditsDecreased to calculate balance
 *     @SourceCriteriaBuilder
 *     public static EventCriteria sourceCriteria(String accountId) {
 *         return EventCriteria
 *             .havingTags("accountId", accountId)
 *             .andBeingOneOfTypes("CreditsIncreased", "CreditsDecreased");
 *     }
 *
 *     // Append: Only check for conflicts on CreditsDecreased (allow concurrent increases)
 *     @AppendCriteriaBuilder
 *     public static EventCriteria appendCriteria(String accountId) {
 *         return EventCriteria
 *             .havingTags("accountId", accountId)
 *             .andBeingOneOfTypes("CreditsDecreased");
 *     }
 * }
 * }</pre>
 *
 * @param <E>  The type of the entity.
 * @param <ID> The type of the identifier of the entity.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @see SourceCriteriaBuilder
 * @see AppendCriteriaBuilder
 * @see EventCriteriaBuilder
 * @see CriteriaResolverDefinition
 * @since 5.0.0
 */
public class AnnotationBasedCriteriaResolvers<E, ID> {

    private final CriteriaResolver<ID> sourceCriteriaResolver;
    private final CriteriaResolver<ID> appendCriteriaResolver;

    /**
     * Creates a new instance of {@link AnnotationBasedCriteriaResolvers} for the given entity type.
     * <p>
     * This constructor first checks for a custom {@link CriteriaResolverDefinition} in the annotation.
     * If specified, it is used for both source and append criteria (for backward compatibility).
     * Otherwise, it scans the entity class for {@link SourceCriteriaBuilder}, {@link AppendCriteriaBuilder},
     * and {@link EventCriteriaBuilder} annotations to create the appropriate resolvers.
     *
     * @param entityType    The entity type to scan for criteria builder annotations.
     * @param idType        The identifier type for the entity.
     * @param configuration The configuration to use for resolving components.
     * @throws IllegalArgumentException If the entity type is not annotated with {@link EventSourcedEntity}.
     */
    @SuppressWarnings("unchecked")
    public AnnotationBasedCriteriaResolvers(@Nonnull Class<E> entityType,
                                            @Nonnull Class<ID> idType,
                                            @Nonnull Configuration configuration) {
        Objects.requireNonNull(entityType, "The entity type cannot be null.");
        Objects.requireNonNull(idType, "The id type cannot be null.");
        Objects.requireNonNull(configuration, "The configuration cannot be null.");

        Map<String, Object> attributes = AnnotationUtils
                .findAnnotationAttributes(entityType, EventSourcedEntity.class)
                .orElseThrow(() -> new IllegalArgumentException("The given class is not an @EventSourcedEntity"));

        // Check for custom CriteriaResolverDefinition first (backward compatibility)
        Class<CriteriaResolverDefinition> criteriaResolverDefinitionType =
                (Class<CriteriaResolverDefinition>) attributes.get("criteriaResolverDefinition");

        if (criteriaResolverDefinitionType != null &&
            !criteriaResolverDefinitionType.equals(AnnotationBasedEventCriteriaResolverDefinition.class)) {
            // Use the custom CriteriaResolverDefinition for both source and append
            CriteriaResolverDefinition definition = getConstructorFunctionWithZeroArguments(criteriaResolverDefinitionType).get();
            CriteriaResolver<ID> sharedResolver = definition.createEventCriteriaResolver(entityType, idType, configuration);
            this.sourceCriteriaResolver = sharedResolver;
            this.appendCriteriaResolver = sharedResolver;
            return;
        }

        // Otherwise, use annotation-based resolution
        String annotationTagKey = (String) attributes.get("tagKey");
        String tagKey = annotationTagKey.isEmpty() ? null : annotationTagKey;

        // Scan for annotated methods
        Map<Class<?>, WrappedCriteriaBuilderMethod> sourceMethods = scanForMethods(entityType, SourceCriteriaBuilder.class, configuration);
        Map<Class<?>, WrappedCriteriaBuilderMethod> appendMethods = scanForMethods(entityType, AppendCriteriaBuilder.class, configuration);
        Map<Class<?>, WrappedCriteriaBuilderMethod> genericMethods = scanForMethods(entityType, EventCriteriaBuilder.class, configuration);

        // Create source resolver with precedence: SourceCriteriaBuilder > EventCriteriaBuilder > tag fallback
        this.sourceCriteriaResolver = createResolver(sourceMethods, genericMethods, tagKey, entityType);

        // Create append resolver with precedence: AppendCriteriaBuilder > EventCriteriaBuilder > tag fallback
        this.appendCriteriaResolver = createResolver(appendMethods, genericMethods, tagKey, entityType);
    }

    /**
     * Returns the {@link CriteriaResolver} for sourcing (loading events).
     *
     * @return The criteria resolver for sourcing.
     */
    public CriteriaResolver<ID> sourceCriteriaResolver() {
        return sourceCriteriaResolver;
    }

    /**
     * Returns the {@link CriteriaResolver} for appending (consistency checking).
     *
     * @return The criteria resolver for appending.
     */
    public CriteriaResolver<ID> appendCriteriaResolver() {
        return appendCriteriaResolver;
    }

    private Map<Class<?>, WrappedCriteriaBuilderMethod> scanForMethods(Class<E> entityType,
                                                                        Class<? extends Annotation> annotationType,
                                                                        Configuration configuration) {
        var builders = Arrays
                .stream(entityType.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(annotationType))
                .map(m -> new WrappedCriteriaBuilderMethod(m, configuration, annotationType))
                .collect(Collectors.groupingBy(WrappedCriteriaBuilderMethod::getIdentifierType));

        builders.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .findAny()
                .ifPresent(list -> {
                    throw new IllegalArgumentException(
                            "Multiple @%s methods found with the same parameter type: %s".formatted(
                                    annotationType.getSimpleName(),
                                    list.getValue()
                                        .stream()
                                        .map(wv -> ReflectionUtils.toDiscernibleSignature(wv.getMethod()))
                                        .sorted()
                                        .collect(Collectors.joining(", "))));
                });

        return builders.entrySet().stream()
                       .collect(Collectors.toMap(Map.Entry::getKey, m -> m.getValue().getFirst()));
    }

    private CriteriaResolver<ID> createResolver(Map<Class<?>, WrappedCriteriaBuilderMethod> primaryMethods,
                                                Map<Class<?>, WrappedCriteriaBuilderMethod> fallbackMethods,
                                                String tagKey,
                                                Class<E> entityType) {
        return (id, context) -> {
            // Try primary methods first
            Optional<Object> primaryResult = primaryMethods
                    .keySet()
                    .stream()
                    .filter(c -> c.isInstance(id))
                    .findFirst()
                    .map(primaryMethods::get)
                    .map(m -> m.resolve(id));

            if (primaryResult.isPresent()) {
                return (EventCriteria) primaryResult.get();
            }

            // Try fallback methods (EventCriteriaBuilder)
            Optional<Object> fallbackResult = fallbackMethods
                    .keySet()
                    .stream()
                    .filter(c -> c.isInstance(id))
                    .findFirst()
                    .map(fallbackMethods::get)
                    .map(m -> m.resolve(id));

            if (fallbackResult.isPresent()) {
                return (EventCriteria) fallbackResult.get();
            }

            // Tag-based fallback
            String key = Objects.requireNonNullElseGet(tagKey, entityType::getSimpleName);
            return EventCriteria.havingTags(Tag.of(key, id.toString()));
        };
    }

    /**
     * Wraps a method that is annotated with a criteria builder annotation and returns an {@link EventCriteria}.
     * Validates that the method is valid, and retrieves the components from the configuration that are requested.
     */
    private static class WrappedCriteriaBuilderMethod {

        private final Method method;
        private final Class<?> identifierType;
        private final Object[] optionalArgumentSuppliers;

        private WrappedCriteriaBuilderMethod(Method method, Configuration configuration, Class<? extends Annotation> annotationType) {
            if (!EventCriteria.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException(
                        "Method annotated with @%s must return an EventCriteria. Violating method: %s".formatted(
                                annotationType.getSimpleName(),
                                ReflectionUtils.toDiscernibleSignature(method)));
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException(
                        "Method annotated with @%s must be static. Violating method: %s".formatted(
                                annotationType.getSimpleName(),
                                ReflectionUtils.toDiscernibleSignature(method)));
            }
            if (method.getParameterCount() == 0) {
                throw new IllegalArgumentException(
                        "Method annotated with @%s must have at least one parameter which is the identifier. Violating method: %s".formatted(
                                annotationType.getSimpleName(),
                                ReflectionUtils.toDiscernibleSignature(method)));
            }
            this.method = ReflectionUtils.ensureAccessible(method);
            // Let's determine the identifier and create the argument suppliers
            this.identifierType = method.getParameterTypes()[0];
            int optionalParameterCount = method.getParameterCount() - 1;
            this.optionalArgumentSuppliers = new Object[optionalParameterCount];

            // And other arguments which can come from the configuration
            for (int i = 0; i < optionalParameterCount; i++) {
                Class<?> parameterType = method.getParameterTypes()[i + 1];

                // The whole configuration can be passed in
                if (parameterType == Configuration.class) {
                    optionalArgumentSuppliers[i] = configuration;
                    continue;
                }
                // Or a specific component of the configuration
                Optional<?> component = configuration.getOptionalComponent(parameterType);
                if (component.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Method annotated with @%s declared a parameter which is not a component: %s. Violating method: %s".formatted(
                                    method.getAnnotations()[0].annotationType().getSimpleName(),
                                    parameterType.getName(),
                                    ReflectionUtils.toDiscernibleSignature(method)
                            ));
                }
                optionalArgumentSuppliers[i] = component.get();
            }
        }

        private Object resolve(Object id) {
            Object[] args = new Object[method.getParameterCount()];
            args[0] = id;
            System.arraycopy(optionalArgumentSuppliers, 0, args, 1, optionalArgumentSuppliers.length);
            try {
                Object result = method.invoke(null, args);
                if (!(result instanceof EventCriteria)) {
                    throw new IllegalArgumentException(
                            "The criteria builder method returned null. The method must return a non-null EventCriteria. Violating method: %s".formatted(
                                    ReflectionUtils.toDiscernibleSignature(method)));
                }
                return result;
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new IllegalArgumentException("Error invoking criteria builder method", e);
            }
        }

        public Class<?> getIdentifierType() {
            return identifierType;
        }

        public Method getMethod() {
            return method;
        }
    }
}
