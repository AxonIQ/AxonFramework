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
import org.axonframework.common.ReflectionUtils;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.child.ChildEntityFieldDefinition;
import org.axonframework.modelling.entity.child.CommandTargetResolver;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;
import org.axonframework.modelling.entity.child.EventTargetMatcher;
import org.axonframework.modelling.entity.child.MapEntityChildMetamodel;

import java.lang.reflect.Member;
import java.util.Map;

import static java.lang.String.format;
import static org.axonframework.common.ReflectionUtils.resolveMemberGenericType;

/**
 * {@link EntityChildModelDefinition} for creating {@link EntityChildMetamodel} instances for child entities that are
 * represented as a {@link Map}.
 * <p>
 * It resolves the child type from the member's value generic type and creates a {@link MapEntityChildMetamodel}
 * accordingly. The key type of the {@link Map} is not resolved, as it is not needed to construct the child
 * {@link EntityMetamodel}: the key of an entry is preserved as-is when the entity is evolved.
 *
 * @author Steven van Beelen
 * @since 5.3.0
 */
public class MapEntityChildModelDefinition extends AbstractEntityChildModelDefinition {

    @Override
    protected boolean isMemberTypeSupported(Class<?> memberType) {
        return Map.class.isAssignableFrom(memberType);
    }

    @Override
    protected Class<?> getChildTypeFromMember(Member member) {
        return getChildTypeFromMap(member);
    }

    @Override
    protected <C, P> EntityChildMetamodel<C, P> doCreate(
            Class<P> parentClass,
            EntityMetamodel<C> entityMetamodel,
            String fieldName,
            EventTargetMatcher<C> eventTargetMatcher,
            CommandTargetResolver<C> commandTargetResolver
    ) {
        ChildEntityFieldDefinition<P, Map<Object, C>> fieldDefinition =
                ChildEntityFieldDefinition.forFieldName(parentClass, fieldName);
        return MapEntityChildMetamodel.forEntityModel(parentClass, entityMetamodel)
                                      .childEntityFieldDefinition(fieldDefinition)
                                      .commandTargetResolver(commandTargetResolver)
                                      .eventTargetMatcher(eventTargetMatcher)
                                      .build();
    }

    @SuppressWarnings("unchecked")
    private <C> Class<C> getChildTypeFromMap(Member member) {
        return (Class<C>) resolveMemberGenericType(member, 1).orElseThrow(
                () -> new AxonConfigurationException(format(
                        "Unable to resolve entity type of member [%s].", ReflectionUtils.getMemberGenericString(member)
                )));
    }
}
