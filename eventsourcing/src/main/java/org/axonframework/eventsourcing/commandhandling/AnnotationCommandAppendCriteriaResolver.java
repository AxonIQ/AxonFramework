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

package org.axonframework.eventsourcing.commandhandling;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.CommandAppendCriteriaResolver;
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.modelling.annotation.InjectEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Synchronous annotation adapter for a class-level append-criteria builder.
 */
final class AnnotationCommandAppendCriteriaResolver implements CommandAppendCriteriaResolver {

    private final BuilderMethod builder;

    private AnnotationCommandAppendCriteriaResolver(BuilderMethod builder) {
        this.builder = builder;
    }

    static Optional<AnnotationCommandAppendCriteriaResolver> inspect(Class<?> declaringType,
                                                                     Configuration configuration) {
        List<Method> builders = new ArrayList<>();
        for (Method method : declaringType.getDeclaredMethods()) {
            if (method.isAnnotationPresent(AppendCriteriaBuilder.class)) {
                builders.add(method);
            }
        }
        if (builders.isEmpty()) {
            return Optional.empty();
        }
        if (builders.size() > 1) {
            throw new IllegalArgumentException(
                    "Command-handling class [%s] declares more than one @AppendCriteriaBuilder: %s"
                            .formatted(declaringType.getName(), builders.stream()
                                                                      .map(ReflectionUtils::toDiscernibleSignature)
                                                                      .toList())
            );
        }
        BuilderMethod builder = new BuilderMethod(builders.getFirst(), configuration);
        validateCompleteCoverage(declaringType, builder);
        return Optional.of(new AnnotationCommandAppendCriteriaResolver(builder));
    }

    private static void validateCompleteCoverage(Class<?> declaringType, BuilderMethod builder) {
        Set<Class<?>> commandPayloadTypes = new HashSet<>();
        for (Method method : ReflectionUtils.methodsOf(declaringType)) {
            Optional<Map<String, Object>> attributes =
                    AnnotationUtils.findAnnotationAttributes(method, CommandHandler.class);
            if (attributes.isEmpty()) {
                continue;
            }
            Class<?> payloadType = (Class<?>) attributes.get().get("payloadType");
            if (payloadType == Object.class && method.getParameterCount() > 0) {
                Class<?> firstParameter = method.getParameterTypes()[0];
                payloadType = firstParameter == CommandMessage.class ? Object.class : firstParameter;
            }
            commandPayloadTypes.add(payloadType);
        }
        if (commandPayloadTypes.isEmpty()) {
            throw invalid(builder.method, "must be declared on a class containing at least one @CommandHandler");
        }
        for (Class<?> payloadType : commandPayloadTypes) {
            if (!builder.accepts(payloadType)) {
                throw invalid(
                        builder.method,
                        "declares command parameter [%s], which cannot accept handled command payload [%s]"
                                .formatted(builder.commandType.getName(), payloadType.getName())
                );
            }
        }
    }

    @Override
    public EventCriteria resolve(CommandMessage command,
                                 ProcessingContext context,
                                 EventCriteria sourcingCriteria) {
        return builder.resolve(command, context, sourcingCriteria);
    }

    private static IllegalArgumentException invalid(Method method, String reason) {
        return new IllegalArgumentException(
                "@AppendCriteriaBuilder method %s. Violating method: %s"
                        .formatted(reason, ReflectionUtils.toDiscernibleSignature(method))
        );
    }

    private static final class BuilderMethod {

        private final Method method;
        private final Class<?> commandType;
        private final ArgumentResolver[] argumentResolvers;

        private BuilderMethod(Method method, Configuration configuration) {
            validateMethod(method);
            this.method = ReflectionUtils.ensureAccessible(method);
            this.commandType = method.getParameterTypes()[0];
            this.argumentResolvers = new ArgumentResolver[method.getParameterCount()];
            argumentResolvers[0] = commandType == CommandMessage.class
                    ? (command, context, sourcingCriteria) -> command
                    : (command, context, sourcingCriteria) -> {
                        if (!commandType.isInstance(command.payload())) {
                            throw new IllegalArgumentException(
                                    "Command payload [%s] is not assignable to @AppendCriteriaBuilder parameter [%s]."
                                            .formatted(command.payloadType().getName(), commandType.getName())
                            );
                        }
                        return command.payload();
                    };
            Parameter[] parameters = method.getParameters();
            for (int i = 1; i < parameters.length; i++) {
                argumentResolvers[i] = argumentResolver(parameters[i], configuration, method);
            }
        }

