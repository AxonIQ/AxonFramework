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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.interception.annotation.ChainedMessageHandlerInterceptorMember;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.interception.annotation.MessageInterceptingMember;
import org.axonframework.messaging.core.interception.annotation.NoMoreInterceptors;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static java.util.Collections.emptySortedSet;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToSingleStream;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;

/**
 * Inspector for a message handling target of type {@code T} that uses annotations on the target to inspect the
 * capabilities of the target.
 *
 * @param <T> The target type.
 * @author Allard Buijze
 * @since 3.0.0
 */
@Internal
public class AnnotatedHandlerInspector<T> {

    private final Class<T> inspectedType;
    private final List<AnnotatedHandlerInspector<? super T>> superClassInspectors;
    private final List<AnnotatedHandlerInspector<? extends T>> subClassInspectors;
    private final MessageTypeResolver messageTypeResolver;
    private final Map<Class<?>, SortedSet<MessageHandlingMember<? super T>>> handlers;
    private final Map<Class<?>, MessageHandlerInterceptorMemberChain<T>> interceptorChains;
    private final Map<Class<?>, SortedSet<MessageHandlingMember<? super T>>> interceptors;

    private AnnotatedHandlerInspector(Class<T> inspectedType,
                                      List<AnnotatedHandlerInspector<? super T>> superClassInspectors,
                                      List<AnnotatedHandlerInspector<? extends T>> subClassInspectors,
                                      MessageTypeResolver messageTypeResolver) {
        this.inspectedType = inspectedType;
        this.superClassInspectors = new ArrayList<>(superClassInspectors);
        this.subClassInspectors = subClassInspectors;
        this.messageTypeResolver = messageTypeResolver;
        this.handlers = new HashMap<>();
        this.interceptorChains = new ConcurrentHashMap<>();
        this.interceptors = new ConcurrentHashMap<>();
    }

    /**
     * Create an inspector for given {@code handlerType} that uses given {@code parameterResolverFactory} to resolve
     * method parameters and given {@code handlerDefinition} to create handlers.
     *
     * @param <T>                      the handler's type
     * @param handlerType              the target handler type
     * @param messageTypeResolver      the message type resolver used to derive the return type of the message returned
     *                                 by the {@link org.axonframework.messaging.core.MessageStream} returned from
     *                                 uncovered
     *                                 {@link org.axonframework.messaging.core.MessageHandler MessageHandlers}
     * @param parameterResolverFactory the resolver factory to use during detection
     * @param handlerDefinition        the handler definition used to create concrete handlers
     * @return a new inspector instance for the inspected class
     */
    public static <T> AnnotatedHandlerInspector<T> inspectType(Class<T> handlerType,
                                                               MessageTypeResolver messageTypeResolver,
                                                               ParameterResolverFactory parameterResolverFactory,
                                                               HandlerDefinition handlerDefinition) {
        return inspectType(handlerType, messageTypeResolver, parameterResolverFactory, handlerDefinition, emptySet());
    }

