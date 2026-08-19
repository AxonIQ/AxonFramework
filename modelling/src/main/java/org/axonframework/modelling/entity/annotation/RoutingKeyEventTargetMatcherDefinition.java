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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.modelling.entity.child.EventTargetMatcher;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.util.Map;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.getMemberValueType;

/**
 * Definition for creating {@link EventTargetMatcher} instances based on the
 * {@link EntityMember#routingKey routing key attribute}.
 * <p>
 * The routing key of <b>both</b> the message and entity is determined by the {@link EntityMember#routingKey attribute}
 * on the declaring member in the parent entity. The routing key of the message and of the entity are matched to
 * determine if a child entity should handle a given message.
 *
 * @author Mitchell Herrijgers
 * @see RoutingKeyEventTargetMatcher
 * @since 5.0.0
 */
public class RoutingKeyEventTargetMatcherDefinition implements EventTargetMatcherDefinition {

    @Override
    public <E> EventTargetMatcher<E> createChildEntityMatcher(AnnotatedEntityMetamodel<E> entity,
                                                              Member member) {
        String routingKey = RoutingKeyUtils.getMessageRoutingKey((AnnotatedElement) member).orElse(null);
        if (routingKey != null) {
            return new RoutingKeyEventTargetMatcher<>(entity, routingKey, routingKey);
        }

        // No routing key found, perhaps we are dealing with a single occurrence entity member.
        Class<?> memberValueType = getMemberValueType(member);
        if (Iterable.class.isAssignableFrom(memberValueType) || Map.class.isAssignableFrom(memberValueType)) {
            throw new AxonConfigurationException(
                    format("Member [%s] of type [%s] is a collection type, but the child does not define a routing key. "
                                   + "Please set the \"routingKey\" property on the @EntityMember annotation "
                                   + "or implement a custom EventTargetMatcher for this collection type.",
                           member, memberValueType)
            );
        }
        // If the member is not a collection type, we can assume it is a single entity.
        // This does not require an explicit @RoutingKey, as there might only be one in the aggregate.
        // If the user has multiple single-entity child entities in the same parent, commands will lead to a ChildAmbiguityException, which is clear enough.
        return EventTargetMatcher.MATCH_ANY();
    }
}
