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

package org.axonframework.modelling.entity.child;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.stream.StreamSupport;

import static org.axonframework.common.StringUtils.capitalize;

/**
 * A {@link ChildEntityFieldDefinition} that uses a field to access the child entity. If getters or setters are
 * available, it will use those instead.
 *
 * @param <P> The type of the parent entity.
 * @param <F> The type of the field. This can be the type of the child entity or a collection of child entities.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class FieldChildEntityFieldDefinition<P, F> implements ChildEntityFieldDefinition<P, F>, DescribableComponent {

    private final Field field;
    private final Class<P> parentClass;
    private final String fieldName;
    @Nullable
    private Method optionalGetter;
    @Nullable
    private Method optionalSetter;

    /**
     * Creates a new {@link ChildEntityFieldDefinition} that uses the given field to access the child entity.
     *
     * @param parentClass The class of the parent entity.
     * @param fieldName   The name of the field to use to access the child entity.
     */
    public FieldChildEntityFieldDefinition(
            Class<P> parentClass,
            String fieldName
    ) {
        this.parentClass = Objects.requireNonNull(parentClass, "The parentClass may not be null.");
        this.fieldName = Objects.requireNonNull(fieldName, "The fieldName may not be null.");
        this.field = StreamSupport
                .stream(ReflectionUtils.fieldsOf(parentClass).spliterator(), false)
                .filter(f -> f.getName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Field [%s] not found in class [%s]",
                                                                          fieldName,
                                                                          parentClass.getName())));
        ReflectionUtils.ensureAccessible(field);
        detectOptionalGetter(parentClass, field);
        detectOptionalSetter(parentClass, field);
    }

    /**
     * Detects the optional getter for the given field.
     * <p>
     * It will look for a method with the same name as the field, or a method with the name "get" + the field name, as
     * well as matching the return type to the field type.
     */
    private void detectOptionalGetter(Class<P> parentClass, Field field) {
        this.optionalGetter = StreamSupport.stream(ReflectionUtils.methodsOf(parentClass).spliterator(), false)
                                           .filter(m -> m.getParameterCount() == 0)
                                           .filter(m -> field.getType().isAssignableFrom(m.getReturnType()))
                                           .filter(m -> m.getName().equals(field.getName())
                                                   || m.getName().equals("get" + capitalize(field.getName())))
                                           .findFirst()
                                           .orElse(null);
        if (this.optionalGetter != null) {
            ReflectionUtils.ensureAccessible(this.optionalGetter);
        }
    }

    /**
     * Detects the optional setter for the given field.
     * <p>
     * It will look for a method with the same name as the field, the name "set" + the field name, or the name "evolve"
     * + the field name, as well as matching the field type against the parameter type.
     */
    private void detectOptionalSetter(Class<P> parentClass, Field field) {
        this.optionalSetter = StreamSupport.stream(ReflectionUtils.methodsOf(parentClass).spliterator(), false)
                                           .filter(m -> m.getParameterCount() == 1)
                                           .filter(m -> m.getParameterTypes()[0].isAssignableFrom(field.getType()))
                                           .filter(m -> m.getName().equals(field.getName())
                                                   || m.getName().equals("set" + capitalize(field.getName()))
                                                   || m.getName().equals("evolve" + capitalize(field.getName())))
                                           .findFirst()
                                           .orElse(null);
        if (this.optionalSetter != null) {
            ReflectionUtils.ensureAccessible(this.optionalSetter);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public P evolveParentBasedOnChildInput(P parentEntity, F childInput) {
        try {
            if (optionalSetter != null) {
                Object invokeResult = optionalSetter.invoke(parentEntity, childInput);
                if (invokeResult != null) {
                    if(!parentClass.isAssignableFrom(invokeResult.getClass())) {
                        throw new IllegalArgumentException(
                                String.format("Evolve method [%s] must return a type compatible with entity type [%s]",
                                              optionalSetter.getName(), parentClass.getName()));
                    }
                    // Is an evolve method
                    return (P) invokeResult;
                }
            }
            field.set(parentEntity, childInput);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return parentEntity;
    }

    @SuppressWarnings("unchecked")
    @Override
    public F getChildValue(P parentEntity) {
        try {
            if (optionalGetter != null) {
                return (F) optionalGetter.invoke(parentEntity);
            }
            return (F) field.get(parentEntity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("parentClass", parentClass);
        descriptor.describeProperty("fieldName", fieldName);
        descriptor.describeProperty("field", field);
        descriptor.describeProperty("optionalGetter", optionalGetter);
        descriptor.describeProperty("optionalSetter", optionalSetter);
    }
}
