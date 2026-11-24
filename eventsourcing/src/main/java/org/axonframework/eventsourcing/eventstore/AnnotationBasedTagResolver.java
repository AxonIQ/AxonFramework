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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.EventTags;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.getMemberValue;

/**
 * Implementation of {@link TagResolver} that processes {@link EventTag} and {@link EventTags} annotation on fields and
 * methods of event payload objects to create {@link Tag} instances. Supports inherited fields and methods.
 * <p>
 * The {@link EventTag} annotation can be used in two ways:
 * <ul>
 *     <li>Directly on fields and methods using the repeatable syntax:
 *         <pre>
 *         {@literal @}EventTag
 *         {@literal @}EventTag(key = "identifier")
 *         private String id;
 *         </pre>
 *     </li>
 *     <li>Using the container annotation {@link EventTags}:
 *         <pre>
 *         {@literal @}EventTags({
 *             {@literal @}EventTag,
 *             {@literal @}EventTag(key = "identifier")
 *         })
 *         private String id;
 *         </pre>
 *     </li>
 * </ul>
 * Both approaches are equivalent and will produce the same tags.
 * <p>
 * Besides direct use, the {@link EventTag} annotation is also detected when it is used as a meta-annotation, on any
 * level:
 * <pre>
 * {@literal @}Retention(RetentionPolicy.RUNTIME)
 * {@literal @}Target({ElementType.FIELD, ElementType.METHOD})
 * {@literal @}EventTag(key = "orderId")
 * public {@literal @}interface OrderIdTag {
 * }
 *
 * {@literal @}OrderIdTag
 * private String id;
 * </pre>
 * Every occurrence of the {@link EventTag} annotation on a member, whether direct, repeated or meta-annotated,
 * results in tags for that member. An annotation meta-annotated with {@link EventTag} may declare a {@code key}
 * attribute itself, which then overrides the {@link EventTag#key()} of every {@link EventTag} meta-annotation on it.
 * <p>
 * The tagged fields and methods are inspected once per payload type and cached for subsequent resolutions.
 *
 * @author Mateusz Nowak
 * @see EventTag for more information on how to use the annotation
 * @see EventTags for more information on how to use the annotation
 * @since 5.0.0
 */
public class AnnotationBasedTagResolver implements TagResolver {

    private static final Class<EventTag> EVENT_TAG_ANNOTATION = EventTag.class;
    private static final String KEY_ATTRIBUTE = "key";

    private final ClassValue<List<TaggedMember>> taggedMembersCache = new ClassValue<>() {
        @Override
        protected List<TaggedMember> computeValue(Class<?> payloadType) {
            return taggedMembersOf(payloadType);
        }
    };

    @Override
    public Set<Tag> resolve(EventMessage event) {
        Objects.requireNonNull(event, "Event cannot be null");
        var payload = event.payload();
        if (payload == null) {
            return Set.of();
        }
        return taggedMembersCache.get(payload.getClass())
                                 .stream()
                                 .flatMap(taggedMember -> tagsFrom(taggedMember, payload).stream())
                                 .collect(Collectors.toSet());
    }

    private List<TaggedMember> taggedMembersOf(Class<?> payloadType) {
        var taggedMembers = new ArrayList<TaggedMember>();
        for (Field field : ReflectionUtils.fieldsOf(payloadType)) {
            taggedMemberOf(field, field.getName()).ifPresent(taggedMembers::add);
        }
        for (Method method : ReflectionUtils.methodsOf(payloadType)) {
            var taggedMethod = taggedMemberOf(method, getMemberIdentifierName(method));
            if (taggedMethod.isPresent()) {
                assertValidTagMethod(method);
                taggedMembers.add(taggedMethod.get());
            }
        }
        return List.copyOf(taggedMembers);
    }

    private static <M extends AnnotatedElement & Member> Optional<TaggedMember> taggedMemberOf(M member,
                                                                                               String memberName) {
        var keys = AnnotationUtils.findAllAnnotationAttributes(member, EVENT_TAG_ANNOTATION)
                                  .stream()
                                  .map(attributes -> (String) attributes.get(KEY_ATTRIBUTE))
                                  .distinct()
                                  .toList();
        return keys.isEmpty() ? Optional.empty() : Optional.of(new TaggedMember(member, memberName, keys));
    }

