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
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts an {@link AppendCriteriaBuilder} method to the {@link AppendCriteriaResolver} contract.
 * <p>
 * Resolution is synchronous, matching the resolver contract: the builder's parameters come from the command, the
 * accumulated sourcing criteria, and the {@link Configuration}, none of which require awaiting. The command is passed
 * in explicitly rather than read from the {@link ProcessingContext}, because the resolver runs when the append
 * condition is finalized rather than while the handler is invoked.
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
final class AnnotationBasedAppendCriteriaResolver implements AppendCriteriaResolver {

    private final BuilderMethod builder;

    private AnnotationBasedAppendCriteriaResolver(BuilderMethod builder) {
        this.builder = builder;
    }

    /**
     * Resolves, for every command handler declared on {@code declaringType}, the builder that applies to it.
     * <p>
     * The builders visible to {@code declaringType} are the ones it declares and the ones its superclasses declare,
     * one per command type. When a class and one of its superclasses both declare a builder for the same command type,
     * the closest one wins. Interfaces are not searched, as a builder is required to be {@code static} and static
     * interface methods are not inherited.
     * <p>
     * A handler is matched with the builder whose command type is the most specific supertype of the handled payload. A
     * builder accepting {@link CommandMessage} applies to handlers no payload-typed builder accepts. When builders are
     * visible, every declared handler has to be matched by one, so a command cannot silently keep its sourcing criteria
     * because its builder was forgotten.
     * <p>
     * Only handlers <b>declared</b> on {@code declaringType} are resolved, matching the scope in which the builder is
     * applied: a handler inherited from a supertype is created against that supertype as its declaring type, and so is
     * resolved against the builders visible to that supertype.
     *
     * @return the resolver per declared command handler, empty when no builder is visible to {@code declaringType}
     */
    static Map<Method, AnnotationBasedAppendCriteriaResolver> inspect(Class<?> declaringType,
                                                                      Configuration configuration) {
        Map<Class<?>, BuilderMethod> builders = visibleBuilders(declaringType, configuration);
        if (builders.isEmpty()) {
            return Map.of();
        }
        Map<BuilderMethod, AnnotationBasedAppendCriteriaResolver> resolvers = new HashMap<>();
        Map<Method, AnnotationBasedAppendCriteriaResolver> resolverPerHandler = new HashMap<>();
        for (Method handler : declaringType.getDeclaredMethods()) {
            Optional<Class<?>> payloadType = handledPayloadType(handler);
            if (payloadType.isEmpty()) {
                continue;
            }
            BuilderMethod builder = select(payloadType.get(), builders.values())
                    .orElseThrow(() -> uncovered(payloadType.get(), declaringType, builders.values()));
            resolverPerHandler.put(
                    handler, resolvers.computeIfAbsent(builder, AnnotationBasedAppendCriteriaResolver::new)
            );
        }
        return resolverPerHandler;
    }

