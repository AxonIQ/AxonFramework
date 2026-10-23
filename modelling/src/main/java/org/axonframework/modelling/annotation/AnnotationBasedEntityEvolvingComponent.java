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

import org.axonframework.common.StringUtils;
import org.axonframework.common.TypeReflectionUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.EntityEvolvingComponent;
import org.axonframework.modelling.StateEvolvingException;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of the {@link EntityEvolvingComponent} that applies state changes through
 * {@link EventHandler}(-meta)-annotated methods using the
 * {@link AnnotatedHandlerInspector}.
 * <p>
 * During construction, this component eagerly resolves the event names of all inspected handlers and builds an
 * immutable routing index. This shifts annotation inspection and message type resolution to initialization, increasing
 * startup work and memory usage in proportion to the number of handlers. In return, event evolution performs direct
 * lookups without reflection, message type resolution, cache mutation, or a scan of all handlers. Resolution failures
 * are also reported during initialization instead of on the first matching event.
 * <p>
 * An overriding method counts as one handler; call {@code super} to run the overridden one. Separate methods for the
 * same event all run, parent handlers first, each receiving the entity returned by the previous one. Within one class
 * the order is unspecified. A handler may return a different concrete type only if every handler that follows is
 * declared by a supertype of the returned type; otherwise evolving fails with a {@link StateEvolvingException}.
 * Handlers declared only by the returned type do not run for the same event.
 *
 * @param <E> The entity type to evolve.
 * @author Mateusz Nowak
 * @see AnnotatedHandlerInspector
 * @since 5.0.0
 */
public class AnnotationBasedEntityEvolvingComponent<E> implements EntityEvolvingComponent<E> {

    private static final Comparator<MessageHandlingMember<?>> LEAST_SPECIFIC_DECLARING_TYPE_FIRST =
            Comparator.comparingInt(AnnotationBasedEntityEvolvingComponent::declaringTypeSpecificity);

