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

import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Describes one of several {@link MessageHandler message handler} annotations of a {@link Method}, each of which
 * registers the method as a separate handler.
 * <p>
 * A method declares several message handler annotations when it repeats one, such as
 * {@link org.axonframework.messaging.eventhandling.annotation.EventHandler @EventHandler}, or when it is annotated with
 * a custom annotation that itself repeats one. In both cases, the Java compiler groups the repeated annotations in a
 * container annotation. An occurrence is the chain of annotations leading from the method to one
 * {@link MessageHandler @MessageHandler}, taking a single one of the repeated annotations at every container. Its
 * {@link #handlerAttributes() handler attributes} are those of the annotations in that chain, where attributes of
 * annotations closer to the method override same-named attributes of the annotations they are meta-annotated with,
 * combined with the handler attributes of the other annotations of the method.
 * <p>
 * As an {@link AnnotatedElement}, an occurrence exposes the method annotation its chain starts with, together with all
 * annotations of the method that are not message handler annotations.
 * <p>
 * This class is internal, as it only serves the creation of {@link MessageHandlingMember MessageHandlingMembers} by
 * the {@link AnnotatedMessageHandlingMemberDefinition}.
 *
 * @author Allard Buijze
 * @since 5.5.0
 */
@Internal
public final class HandlerAnnotationOccurrence implements AnnotatedElement {

    private final Method method;
    private final int index;
    private final Annotation[] annotations;
    private final Map<String, Object> handlerAttributes;
    private final Map<String, Object> messageHandlerAttributes;

    private HandlerAnnotationOccurrence(Method method,
                                        int index,
                                        List<Annotation> chain,
                                        List<Annotation> otherAnnotations) {
        this.method = method;
        this.index = index;
        List<Annotation> exposed = new ArrayList<>(otherAnnotations);
        exposed.add(chain.getFirst());
        this.annotations = exposed.toArray(Annotation[]::new);
        this.handlerAttributes = new HashMap<>(
                new AnnotatedHandlerAttributes(new AnnotationsView(otherAnnotations)).getAll()
        );
        Map<String, Object> messageHandlerAttributes = Map.of();
        for (int i = 0; i < chain.size(); i++) {
            Class<? extends Annotation> type = chain.get(i).annotationType();
            if (!hasHandlerAttributes(type)) {
                continue;
            }
            Map<String, Object> typeAttributes = attributesInChain(chain, i);
            typeAttributes.forEach((name, value) -> handlerAttributes.put(type.getSimpleName() + "." + name, value));
            if (type.equals(MessageHandler.class)) {
                messageHandlerAttributes = typeAttributes;
            }
        }
        this.messageHandlerAttributes = messageHandlerAttributes;
    }

    /**
     * Returns the elements to read the message handler annotations of the given {@code method} from, one per declared
     * message handler annotation.
     * <p>
     * When the given {@code method} does not repeat any message handler annotation, either directly or through the
     * annotations it is annotated with, the result contains only the {@code method} itself. Otherwise, the result
     * contains a {@link HandlerAnnotationOccurrence} per message handler annotation, in declaration order.
     *
     * @param method the method to find the message handler annotation occurrences of
     * @return the elements to read the message handler annotations of the given {@code method} from, one per declared
     * message handler annotation
     */
    public static List<AnnotatedElement> of(Method method) {
        List<Annotation> otherAnnotations = new ArrayList<>();
        Chains chains = chainsOf(handlerGroupsOf(method, otherAnnotations), List.of(), new HashSet<>());
        if (!chains.repeated()) {
            return List.of(method);
        }
        List<AnnotatedElement> occurrences = new ArrayList<>(chains.chains().size());
        for (int i = 0; i < chains.chains().size(); i++) {
            occurrences.add(new HandlerAnnotationOccurrence(method, i, chains.chains().get(i), otherAnnotations));
        }
        return occurrences;
    }

    /**
     * Groups the message handler annotations on the given {@code element}. Annotations of a repeatable type form a
     * single group, whether they are placed directly or grouped in their container annotation. Annotations that are
     * not message handler annotations are added to the given {@code otherAnnotations}, if not {@code null}.
     */
    private static List<HandlerGroup> handlerGroupsOf(AnnotatedElement element,
                                                      @Nullable List<Annotation> otherAnnotations) {
        Annotation[] elementAnnotations = element.getAnnotations();
        Set<Class<? extends Annotation>> repeatedHandlerTypes = new HashSet<>();
        for (Annotation annotation : elementAnnotations) {
            Class<? extends Annotation> repeatedType = AnnotationUtils.repeatedTypeOf(annotation.annotationType());
            if (repeatedType != null && isMessageHandlerAnnotation(repeatedType)) {
                repeatedHandlerTypes.add(repeatedType);
            }
        }
        List<HandlerGroup> groups = new ArrayList<>();
        for (Annotation annotation : elementAnnotations) {
            Class<? extends Annotation> type = annotation.annotationType();
            Class<? extends Annotation> repeatedType = AnnotationUtils.repeatedTypeOf(type);
            if (repeatedType != null && repeatedHandlerTypes.contains(repeatedType)) {
                // Covers the annotations in the container, as well as one of the same type placed directly
                groups.add(new HandlerGroup(List.of(element.getAnnotationsByType(repeatedType)), true));
            } else if (repeatedHandlerTypes.contains(type)) {
                // Already included through the container annotation
                continue;
            } else if (isMessageHandlerAnnotation(type)) {
                groups.add(new HandlerGroup(List.of(annotation), false));
            } else if (otherAnnotations != null) {
                otherAnnotations.add(annotation);
            }
        }
        return groups;
    }

    /**
     * Returns the chains of annotations leading from the annotations in the given {@code groups} to a
     * {@link MessageHandler}, each prefixed with the given {@code prefix}. Like the meta-annotation lookups of
     * {@link AnnotationUtils}, a meta-annotated type only follows the first of its meta-annotations leading to a
     * {@code MessageHandler}, except that every annotation in a container annotation results in chains of its own.
     */
    private static Chains chainsOf(List<HandlerGroup> groups,
                                   List<Annotation> prefix,
                                   Set<Class<? extends Annotation>> visiting) {
        List<List<Annotation>> chains = new ArrayList<>();
        boolean repeated = false;
        for (HandlerGroup group : groups) {
            for (Annotation annotation : group.annotations()) {
                Chains annotationChains = chainsFrom(annotation, prefix, visiting);
                chains.addAll(annotationChains.chains());
                repeated |= annotationChains.repeated() || (group.repeated() && !annotationChains.chains().isEmpty());
            }
        }
        return new Chains(chains, repeated);
    }

    private static Chains chainsFrom(Annotation annotation,
                                     List<Annotation> prefix,
                                     Set<Class<? extends Annotation>> visiting) {
        List<Annotation> chain = new ArrayList<>(prefix);
        chain.add(annotation);
        Class<? extends Annotation> type = annotation.annotationType();
        if (type.equals(MessageHandler.class)) {
            return new Chains(List.of(chain), false);
        }
        if (!visiting.add(type)) {
            return new Chains(List.of(), false);
        }
        try {
            for (HandlerGroup group : handlerGroupsOf(type, null)) {
                Chains chains = chainsOf(List.of(group), chain, visiting);
                if (!chains.chains().isEmpty()) {
                    return chains;
                }
            }
            return new Chains(List.of(), false);
        } finally {
            visiting.remove(type);
        }
    }

    /**
     * Returns the attributes of the annotation at the given {@code position} in the {@code chain}, overridden by the
     * same-named attributes of the annotations preceding it, with the annotation closest to the method taking
     * precedence.
     */
    private static Map<String, Object> attributesInChain(List<Annotation> chain, int position) {
        Map<String, Object> attributes = new HashMap<>(AnnotationUtils.attributesOf(chain.get(position)));
        for (int i = position - 1; i >= 0; i--) {
            AnnotationUtils.attributesOf(chain.get(i))
                           .forEach((name, value) -> attributes.computeIfPresent(name, (n, previous) -> value));
        }
        return attributes;
    }

    private static boolean isMessageHandlerAnnotation(Class<? extends Annotation> type) {
        return type.equals(MessageHandler.class)
                || AnnotationUtils.isAnnotatedWith(type, MessageHandler.class, new HashSet<>(), new HashSet<>());
    }

    private static boolean hasHandlerAttributes(Class<? extends Annotation> type) {
        return AnnotationUtils.isAnnotatedWith(type, HasHandlerAttributes.class, new HashSet<>(), new HashSet<>());
    }

    /**
     * Returns the method this occurrence describes a message handler annotation of.
     *
     * @return the method this occurrence describes a message handler annotation of
     */
    public Method method() {
        return method;
    }

    /**
     * Returns the position of this occurrence among the message handler annotations of the {@link #method()}, in
     * declaration order and starting at {@code 0}.
     *
     * @return the position of this occurrence among the message handler annotations of the {@link #method()}
     */
    public int index() {
        return index;
    }

    /**
     * Returns the handler attributes of this occurrence, keyed like the attributes of
     * {@link AnnotatedHandlerAttributes}: the simple name of the annotation type, a dot, and the attribute name.
     *
     * @return the handler attributes of this occurrence
     */
    public Map<String, Object> handlerAttributes() {
        return Map.copyOf(handlerAttributes);
    }

    /**
     * Returns the attributes of the {@link MessageHandler @MessageHandler} annotation this occurrence leads to,
     * overridden by the same-named attributes of the annotations closer to the method.
     *
     * @return the attributes of the {@link MessageHandler @MessageHandler} annotation this occurrence leads to
     */
    public Map<String, Object> messageHandlerAttributes() {
        return messageHandlerAttributes;
    }

    @Override
    public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationClass) {
        return AnnotationsView.find(annotations, annotationClass);
    }

    @Override
    public Annotation[] getAnnotations() {
        return annotations.clone();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return annotations.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HandlerAnnotationOccurrence that = (HandlerAnnotationOccurrence) o;
        return index == that.index && method.equals(that.method);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, index);
    }

    @Override
    public String toString() {
        return method.toGenericString() + "#" + index;
    }

    /**
     * The message handler annotations of a single type on an element. A group is {@code repeated} when the annotations
     * were found through a container annotation.
     */
    private record HandlerGroup(List<Annotation> annotations, boolean repeated) {

    }

    /**
     * The chains of annotations leading to a {@link MessageHandler}. The chains are {@code repeated} when any container
     * annotation of message handler annotations was found while collecting them.
     */
    private record Chains(List<List<Annotation>> chains, boolean repeated) {

    }

    /**
     * An {@link AnnotatedElement} exposing a fixed set of annotations.
     */
    private record AnnotationsView(List<Annotation> annotations) implements AnnotatedElement {

        private static <A extends Annotation> @Nullable A find(Annotation[] annotations, Class<A> annotationClass) {
            for (Annotation annotation : annotations) {
                if (annotationClass.isInstance(annotation)) {
                    return annotationClass.cast(annotation);
                }
            }
            return null;
        }

        @Override
        public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationClass) {
            return find(getAnnotations(), annotationClass);
        }

        @Override
        public Annotation[] getAnnotations() {
            return annotations.toArray(Annotation[]::new);
        }

        @Override
        public Annotation[] getDeclaredAnnotations() {
            return getAnnotations();
        }
    }
}
