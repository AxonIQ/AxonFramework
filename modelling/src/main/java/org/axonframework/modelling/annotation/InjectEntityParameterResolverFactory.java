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

package org.axonframework.modelling.annotation;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.nullability.NullabilityResolver;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.PropertyBasedEntityIdResolver;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.ManagedEntity;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.ConstructorUtils.getConstructorFunctionWithZeroArguments;

/**
 * {@link ParameterResolverFactory} implementation that provides {@link ParameterResolver ParameterResolvers} for
 * parameters annotated with {@link InjectEntity}.
 * <p>
 * The parameter can either be a {@link ManagedEntity} or the entity itself, optionally wrapped in an
 * {@link Optional} (for example {@code Optional<MyEntity>} or {@code Optional<ManagedEntity<ID, MyEntity>>}). The
 * order of resolving the identity id is as specified on the {@link InjectEntity} annotation.
 *
 * @author Mitchell Herrijgers
 * @see InjectEntity
 * @since 5.0.0
 */
public class InjectEntityParameterResolverFactory implements ParameterResolverFactory {

    private final Configuration configuration;

    /**
     * Initialize the factory with the given {@code configuration}. The {@code configuration} should contain a
     * {@link org.axonframework.modelling.StateManager} to load entities from.
     * <p>
     * This constructor depends on the {@link Configuration} instead of the {@link StateManager} to prevent circular
     * dependencies during creation of message handlers. For example, if the repository uses an annotation-based event
     * state applier, it would construct methods, which would then require the {@link StateManager} to be created during
     * the construction of the parameter resolvers. This would lead to a circular dependency.
     *
     * @param configuration the {@link Configuration} to use for loading entities
     */
    public InjectEntityParameterResolverFactory(Configuration configuration) {
        this.configuration = requireNonNull(configuration, "The Configuration is required");
    }

    @Nullable
    @Override
    public ParameterResolver<?> createInstance(Executable executable,
                                               Parameter[] parameters,
                                               int parameterIndex) {
        Parameter parameter = parameters[parameterIndex];
        if (!parameter.isAnnotationPresent(InjectEntity.class)) {
            return null;
        }

        InjectEntity annotation = parameter.getAnnotation(InjectEntity.class);
        boolean isOptional = Optional.class.equals(parameter.getType());

        EntityTypeInfo entityTypeInfo = getEntityTypeInfo(isOptional, parameter, executable);
        EntityIdResolver<?> entityIdResolver = getEntityIdResolver(annotation);

        return new InjectEntityParameterResolver(
                configuration,
                entityTypeInfo.entityClass(),
                entityIdResolver,
                entityTypeInfo.managedEntity(),
                missingEntityStrategyFor(isOptional, parameter)
        );
    }

    /**
     * Decides how the resolver should react to a missing entity, based on how the {@code parameter} is declared.
     * <p>
     * An {@link Optional}-typed parameter resolves to {@link Optional#empty()}, a parameter declared as accepting
     * {@code null} resolves to {@code null}, and anything else fails the message being handled. Nullability is
     * determined through the {@link NullabilityResolver} chain, so languages that do not express it through a
     * runtime-visible annotation, such as Kotlin, are honored as well.
     *
     * @param isOptional whether the parameter is declared as an {@link Optional}
     * @param parameter  the {@link InjectEntity} annotated parameter being resolved
     * @return the strategy to apply when the entity to inject cannot be found
     */
    private static InjectEntityParameterResolver.MissingEntityStrategy missingEntityStrategyFor(boolean isOptional,
                                                                                                Parameter parameter) {
        if (isOptional) {
            return InjectEntityParameterResolver.MissingEntityStrategy.RESOLVE_OPTIONAL;
        }
        return NullabilityResolver.isNullable(parameter)
                ? InjectEntityParameterResolver.MissingEntityStrategy.RESOLVE_NULL
                : InjectEntityParameterResolver.MissingEntityStrategy.FAIL;
    }

    private static EntityTypeInfo getEntityTypeInfo(boolean isOptional, Parameter parameter, Executable executable) {
        Type entityType = getEntityType(isOptional, parameter, executable);
        if (entityType instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> rawType
                && ManagedEntity.class.isAssignableFrom(rawType)) {
            return new EntityTypeInfo((Class<?>) parameterizedType.getActualTypeArguments()[1], true);
        }
        if (entityType instanceof Class<?> entityClass) {
            if (ManagedEntity.class.isAssignableFrom(entityClass)) {
                throw new AxonConfigurationException(
                        ("Cannot inject entity for parameter [%s] of [%s]: a raw ManagedEntity does not specify its "
                                + "entity type. Use ManagedEntity<ID, MyEntity> instead.")
                                .formatted(parameter.getName(), executable)
                );
            }
            return new EntityTypeInfo(entityClass, false);
        }
        throw new AxonConfigurationException(
                "Cannot inject entity for parameter [%s] of [%s]: unsupported parameter type [%s]."
                        .formatted(parameter.getName(), executable, entityType)
        );
    }

    private static Type getEntityType(boolean isOptional, Parameter parameter, Executable executable) {
        Type type;
        if (isOptional) {
            Type parameterizedType = parameter.getParameterizedType();
            if (!(parameterizedType instanceof ParameterizedType)) {
                throw new AxonConfigurationException(
                        ("Cannot inject entity for parameter [%s] of [%s]: a raw Optional does not specify the "
                                + "entity type. Use Optional<MyEntity> or Optional<ManagedEntity<ID, MyEntity>> "
                                + "instead.").formatted(parameter.getName(), executable)
                );
            }
            type = ReflectionUtils.unwrapIfType(parameterizedType, Optional.class);
        } else {
            type = parameter.getParameterizedType();
        }
        return type;
    }

    private static EntityIdResolver<?> getEntityIdResolver(InjectEntity annotation) {
        if (annotation.idProperty() != null && !annotation.idProperty().isEmpty()) {
            return new PropertyBasedEntityIdResolver(annotation.idProperty());
        }
        try {
            return getConstructorFunctionWithZeroArguments(annotation.idResolver()).get();
        } catch (Exception e) {
            throw new AxonConfigurationException(
                    "Failed to instantiate id resolver: " + annotation.idResolver().getName(), e
            );
        }
    }

    /**
     * The resolved entity type of an {@link InjectEntity} annotated parameter, and whether it should be loaded as a
     * {@link ManagedEntity}.
     *
     * @param entityClass   the type of the entity to load
     * @param managedEntity whether the entity should be loaded as a {@link ManagedEntity}
     */
    private record EntityTypeInfo(Class<?> entityClass, boolean managedEntity) {

    }
}