    /**
     * Collects the builders declared on {@code declaringType} and its superclasses, keyed by command type, with the
     * closest declaration winning per type.
     */
    private static Map<Class<?>, BuilderMethod> visibleBuilders(Class<?> declaringType, Configuration configuration) {
        Map<Class<?>, BuilderMethod> builders = new LinkedHashMap<>();
        for (Class<?> type = declaringType; type != null && type != Object.class; type = type.getSuperclass()) {
            Map<Class<?>, BuilderMethod> declared = new HashMap<>();
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(AppendCriteriaBuilder.class)) {
                    continue;
                }
                BuilderMethod builder = new BuilderMethod(method, configuration);
                BuilderMethod duplicate = declared.putIfAbsent(builder.commandType, builder);
                if (duplicate != null) {
                    throw new IllegalArgumentException(
                            ("Command-handling class [%s] declares more than one @AppendCriteriaBuilder for command "
                                    + "type [%s]: %s")
                                    .formatted(type.getName(),
                                               builder.commandType.getName(),
                                               List.of(ReflectionUtils.toDiscernibleSignature(duplicate.method),
                                                       ReflectionUtils.toDiscernibleSignature(builder.method)))
                    );
                }
            }
            declared.forEach(builders::putIfAbsent);
        }
        return builders;
    }

    /**
     * Returns the payload type a command-handling {@code method} declares, or an empty {@code Optional} when it is not
     * a command handler. A handler taking the {@link CommandMessage} itself declares {@link Object}.
     */
    private static Optional<Class<?>> handledPayloadType(Method method) {
        Optional<Map<String, Object>> attributes =
                AnnotationUtils.findAnnotationAttributes(method, CommandHandler.class);
        if (attributes.isEmpty()) {
            return Optional.empty();
        }
        Class<?> payloadType = (Class<?>) attributes.get().get("payloadType");
        if (payloadType == Object.class && method.getParameterCount() > 0) {
            Class<?> firstParameter = method.getParameterTypes()[0];
            payloadType = firstParameter == CommandMessage.class ? Object.class : firstParameter;
        }
        return Optional.of(payloadType);
    }

    /**
     * Selects the builder with the most specific command type accepting {@code payloadType}, falling back to a builder
     * accepting {@link CommandMessage}.
     */
    private static Optional<BuilderMethod> select(Class<?> payloadType, Collection<BuilderMethod> builders) {
        List<BuilderMethod> candidates = builders.stream()
                                                 .filter(builder -> builder.acceptsAsPayload(payloadType))
                                                 .toList();
        if (candidates.isEmpty()) {
            return builders.stream().filter(builder -> builder.commandType == CommandMessage.class).findFirst();
        }
        List<BuilderMethod> mostSpecific =
                candidates.stream()
                          .filter(candidate -> candidates.stream().allMatch(
                                  other -> other.commandType.isAssignableFrom(candidate.commandType)
                          ))
                          .toList();
        if (mostSpecific.size() != 1) {
            throw new IllegalArgumentException(
                    ("Command payload [%s] is accepted by several @AppendCriteriaBuilder methods, none of which is "
                            + "more specific than the others: %s. Declare a builder for [%s] itself.")
                            .formatted(payloadType.getName(), describe(candidates), payloadType.getName())
            );
        }
        return Optional.of(mostSpecific.getFirst());
    }

    private static IllegalArgumentException uncovered(Class<?> payloadType,
                                                      Class<?> declaringType,
                                                      Collection<BuilderMethod> builders) {
        return new IllegalArgumentException(
                ("No @AppendCriteriaBuilder accepts handled command payload [%s] declared by [%s]. Once a class has a "
                        + "builder, every command it handles needs one, so a command cannot silently keep its "
                        + "sourcing criteria. Builders visible to the class: %s")
                        .formatted(payloadType.getName(), declaringType.getName(), describe(builders))
        );
    }

    private static List<String> describe(Collection<BuilderMethod> builders) {
        return builders.stream()
                       .map(builder -> builder.method.getDeclaringClass().getName() + "#"
                               + ReflectionUtils.toDiscernibleSignature(builder.method))
                       .toList();
    }

    @Override
    public EventCriteria resolve(CommandMessage command,
                                 ProcessingContext context,
                                 EventCriteria sourcingCriteria) {
        return builder.resolve(command, context, sourcingCriteria);
    }

    private static IllegalArgumentException invalid(Method method, String reason) {
        return new IllegalArgumentException(
                "@AppendCriteriaBuilder method %s. Violating method: %s#%s"
                        .formatted(reason,
                                   method.getDeclaringClass().getName(),
                                   ReflectionUtils.toDiscernibleSignature(method))
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
            return componentResolver(parameterType, configuration, method);
        }

        /**
         * Resolves any remaining parameter as a configured component, rejecting whatever the {@link Configuration}
         * cannot supply.
         * <p>
         * This covers every type that is not injectable rather than enumerating the types that are not, so a parameter
         * that is meaningless to a criteria builder -- a gateway, an entity, an event appender -- is reported through
         * the same message as any other. A broad type such as {@code Object} matches several components and makes the
         * lookup itself fail; that detail is folded into the message instead of surfacing as a configuration error.
         */
        private static ArgumentResolver componentResolver(Class<?> parameterType,
                                                          Configuration configuration,
                                                          Method method) {
            Object component;
            try {
                component = configuration.getOptionalComponent(parameterType).orElse(null);
            } catch (RuntimeException e) {
                throw invalid(method, "declares unsupported parameter type [%s]: %s"
                        .formatted(parameterType.getName(), e.getMessage()));
            }
            if (component == null) {
                throw invalid(method,
                              "declares unsupported parameter type [%s]".formatted(parameterType.getName()));
            }
            Object resolved = component;
            return (command, context, sourcingCriteria) -> resolved;
        }

        private static Class<?> boxed(Class<?> type) {
            return type.isPrimitive() ? ReflectionUtils.resolvePrimitiveWrapperType(type) : type;
        }

        private boolean acceptsAsPayload(Class<?> payloadType) {
            return commandType != CommandMessage.class
                    && payloadType != Object.class
                    && commandType.isAssignableFrom(payloadType);
        }

        private EventCriteria resolve(CommandMessage command,
                                      ProcessingContext context,
                                      EventCriteria sourcingCriteria) {
            Object[] arguments = new Object[argumentResolvers.length];
            for (int i = 0; i < argumentResolvers.length; i++) {
                arguments[i] = argumentResolvers[i].resolve(command, context, sourcingCriteria);
            }
            try {
                // The return type is validated on construction, so null is the only value that is not EventCriteria.
                Object result = method.invoke(null, arguments);
                if (result == null) {
                    throw invalid(method, "returned null; it must return a non-null EventCriteria");
                }
                return (EventCriteria) result;
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
