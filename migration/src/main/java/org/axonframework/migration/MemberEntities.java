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

package org.axonframework.migration;

import org.jspecify.annotations.Nullable;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.axonframework.migration.SagaClasses.isAnnotation;
import static org.axonframework.migration.SagaClasses.resolvedName;

/**
 * Tells the member entities of Axon Framework 4 aggregates apart from the aggregate roots, from the
 * {@code @AggregateMember} fields declaring them.
 * <p>
 * The deadline recipes treat a member entity differently from its aggregate root: Axon Framework 4 described the scope
 * of the aggregate the entity belongs to, and delivered a fired deadline to the root and to every member entity. The
 * scanning phase of a recipe {@link #collect(J.ClassDeclaration) collects} every class, after which the owner and root
 * of any class can be looked up. Package-private, as it only shares this detection between the deadline recipes of
 * this module.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class MemberEntities {

    private static final String AGGREGATE_MEMBER_FQN = "org.axonframework.modelling.command.AggregateMember";

    private final Map<String, String> owners = new HashMap<>();

    /**
     * Records the entity types of the {@code @AggregateMember} fields of the given class with that class as their
     * owner: the field type itself, the element type of a collection, or the value type of a map.
     *
     * @param classDeclaration the class to inspect
     */
    void collect(J.ClassDeclaration classDeclaration) {
        JavaType.FullyQualified owner = classDeclaration.getType();
        if (owner == null) {
            return;
        }
        for (Statement statement : classDeclaration.getBody().getStatements()) {
            if (!(statement instanceof J.VariableDeclarations field)
                    || field.getLeadingAnnotations().stream()
                            .noneMatch(annotation -> isAnnotation(annotation,
                                                                  AGGREGATE_MEMBER_FQN,
                                                                  "AggregateMember"))) {
                continue;
            }
            JavaType type = field.getType();
            if (type instanceof JavaType.Parameterized parameterized
                    && !parameterized.getTypeParameters().isEmpty()) {
                type = parameterized.getTypeParameters().getLast();
            }
            String name = resolvedName(type);
            if (name != null) {
                owners.put(name, owner.getFullyQualifiedName());
            }
        }
    }

    /**
     * Returns the class declaring the {@code @AggregateMember} field of the given entity class.
     *
     * @param className the fully qualified name of the class
     * @return the fully qualified name of the owning class, or {@code null} when the class is no member entity
     */
    @Nullable String ownerOf(String className) {
        return owners.get(className);
    }

    /**
     * Returns the aggregate root a class belongs to, following the owners of nested member entities.
     *
     * @param className the fully qualified name of the class
     * @return the fully qualified name of the aggregate root, which is the class itself when it is no member entity
     */
    String rootOf(String className) {
        String root = className;
        Set<String> visited = new HashSet<>();
        while (visited.add(root) && owners.containsKey(root)) {
            root = owners.get(root);
        }
        return root;
    }
}
