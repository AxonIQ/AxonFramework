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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.messaging.commandhandling.CommandMessage;
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
     * Collects the builders that apply to the command handlers declared on {@code declaringType}.
     * <p>
     * The builders visible to {@code declaringType} are the ones it declares and the ones its superclasses declare,
     * one per command type. When a class and one of its superclasses both declare a builder for the same command type,
     * the closest one wins. Interfaces are not searched, as a builder is required to be {@code static} and static
     * interface methods are not inherited.
     * <p>
     * A handler inherited from a supertype is created against that supertype as its declaring type, and so is matched
     * against the builders visible to that supertype. {@link #rejectBuildersInheritedHandlersNeverUse} rejects
     * builders on {@code declaringType} that would appear to cover such a handler.
     *
     * @return the builders visible to {@code declaringType}, or an empty {@code Optional} when it has none
     * @throws AxonConfigurationException if a builder is invalid, or two builders on one class cover the same command
     *                                    type
     */
    static Optional<VisibleBuilders> inspect(Class<?> declaringType, Configuration configuration) {
        Map<Class<?>, BuilderMethod> builders = visibleBuilders(declaringType, configuration);
        return builders.isEmpty()
                ? Optional.empty()
                : Optional.of(new VisibleBuilders(declaringType, List.copyOf(builders.values())));
    }

    /**
     * Rejects builders that appear to cover a command handler {@code declaringType} inherits, but never run for it.
     * <p>
     * An inherited handler is matched with the builders visible to the class declaring it, so a builder declared on
     * {@code declaringType} (or on a class between it and the handler's declaring class) never applies to it. Such a
     * builder is rejected when it is the one that would be selected for the handler, were the handler declared on
     * {@code declaringType}, as the handler would otherwise silently keep the criteria of its own class.
     *
     * @param declaringType     the class whose builders are checked
     * @param inheritedHandlers the command handlers {@code declaringType} inherits and does not redeclare, mapped to
     *                          the payload type each of them handles
     * @param configuration     the configuration used to validate the builders
     * @throws AxonConfigurationException if a builder would be selected for an inherited handler it never applies to
     */
    static void rejectBuildersInheritedHandlersNeverUse(Class<?> declaringType,
                                                        Map<Method, Class<?>> inheritedHandlers,
                                                        Configuration configuration) {
        Collection<BuilderMethod> builders = visibleBuilders(declaringType, configuration).values();
        inheritedHandlers.forEach((handler, payloadType) -> select(payloadType, builders)
                .filter(builder -> !builder.method.getDeclaringClass().isAssignableFrom(handler.getDeclaringClass()))
                .ifPresent(builder -> {
                    throw invalid(builder.method, (
                            "never applies to the inherited command handler [%s#%s] for payload [%s], which is "
                                    + "matched with the builders of [%s] and its superclasses. Declare the builder "
                                    + "there, or redeclare the handler on [%s]"
                    ).formatted(handler.getDeclaringClass().getName(),
                                ReflectionUtils.toDiscernibleSignature(handler),
                                payloadType.getName(),
                                handler.getDeclaringClass().getName(),
                                declaringType.getName()));
                }));
    }

    /**
     * The builders visible to one command-handling class, matching each of its handlers with the builder that applies.
     */
    static final class VisibleBuilders {

        private final Class<?> declaringType;
        private final List<BuilderMethod> builders;

        private VisibleBuilders(Class<?> declaringType, List<BuilderMethod> builders) {
            this.declaringType = declaringType;
            this.builders = builders;
        }

        /**
         * Returns the resolver for a handler of the given {@code payloadType}: the builder whose command type is the
         * most specific supertype of the payload, or else a builder accepting {@link CommandMessage}. Every handler
         * has to be matched, so a command cannot silently keep its sourcing criteria because its builder was
         * forgotten.
         *
         * @param payloadType the payload type the handler effectively accepts
         * @return the resolver for that handler
         * @throws AxonConfigurationException if no builder accepts the payload, or several do and none of them is more
         *                                    specific than the others
         */
        AnnotationBasedAppendCriteriaResolver resolverFor(Class<?> payloadType) {
            return select(payloadType, builders)
                    .map(AnnotationBasedAppendCriteriaResolver::new)
                    .orElseThrow(() -> uncovered(payloadType, declaringType, builders));
        }
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
                    throw new AxonConfigurationException(
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
            throw new AxonConfigurationException(
                    ("Command payload [%s] is accepted by several @AppendCriteriaBuilder methods, none of which is "
                            + "more specific than the others: %s. Declare a builder for [%s] itself.")
                            .formatted(payloadType.getName(), describe(candidates), payloadType.getName())
            );
        }
        return Optional.of(mostSpecific.getFirst());
    }

    private static AxonConfigurationException uncovered(Class<?> payloadType,
                                                       Class<?> declaringType,
                                                       Collection<BuilderMethod> builders) {
        return new AxonConfigurationException(
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

    private static AxonConfigurationException invalid(Method method, String reason) {
        return new AxonConfigurationException(violation(method, reason));
    }

    private static AxonConfigurationException invalid(Method method, String reason, Throwable cause) {
        return new AxonConfigurationException(violation(method, reason), cause);
    }

    private static String violation(Method method, String reason) {
        return "@AppendCriteriaBuilder method %s. Violating method: %s#%s"
                .formatted(reason,
                           method.getDeclaringClass().getName(),
                           ReflectionUtils.toDiscernibleSignature(method));
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
                // metadata only holds strings, so any other type would fail every commit that carries the value
                if (!parameter.getType().isAssignableFrom(String.class)) {
                    throw invalid(method, ("declares a @MetadataValue parameter of type [%s], while metadata values "
                            + "are strings").formatted(parameter.getType().getName()));
                }
                String key = metadataValue.get().get("metadataValue").toString();
                boolean required = (boolean) metadataValue.get().get("required");
                return (command, context, sourcingCriteria) -> {
                    String value = command.metadata().get(key);
                    if (value == null && required) {
                        throw new IllegalArgumentException(
                                "Required metadata value [%s] is missing for @AppendCriteriaBuilder method [%s]."
                                        .formatted(key, ReflectionUtils.toDiscernibleSignature(method))
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
         * lookup itself fail; that failure is kept as the cause.
         */
        private static ArgumentResolver componentResolver(Class<?> parameterType,
                                                          Configuration configuration,
                                                          Method method) {
            Object component;
            try {
                component = configuration.getOptionalComponent(parameterType).orElse(null);
            } catch (RuntimeException e) {
                throw invalid(method, "declares unsupported parameter type [%s]: %s"
                        .formatted(parameterType.getName(), e.getMessage()), e);
            }
            if (component == null) {
                throw invalid(method,
                              "declares unsupported parameter type [%s]".formatted(parameterType.getName()));
            }
            Object resolved = component;
            return (command, context, sourcingCriteria) -> resolved;
        }

        private boolean acceptsAsPayload(Class<?> payloadType) {
            return commandType != CommandMessage.class && commandType.isAssignableFrom(payloadType);
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
                    throw new IllegalStateException(
                            violation(method, "returned null; it must return a non-null EventCriteria")
                    );
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