    /**
     * Create an inspector for given {@code handlerType} and its {@code declaredSubtypes} that uses given
     * {@code parameterResolverFactory} to resolve method parameters and given {@code handlerDefinition} to create
     * handlers.
     *
     * @param <T>                      the handler's type
     * @param handlerType              the target handler type
     * @param messageTypeResolver      the message type resolver used to derive the return type of the message returned
     *                                 by the {@link org.axonframework.messaging.core.MessageStream} returned from
     *                                 uncovered
     *                                 {@link org.axonframework.messaging.core.MessageHandler MessageHandlers}
     * @param parameterResolverFactory the resolver factory to use during detection
     * @param handlerDefinition        the handler definition used to create concrete handlers
     * @param declaredSubtypes         the declared subtypes of this {@code handlerType}
     * @return a new inspector instance for the inspected class
     */
    public static <T> AnnotatedHandlerInspector<T> inspectType(Class<T> handlerType,
                                                               MessageTypeResolver messageTypeResolver,
                                                               ParameterResolverFactory parameterResolverFactory,
                                                               HandlerDefinition handlerDefinition,
                                                               Set<Class<? extends T>> declaredSubtypes) {
        return createInspector(
                handlerType,
                messageTypeResolver,
                parameterResolverFactory,
                handlerDefinition,
                new HashMap<>(),
                declaredSubtypes
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> AnnotatedHandlerInspector<T> createInspector(
            Class<T> inspectedType,
            MessageTypeResolver messageTypeResolver,
            ParameterResolverFactory parameterResolverFactory,
            HandlerDefinition handlerDefinition,
            Map<Class<?>, AnnotatedHandlerInspector<?>> registry,
            Set<Class<? extends T>> declaredSubtypes
    ) {
        if (!registry.containsKey(inspectedType)) {
            registry.put(inspectedType, AnnotatedHandlerInspector.initialize(
                    inspectedType,
                    messageTypeResolver,
                    parameterResolverFactory,
                    handlerDefinition,
                    registry,
                    declaredSubtypes
            ));
        }

        return (AnnotatedHandlerInspector<T>) registry.get(inspectedType);
    }

    private static <T> AnnotatedHandlerInspector<T> initialize(Class<T> inspectedType,
                                                               MessageTypeResolver messageTypeResolver,
                                                               ParameterResolverFactory parameterResolverFactory,
                                                               HandlerDefinition handlerDefinition,
                                                               Map<Class<?>, AnnotatedHandlerInspector<?>> registry,
                                                               Set<Class<? extends T>> declaredSubtypes) {
        List<AnnotatedHandlerInspector<? super T>> parents = new ArrayList<>();
        for (Class<?> iFace : inspectedType.getInterfaces()) {
            @SuppressWarnings("unchecked")  // Safe cast: all interfaces of T are guaranteed to be supertypes of T
            Class<? super T> castIF = (Class<? super T>) iFace;
            parents.add(createInspector(
                    castIF,
                    messageTypeResolver,
                    parameterResolverFactory,
                    handlerDefinition,
                    registry,
                    emptySet()
            ));
        }

        if (inspectedType.getSuperclass() != null && !Object.class.equals(inspectedType.getSuperclass())) {
            parents.add(createInspector(inspectedType.getSuperclass(), messageTypeResolver,
                                        parameterResolverFactory,
                                        handlerDefinition,
                                        registry, emptySet()));
        }
        List<AnnotatedHandlerInspector<? extends T>> children =
                declaredSubtypes.stream()
                                .map(subclass -> createInspector(
                                        subclass,
                                        messageTypeResolver,
                                        parameterResolverFactory,
                                        handlerDefinition,
                                        registry,
                                        emptySet()
                                ))
                                .collect(Collectors.toList());

        AnnotatedHandlerInspector<T> inspector =
                new AnnotatedHandlerInspector<>(inspectedType, parents, children, messageTypeResolver);
        inspector.initializeMessageHandlers(parameterResolverFactory, handlerDefinition);
        return inspector;
    }

    @SuppressWarnings("unchecked")
    private void initializeMessageHandlers(ParameterResolverFactory parameterResolverFactory,
                                           HandlerDefinition handlerDefinition) {
        handlers.put(inspectedType, new TreeSet<>(HandlerComparator.instance()));
        MessageStreamResolver messageStreamResolver = this::resolveResultToStream;
        for (Method method : inspectedType.getDeclaredMethods()) {
            handlerDefinition.createHandler(
                    inspectedType,
                    method,
                    parameterResolverFactory,
                    messageStreamResolver
            ).ifPresent(h -> registerHandler(inspectedType, h));
        }

        // we need to consider handlers from parent/subclasses as well
        subClassInspectors.forEach(sci -> sci.getAllHandlers()
                                             .forEach((key, value) -> value.forEach(
                                                     h -> registerHandler(key, (MessageHandlingMember<T>) h))
                                             ));
        superClassInspectors.forEach(sci -> sci.getAllHandlers()
                                               .forEach((key, value) -> value.forEach(h -> {
                                                   boolean isAbstract = h.unwrap(Executable.class)
                                                                         .map(e -> Modifier.isAbstract(e.getModifiers()))
                                                                         .orElse(false);
                                                   if (!isAbstract) {
                                                       registerHandler(key, h);
                                                   }
                                                   registerHandler(inspectedType, h);
                                               })));

        // we need to consider interceptors from parent/subclasses as well
        subClassInspectors.forEach(sci -> sci.getAllInterceptors()
                                             .forEach((key, value) -> value.forEach(
                                                     h -> registerHandler(key, (MessageHandlingMember<T>) h))
                                             ));
        superClassInspectors.forEach(sci -> sci.getAllInterceptors()
                                               .forEach((key, value) -> value.forEach(h -> {
                                                   registerHandler(key, h);
                                                   registerHandler(inspectedType, h);
                                               })));
    }

    /**
     * Resolves a handler's return value into a {@link MessageStream}, taking the {@code messageType} the handler is
     * subscribed for into account to decide how many {@link Message Messages} it may produce.
     * <p>
     * Handlers of {@link CommandMessage CommandMessages} produce exactly one result, as expressed by
     * {@link org.axonframework.messaging.commandhandling.CommandHandler} returning a {@link MessageStream.Single}. A
     * collection returned by such a handler is therefore carried as the payload of a single {@link Message}, since
     * spreading it over one {@code Message} per element would silently discard all elements but the first. Handlers of
     * other message types may produce several results, and thus do spread a returned collection.
     */
    private MessageStream<?> resolveResultToStream(@Nullable Object result, Class<? extends Message> messageType) {
        return CommandMessage.class.isAssignableFrom(messageType)
                ? resolveToSingleStream(result, messageTypeResolver)
                : resolveToStream(result, messageTypeResolver);
    }

    private void registerHandler(Class<?> type, MessageHandlingMember<? super T> handler) {
        if (handler.unwrap(MessageInterceptingMember.class).isPresent()) {
            interceptors.computeIfAbsent(type, t -> new TreeSet<>(HandlerComparator.instance()))
                        .add(handler);
        } else {
            handlers.computeIfAbsent(type, t -> new TreeSet<>(HandlerComparator.instance()))
                    .add(handler);
        }
    }

    /**
     * Returns a sorted set of detected members of given {@code type} that are capable of handling certain messages.
     *
     * @param type a type of inspected entity
     * @return a sorted set of detected message handlers for given {@code type}
     */
    public SortedSet<MessageHandlingMember<? super T>> getHandlers(Class<?> type) {
        return handlers.getOrDefault(type, emptySortedSet());
    }

    /**
     * Returns a list of detected members of given {@code type}, that can handle messages of {@code messageType}. When
     * several members resolve to the same method signature - for example a supertype handler that is overridden or
     * shadowed by a subtype - only the member declared by the most specific type is retained.
     *
     * @param type a type of inspected entity
     * @param messageType a message type the returned handlers must be able to handle
     * @return a list of unique detected message handlers for given {@code type}, that can handle messages of {@code messageType}
     */
    public List<MessageHandlingMember<? super T>> getUniqueHandlers(Class<?> type, Class<? extends Message> messageType) {
        SortedSet<MessageHandlingMember<? super T>> set = handlers.getOrDefault(type, emptySortedSet());

        // When several handlers resolve to the same method signature - because a supertype method is
        // overridden or shadowed in a subtype - only the one declared by the most specific type is kept.
        // Otherwise an inherited handler may take precedence over the subtype's own handler; for private
        // methods, which are bound statically rather than virtually dispatched, this even causes the
        // supertype's implementation to be invoked instead of the subtype's.
        // Note: the messageType is filtered first on purpose, so a member handling a different message type
        // cannot displace the member that actually handles the requested messageType.
        Map<ExecutableSignature, MessageHandlingMember<? super T>> uniqueBySignature = set.stream()
            .filter(member -> member.canHandleMessageType(messageType))
            .collect(Collectors.toMap(
                member -> member.unwrap(Executable.class).map(ExecutableSignature::of).orElseThrow(),  // there is always an executable
                member -> member,
                (existing, replacement) ->
                    existing.declaringClass().isAssignableFrom(replacement.declaringClass()) ? replacement : existing,
                LinkedHashMap::new
            ));
        return List.copyOf(uniqueBySignature.values());
    }

    /**
     * Returns an Interceptor Chain of annotated interceptor methods defined on the given {@code type}. The given chain
     * will invoke all relevant interceptors in an order defined by the handler definition.
     *
     * @param type The type containing the handler definitions
     * @return an interceptor chain that invokes the interceptor handlers defined on the inspected type
     */
    public MessageHandlerInterceptorMemberChain<T> chainedInterceptor(Class<?> type) {
        return interceptorChains.computeIfAbsent(type, t -> {
            Collection<MessageHandlingMember<? super T>> i = interceptors.getOrDefault(type, emptySortedSet());
            if (i.isEmpty()) {
                return NoMoreInterceptors.instance();
            }
            return new ChainedMessageHandlerInterceptorMember<>(i.iterator());
        });
    }

    /**
     * Gets all handlers per type for inspected entity. Handlers are sorted based on {@link HandlerComparator}.
     *
     * @return a map of handlers per type
     */
    public Map<Class<?>, SortedSet<MessageHandlingMember<? super T>>> getAllHandlers() {
        return Collections.unmodifiableMap(handlers);
    }

    /**
     * Returns a Map of all registered interceptor methods per inspected type. Each entry contains the inspected type as
     * key, and a SortedSet of interceptor methods defined on that type, in the order they are considered for
     * invocation.
     *
     * @return a map of interceptors per type
     */
    public Map<Class<?>, SortedSet<MessageHandlingMember<? super T>>> getAllInterceptors() {
        return Collections.unmodifiableMap(interceptors);
    }

    /**
     * Resolves a behavior of the given {@code behaviorType} from the inspected {@code target}, on the target's behalf.
     * <p>
     * This is the model's seam for exposing behaviors of an annotated handler. Currently it resolves a behavior the
     * {@code target} implements directly. In the future, behaviors declared purely through annotations (for example an
     * {@code @SelfCheckpointing}-style annotation that does not require the target to implement the interface) can be
     * synthesised here on the target's behalf, without the wrapping component needing to change.
     *
     * @param target       the inspected handler instance to resolve the behavior from
     * @param behaviorType the behavior type to resolve
     * @param <B>          the behavior type
     * @return an {@link Optional} holding the resolved behavior, or empty if the {@code target} does not provide it
     * @since 5.3.0
     */
    public <B> Optional<B> resolveBehavior(T target, Class<B> behaviorType) {
        return behaviorType.isInstance(target) ? Optional.of(behaviorType.cast(target)) : Optional.empty();
    }

    record ExecutableSignature(String name, List<Class<?>> parameterTypes) {
        static ExecutableSignature of(Executable e) {
            return new ExecutableSignature(e.getName(), List.of(e.getParameterTypes()));
        }
    }
}