    private final Class<E> entityType;
    private final AnnotatedHandlerInspector<E> inspector;
    private final EventConverter converter;
    private final Map<Class<?>, Map<QualifiedName, List<MessageHandlingMember<? super E>>>> handlersByEntityType;

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType          The type of entity this instance will handle state changes for.
     * @param converter           The converter to use for converting event payloads to the handler's expected type.
     * @param messageTypeResolver The resolver to use for resolving the event message type.
     * @deprecated in favor of
     * {@link #AnnotationBasedEntityEvolvingComponent(Class, EventConverter, MessageTypeResolver,
     * ParameterResolverFactory, HandlerDefinition)} since this allows for clean customization of the
     * {@link ParameterResolverFactory} and {@link HandlerDefinition}, which this constructor does not
     */
    @Deprecated(since = "5.3.2", forRemoval = true)
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver) {
        this(entityType,
             AnnotatedHandlerInspector.inspectType(
                     entityType,
                     messageTypeResolver,
                     ClasspathParameterResolverFactory.forClass(entityType),
                     ClasspathHandlerDefinition.forClass(entityType)
             ),
             converter,
             messageTypeResolver);
    }

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType               the type of entity this instance will handle state changes for
     * @param converter                the converter to use for converting event payloads to the handler's expected
     *                                 type
     * @param messageTypeResolver      the resolver to use for resolving the event message type
     * @param parameterResolverFactory the resolver factory to use during detection of the annotated model
     * @param handlerDefinition        the handler definition used to create concrete handlers of the annotated model
     */
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver,
                                                  ParameterResolverFactory parameterResolverFactory,
                                                  HandlerDefinition handlerDefinition) {
        this(
                entityType,
                AnnotatedHandlerInspector.inspectType(
                        entityType,
                        messageTypeResolver,
                        parameterResolverFactory,
                        handlerDefinition
                ),
                converter,
                messageTypeResolver
        );
    }

    /**
     * Initialize a new annotation-based {@link EntityEvolver}.
     *
     * @param entityType          The type of entity this instance will handle state changes for.
     * @param inspector           The inspector to use to find the annotated handlers on the entity.
     * @param converter           The converter to use for converting event payloads to the handler's expected type.
     * @param messageTypeResolver The resolver to use for resolving the event message type.
     */
    public AnnotationBasedEntityEvolvingComponent(Class<E> entityType,
                                                  AnnotatedHandlerInspector<E> inspector,
                                                  EventConverter converter,
                                                  MessageTypeResolver messageTypeResolver
    ) {
        this.entityType = requireNonNull(entityType, "The entity type must not be null.");
        this.inspector = requireNonNull(inspector, "The Annotated Handler Inspector must not be null.");
        this.converter = requireNonNull(converter, "The Converter must not be null.");
        this.handlersByEntityType = indexHandlersByEntityType(
                requireNonNull(messageTypeResolver, "The Message Type Resolver must not be null.")
        );
    }

    @Override
    public E evolve(E entity,
                    EventMessage event,
                    ProcessingContext context) {
        try {
            var listenerType = entity.getClass();

            var handlers = handlersByEntityType.getOrDefault(listenerType, Map.of())
                                               .getOrDefault(event.type().qualifiedName(), List.of());

            E evolvedEntity = entity;
            for (var handler : handlers) {
                var convertedEvent = event.withConvertedPayload(handler.payloadType(), converter);
                if (!handler.canHandle(convertedEvent, context)) {
                    continue;
                }
                assertHandlerCanRunOn(handler, evolvedEntity, event);
                var interceptor = inspector.chainedInterceptor(listenerType);
                var result = interceptor.handle(convertedEvent, context, evolvedEntity, handler)
                                        .first()
                                        .asCompletableFuture()
                                        .join();
                evolvedEntity = entityFromStreamResultOrUpdatedExisting(result, evolvedEntity);
            }

            return evolvedEntity;
        } catch (Exception e) {
            throw new StateEvolvingException(
                    "Failed to apply event [" + event.type() + "] in order to evolve [" + entity.getClass() + "] state",
                    e
            );
        }
    }

    private Map<Class<?>, Map<QualifiedName, List<MessageHandlingMember<? super E>>>> indexHandlersByEntityType(
            MessageTypeResolver messageTypeResolver
    ) {
        return inspector.getAllHandlers().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> indexHandlersByEventName(entry.getKey(), entry.getValue(), messageTypeResolver)
                        ));
    }

    private Map<QualifiedName, List<MessageHandlingMember<? super E>>> indexHandlersByEventName(
            Class<?> type,
            Collection<MessageHandlingMember<? super E>> handlers,
            MessageTypeResolver messageTypeResolver
    ) {
        return handlers.stream()
                       .filter(handler -> handler.canHandleMessageType(EventMessage.class))
                       // javac copies handler annotations onto the bridges it generates, and a bridge runs the same
                       // body as the method it bridges; for an override of a generic parent method, the bridge would
                       // otherwise count as a second handler
                       .filter(handler -> handler.unwrap(Method.class).filter(Method::isBridge).isEmpty())
                       .collect(Collectors.groupingBy(handler -> eventName(handler, messageTypeResolver)))
                       .entrySet().stream()
                       .collect(Collectors.toUnmodifiableMap(
                               Map.Entry::getKey,
                               entry -> onePerDispatchTargetParentFirst(type, entry.getValue())
                       ));
    }

    /**
     * Keeps one handler per method that Java would actually run on the given entity type, the one declared by the most
     * specific type, and orders them parent handlers first. Handlers whose methods are overridden by a method without
     * the annotation therefore still run exactly once, through virtual dispatch.
     */
    private List<MessageHandlingMember<? super E>> onePerDispatchTargetParentFirst(
            Class<?> entityType,
            List<MessageHandlingMember<? super E>> handlers
    ) {
        Map<Object, MessageHandlingMember<? super E>> byDispatchTarget = new LinkedHashMap<>();
        for (var handler : handlers) {
            Object target = handler.unwrap(Method.class)
                                   .<Object>map(method -> dispatchTarget(entityType, method))
                                   .orElse(handler);
            byDispatchTarget.merge(target, handler, (existing, replacement) ->
                    declaringTypeSpecificity(replacement) > declaringTypeSpecificity(existing) ? replacement : existing
            );
        }
        // A stable sort, keeping the inspector's order among handlers declared on equally specific types
        return byDispatchTarget.values().stream()
                               .sorted(LEAST_SPECIFIC_DECLARING_TYPE_FIRST)
                               .toList();
    }

    /**
     * Resolves transitively: a package-private method is invisible to an override in another package, so the next
     * override is only found by resolving again from the target found so far.
     */
    private static Method dispatchTarget(Class<?> entityType, Method method) {
        Method target = method;
        Method resolved = dispatchTargetOnce(entityType, target);
        while (!resolved.equals(target)) {
            target = resolved;
            resolved = dispatchTargetOnce(entityType, target);
        }
        return target;
    }

    private static Method dispatchTargetOnce(Class<?> entityType, Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
            return method;
        }
        for (Class<?> type = entityType; type != null; type = type.getSuperclass()) {
            for (Method declared : type.getDeclaredMethods()) {
                if (isOverrideOrSame(declared, method)) {
                    return bridgedMethodOrSelf(declared, method);
                }
            }
        }
        // Not declared by any class, so it is an interface default method: the most specific default wins
        Set<Class<?>> interfaces = supertypesOf(entityType);
        interfaces.add(entityType);
        List<Method> defaults = interfaces.stream()
                                          .filter(Class::isInterface)
                                          .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                                          .filter(declared -> isOverrideOrSame(declared, method))
                                          .toList();
        return defaults.stream()
                       .filter(candidate -> defaults.stream().noneMatch(
                               other -> other != candidate
                                       && candidate.getDeclaringClass().isAssignableFrom(other.getDeclaringClass())))
                       .findFirst()
                       .map(candidate -> bridgedMethodOrSelf(candidate, method))
                       .orElse(method);
    }

    /**
     * Java dispatches an override with a narrower signature through a compiler-generated bridge method, so a bridge
     * stands for the method it bridges: the one of the same class with the same name whose parameters are exactly the
     * parameter types of the {@code overridden} method, resolved against the type arguments of that class. Without
     * exactly one such method, the bridge is left unresolved.
     */
    private static Method bridgedMethodOrSelf(Method bridge, Method overridden) {
        if (!bridge.isBridge()) {
            return bridge;
        }
        Class<?> declaringClass = bridge.getDeclaringClass();
        Class<?>[] exactParameterTypes = exactParameterTypes(overridden, declaringClass);
        List<Method> bridged = Arrays.stream(declaringClass.getDeclaredMethods())
                                     .filter(candidate -> !candidate.isBridge()
                                             && candidate.getName().equals(bridge.getName())
                                             && Arrays.equals(candidate.getParameterTypes(), exactParameterTypes))
                                     .toList();
        return bridged.size() == 1 ? bridged.getFirst() : bridge;
    }

    private static Class<?>[] exactParameterTypes(Method method, Class<?> viewpoint) {
        Type owner = TypeReflectionUtils.getExactSuperType(parameterizedWithOwnTypeVariables(viewpoint),
                                                           method.getDeclaringClass());
        Type[] genericTypes = method.getGenericParameterTypes();
        Class<?>[] exactTypes = method.getParameterTypes();
        if (genericTypes.length != exactTypes.length || !(owner instanceof ParameterizedType parameterized)) {
            return exactTypes;
        }
        List<TypeVariable<?>> typeParameters = List.of(method.getDeclaringClass().getTypeParameters());
        for (int i = 0; i < genericTypes.length; i++) {
            exactTypes[i] = exactType(genericTypes[i], typeParameters, parameterized.getActualTypeArguments());
        }
        return exactTypes;
    }

    /**
     * A raw generic class would lose the type arguments of its supertypes, whereas viewed with its own type variables
     * they resolve to those variables, which {@link #erasure(Type)} turns into their bounds.
     */
    private static Type parameterizedWithOwnTypeVariables(Class<?> type) {
        if (type.getTypeParameters().length == 0) {
            return type;
        }
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return type.getTypeParameters();
            }

            @Override
            public Type getRawType() {
                return type;
            }

            @Override
            public Type getOwnerType() {
                return type.getDeclaringClass();
            }
        };
    }

    private static Class<?> exactType(Type type, List<TypeVariable<?>> typeParameters, Type[] typeArguments) {
        if (type instanceof TypeVariable<?> variable && typeParameters.contains(variable)) {
            return erasure(typeArguments[typeParameters.indexOf(variable)]);
        }
        if (type instanceof GenericArrayType array) {
            return Array.newInstance(exactType(array.getGenericComponentType(), typeParameters, typeArguments), 0)
                        .getClass();
        }
        return erasure(type);
    }

    private static Class<?> erasure(Type type) {
        return switch (type) {
            case Class<?> clazz -> clazz;
            case ParameterizedType parameterized -> (Class<?>) parameterized.getRawType();
            case TypeVariable<?> variable -> erasure(variable.getBounds()[0]);
            case GenericArrayType array -> Array.newInstance(erasure(array.getGenericComponentType()), 0).getClass();
            default -> Object.class;
        };
    }

    private static boolean isOverrideOrSame(Method candidate, Method method) {
        if (candidate.equals(method)) {
            return true;
        }
        int candidateModifiers = candidate.getModifiers();
        int methodModifiers = method.getModifiers();
        return !Modifier.isStatic(candidateModifiers)
                && !Modifier.isPrivate(candidateModifiers)
                && candidate.getName().equals(method.getName())
                && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())
                && (Modifier.isPublic(methodModifiers)
                || Modifier.isProtected(methodModifiers)
                || candidate.getDeclaringClass().getPackageName()
                            .equals(method.getDeclaringClass().getPackageName()));
    }

    private static Optional<Class<?>> declaringClass(MessageHandlingMember<?> handler) {
        return handler.unwrap(Member.class).map(Member::getDeclaringClass);
    }

    private static int declaringTypeSpecificity(MessageHandlingMember<?> handler) {
        // A subtype always has more supertypes than any of its supertypes, for classes and interfaces alike
        return declaringClass(handler).map(type -> supertypesOf(type).size()).orElse(0);
    }

    private static Set<Class<?>> supertypesOf(Class<?> type) {
        Set<Class<?>> supertypes = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(type));
        while (!pending.isEmpty()) {
            Class<?> current = pending.pop();
            if (current.getSuperclass() != null && supertypes.add(current.getSuperclass())) {
                pending.push(current.getSuperclass());
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (supertypes.add(implemented)) {
                    pending.push(implemented);
                }
            }
        }
        return supertypes;
    }

    private void assertHandlerCanRunOn(MessageHandlingMember<? super E> handler, E evolvedEntity, EventMessage event) {
        Optional<Class<?>> declaringClass = declaringClass(handler);
        if (declaringClass.isPresent() && !declaringClass.get().isInstance(evolvedEntity)) {
            throw new IllegalStateException(
                    "An earlier handler for event [" + event.type() + "] changed the entity to ["
                            + evolvedEntity.getClass().getName() + "], so handler [" + handler.signature()
                            + "] declared by [" + declaringClass.get().getName() + "] cannot run. A handler may return"
                            + " a different concrete type only if every handler that follows is declared by a"
                            + " supertype of the returned type."
            );
        }
    }

    private QualifiedName eventName(MessageHandlingMember<? super E> handler,
                                    MessageTypeResolver messageTypeResolver) {
        return handler.unwrap(EventHandlingMember.class)
                      .map(EventHandlingMember::eventName)
                      .filter(StringUtils::nonEmpty)
                      .map(QualifiedName::new)
                      .orElseGet(() -> messageTypeResolver.resolveOrThrow(handler.payloadType()).qualifiedName());
    }

    private E entityFromStreamResultOrUpdatedExisting(MessageStream.Entry<?> potentialEntityFromStream, E existing) {
        if (potentialEntityFromStream != null) {
            var resultPayload = potentialEntityFromStream.message().payload();
            if (resultPayload != null && entityType.isAssignableFrom(resultPayload.getClass())) {
                //noinspection unchecked
                return (E) entityType.cast(resultPayload);
            }
        }
        return existing;
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return handlersByEntityType.values().stream()
                                  .flatMap(handlers -> handlers.keySet().stream())
                                  .collect(Collectors.toUnmodifiableSet());
    }
}