    private Set<Tag> tagsFrom(TaggedMember taggedMember, Object payload) {
        var member = taggedMember.member();
        try {
            Object value = getMemberValue(member, payload);
            if (value == null) {
                return Set.of();
            }

            var tags = new HashSet<Tag>();
            for (var key : taggedMember.keys()) {
                tags.addAll(createTagsForValue(value, taggedMember.name(), key));
            }
            return tags;
        } catch (Exception e) {
            throw new TagResolutionException(
                    "Failed to resolve tag from " + (member instanceof Field ? "field: " : "method: ")
                            + member.getName(),
                    e
            );
        }
    }

    private Set<Tag> createTagsForValue(Object value, String memberName, String annotationKey) {
        if (value instanceof Iterable<?> iterable) {
            var key = annotationKeyOrMemberName(annotationKey, memberName);
            return StreamSupport.stream(iterable.spliterator(), false)
                                .filter(Objects::nonNull)
                                .map(item -> new Tag(key, item.toString()))
                                .collect(Collectors.toSet());
        }
        if (value instanceof Map<?, ?> map) {
            var annotationKeyProvided = !annotationKey.isBlank();
            if (annotationKeyProvided) {
                // If key provided in annotation, use it for all values
                return map.values().stream()
                          .filter(Objects::nonNull)
                          .map(val -> new Tag(annotationKey, val.toString()))
                          .collect(Collectors.toSet());
            }
            // If no key provided in annotation, use map keys regardless of property name
            return map.entrySet().stream()
                      .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                      .map(entry -> new Tag(entry.getKey().toString(), entry.getValue().toString()))
                      .collect(Collectors.toSet());
        }

        var key = annotationKeyOrMemberName(annotationKey, memberName);
        return Set.of(new Tag(key, value.toString()));
    }

    private static String annotationKeyOrMemberName(String annotationKey, String memberName) {
        return annotationKey.isBlank() ? memberName : annotationKey;
    }

    private void assertValidTagMethod(Method method) {
        if (method.getParameterCount() > 0) {
            throw new TagResolutionException(format(
                    "The @%s annotated method [%s] should not contain any parameters"
                            + " as none are allowed on event Tag providers",
                    EVENT_TAG_ANNOTATION.getSimpleName(), method
            ));
        }
        if (void.class.equals(method.getReturnType())) {
            throw new TagResolutionException(format(
                    "The @%s annotated method [%s] should not return void",
                    EVENT_TAG_ANNOTATION.getSimpleName(), method
            ));
        }
    }

    /**
     * Return the given {@code member}'s name. If the given {@code member} is of type {@link Method} and it resembles a
     * regular getter method, the {@code "get"} will be stripped off.
     *
     * @param member the {@link Member} to retrieve the name for
     * @return the identifier name tied to the given {@code member}
     */
    private String getMemberIdentifierName(Member member) {
        var identifierName = member.getName();
        return member instanceof Method && isGetterByConvention(identifierName)
                ? stripGetterConvention(identifierName)
                : identifierName;
    }

    private boolean isGetterByConvention(String identifierName) {
        return identifierName.startsWith("get")
                && identifierName.length() >= 4
                && Character.isUpperCase(identifierName.charAt(3));
    }

    private String stripGetterConvention(String identifierName) {
        return identifierName.substring(3, 4).toLowerCase() + identifierName.substring(4);
    }

    /**
     * A field or method of an event payload type that provides tags, together with the tag keys defined through
     * (meta-annotated) {@link EventTag} annotations on that member.
     *
     * @param member the field or method providing the tag value
     * @param name   the identifier name of the member, used as the tag key when no key is defined
     * @param keys   the keys defined on the {@link EventTag} occurrences on the member, which may be empty strings
     */
    private record TaggedMember(Member member, String name, List<String> keys) {

    }

    /**
     * Exception thrown when tag resolution fails.
     */
    public static class TagResolutionException extends RuntimeException {

        private TagResolutionException(String message) {
            super(message);
        }

        private TagResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}