        private static void validateMethod(Method method) {
            if (!Modifier.isStatic(method.getModifiers())) {
                throw invalid(method, "must be static");
            }
            if (!EventCriteria.class.isAssignableFrom(method.getReturnType())) {
                throw invalid(method, "must return EventCriteria");
            }
            if (method.getParameterCount() == 0) {
                throw invalid(method, "must declare a command payload or CommandMessage as its first parameter");
            }
            Class<?> firstParameter = method.getParameterTypes()[0];
            if (Message.class.isAssignableFrom(firstParameter) && firstParameter != CommandMessage.class) {
                throw invalid(method, "first parameter must be a command payload type or CommandMessage");
            }
        }

        private static ArgumentResolver argumentResolver(Parameter parameter,
                                                         Configuration configuration,
                                                         Method method) {
            Optional<Map<String, Object>> metadataValue =
                    AnnotationUtils.findAnnotationAttributes(parameter, MetadataValue.class);
            if (metadataValue.isPresent()) {
                String key = metadataValue.get().get("metadataValue").toString();
                boolean required = parameter.getType().isPrimitive()
                        || (boolean) metadataValue.get().get("required");
                return (command, context, sourcingCriteria) -> {
                    Object value = command.metadata().get(key);
                    if (value == null && required) {
                        throw new IllegalArgumentException(
                                "Required metadata value [%s] is missing for @AppendCriteriaBuilder method [%s]."
                                        .formatted(key, ReflectionUtils.toDiscernibleSignature(method))
                        );
                    }
                    if (value != null && !boxed(parameter.getType()).isInstance(value)) {
                        throw new IllegalArgumentException(
                                "Metadata value [%s] is not assignable to parameter type [%s] on method [%s]."
                                        .formatted(key, parameter.getType().getName(),
                                                   ReflectionUtils.toDiscernibleSignature(method))
                        );
                    }
                    return value;
                };
            }
            Class<?> parameterType = parameter.getType();
            if (parameterType == EventCriteria.class) {
                return (command, context, sourcingCriteria) -> sourcingCriteria;
            }
            if (parameterType == CommandMessage.class) {
                return (command, context, sourcingCriteria) -> command;
            }
            if (parameterType == Metadata.class) {
                return (command, context, sourcingCriteria) -> command.metadata();
            }
            if (parameterType == ProcessingContext.class) {
                return (command, context, sourcingCriteria) -> context;
            }
            if (parameterType == Configuration.class) {
                return (command, context, sourcingCriteria) -> configuration;
            }
            if (parameter.isAnnotationPresent(InjectEntity.class)
                    || parameterType == EventAppender.class
                    || parameterType == CommandDispatcher.class
                    || parameterType == CommandGateway.class
                    || parameterType == CommandBus.class
                    || parameterType == QueryGateway.class
                    || parameterType == QueryBus.class) {
                throw invalid(method, "declares unsupported parameter type [%s]".formatted(parameterType.getName()));
            }
            Object component = configuration.getOptionalComponent(parameterType)
                                            .orElseThrow(() -> invalid(
                                                    method,
                                                    "declares unsupported parameter type [%s]"
                                                            .formatted(parameterType.getName())
                                            ));
            return (command, context, sourcingCriteria) -> component;
        }

        private static Class<?> boxed(Class<?> type) {
            return type.isPrimitive() ? ReflectionUtils.resolvePrimitiveWrapperType(type) : type;
        }

        private boolean accepts(Class<?> payloadType) {
            if (payloadType == Object.class) {
                return commandType == CommandMessage.class;
            }
            return commandType == CommandMessage.class || commandType.isAssignableFrom(payloadType);
        }

        private EventCriteria resolve(CommandMessage command,
                                      ProcessingContext context,
                                      EventCriteria sourcingCriteria) {
            Object[] arguments = new Object[argumentResolvers.length];
            for (int i = 0; i < argumentResolvers.length; i++) {
                arguments[i] = argumentResolvers[i].resolve(command, context, sourcingCriteria);
            }
            try {
                Object result = method.invoke(null, arguments);
                if (!(result instanceof EventCriteria criteria)) {
                    throw invalid(method, "returned null; it must return a non-null EventCriteria");
                }
                return criteria;
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Cannot invoke @AppendCriteriaBuilder method.", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalArgumentException("Error invoking @AppendCriteriaBuilder method.", cause);
            }
        }
    }

    @FunctionalInterface
    private interface ArgumentResolver {

        Object resolve(CommandMessage command, ProcessingContext context, EventCriteria sourcingCriteria);
    }
}
