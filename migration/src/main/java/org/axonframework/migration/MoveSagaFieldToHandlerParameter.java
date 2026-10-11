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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves a Saga field of a configured type into a parameter of the Saga methods that use it, because the Axoniq
 * Framework legacy module does not inject Saga fields.
 * <p>
 * Axon Framework 4 populated {@code @Autowired} or {@code @Inject} fields of a Saga instance through a
 * {@code ResourceInjector}. The legacy module resolves Saga collaborators as handler method parameters instead, so an
 * injected field stays {@code null}. For the configured {@link #getFullyQualifiedTypeName() type}, this recipe:
 * <ul>
 *     <li>adds a parameter of that type, named after the field, to every {@code @SagaEventHandler} and
 *     {@code @DeadlineHandler} method that uses the field, directly or through private helper methods. A parameter of
 *     that type the method already declares is reused instead;</li>
 *     <li>adds the same parameter to those private helper methods, and passes it along at their call sites;</li>
 *     <li>removes the field, together with its annotations and leading comments, once nothing refers to it.</li>
 * </ul>
 * The calls on the collaborator itself are left as they are. When the field name clashes with a parameter or local
 * variable of a method, the new parameter gets a numeric suffix and the field references in that method are renamed.
 * <p>
 * The migration is all-or-nothing per field. A use the recipe cannot move into a handler parameter leaves the field and
 * every one of its uses untouched, rather than producing a half-migrated Saga. Such a use is one in a constructor, a
 * static method, a non-private method that is not a handler (it may be called from outside the class), a field
 * initializer or initializer block, a nested, local or anonymous class, a method reference to an affected method, an
 * assignment to the field, or an access through another instance. The same applies when the class declares more than
 * one such field. Uses in other classes, such as subclasses, are not visible to the recipe. A field left in place is
 * marked by {@link MarkSagaResourceInjection} when it is injected.
 * <p>
 * Only Sagas are changed: classes annotated with the Spring {@code @Saga} stereotype or declaring a
 * {@code @SagaEventHandler} method. Other components, such as aggregates or Spring services, keep their fields, because
 * they are still injected there. Java and Kotlin sources are supported. The recipe is idempotent.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MoveSagaFieldToHandlerParameter extends Recipe {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String DEADLINE_HANDLER_FQN = "org.axonframework.deadline.annotation.DeadlineHandler";
    private static final String SAGA_STEREOTYPE_FQN = "org.axonframework.spring.stereotype.Saga";
    private static final Map<String, String> INJECTION_ANNOTATIONS = Map.of(
            "org.springframework.beans.factory.annotation.Autowired", "Autowired",
            "javax.inject.Inject", "Inject",
            "jakarta.inject.Inject", "Inject"
    );
    private static final String PLAN_MESSAGE = "axon.moveSagaFieldToHandlerParameter";

    @Option(displayName = "Fully-qualified type name",
            description = "The type of the Saga field to move into handler method parameters.",
            example = "org.axonframework.deadline.DeadlineManager")
    private final String fullyQualifiedTypeName;

    /**
     * Creates the recipe for Saga fields of the given type.
     *
     * @param fullyQualifiedTypeName the fully qualified name of the field type to move into handler parameters
     */
    @JsonCreator
    public MoveSagaFieldToHandlerParameter(@JsonProperty("fullyQualifiedTypeName") String fullyQualifiedTypeName) {
        this.fullyQualifiedTypeName = fullyQualifiedTypeName;
    }

    /**
     * Returns the fully qualified name of the field type this recipe moves into handler parameters.
     *
     * @return the fully qualified name of the field type
     */
    public String getFullyQualifiedTypeName() {
        return fullyQualifiedTypeName;
    }

    @Override
    public String getDisplayName() {
        return "Move a Saga field into handler method parameters";
    }

    @Override
    public String getDescription() {
        return "Replaces a Saga field of the configured type with a parameter of each `@SagaEventHandler` and "
                + "`@DeadlineHandler` method that uses it, passing it on to the private helper methods that need it, "
                + "and removes the field. The Axoniq Framework legacy module does not inject Saga fields. A field "
                + "with a use that cannot become a handler parameter is left untouched entirely.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                Plan plan = isSaga(classDeclaration) ? planFor(classDeclaration) : null;
                if (plan == null) {
                    return super.visitClassDeclaration(classDeclaration, ctx);
                }
                getCursor().putMessage(PLAN_MESSAGE, plan);
                J.ClassDeclaration migrated = super.visitClassDeclaration(classDeclaration, ctx);
                migrated = removeField(migrated, plan.fieldId());
                removeUnusedImports(migrated, plan);
                return migrated;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                Plan plan = getCursor().getNearestMessage(PLAN_MESSAGE);
                MethodPlan methodPlan = plan == null ? null : plan.methods().get(method.getId());
                if (methodPlan == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                J.MethodDeclaration migrated = methodPlan.addsParameter()
                        ? addParameter(method, methodPlan.parameterName())
                        : method;
                migrated = (J.MethodDeclaration) new JavaVisitor<ExecutionContext>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier, ExecutionContext executionContext) {
                        return plan.references().contains(identifier.getId())
                                ? identifier.withSimpleName(methodPlan.parameterName())
                                : identifier;
                    }

                    @Override
                    public J visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext executionContext) {
                        if (!plan.references().contains(fieldAccess.getId())) {
                            return super.visitFieldAccess(fieldAccess, executionContext);
                        }
                        return new J.Identifier(Tree.randomId(), fieldAccess.getPrefix(), fieldAccess.getMarkers(),
                                                Collections.emptyList(), methodPlan.parameterName(),
                                                fieldAccess.getType(), null);
                    }

                    @Override
                    public J visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext executionContext) {
                        J visited = super.visitMethodInvocation(invocation, executionContext);
                        return visited instanceof J.MethodInvocation call && plan.calls().contains(call.getId())
                                ? withArgument(call, methodPlan.parameterName())
                                : visited;
                    }
                }.visitNonNull(migrated, ctx, getCursor().getParentOrThrow());
                if (methodPlan.addsParameter()) {
                    maybeAddImport(fullyQualifiedTypeName, null, false);
                }
                return migrated;
            }

            private J.MethodDeclaration addParameter(J.MethodDeclaration method, String parameterName) {
                List<Statement> existing = method.getParameters();
                boolean hasExisting = !(existing.size() == 1 && existing.get(0) instanceof J.Empty);
                StringBuilder parameters = new StringBuilder();
                List<Object> templateArguments = new ArrayList<>();
                boolean kotlin = getCursor().firstEnclosing(SourceFile.class) instanceof K.CompilationUnit;
                if (hasExisting) {
                    for (Statement parameter : existing) {
                        // trim: the printed parameter carries its own leading space, the separator adds one
                        String printed = parameter.print(getCursor()).trim();
                        if (kotlin) {
                            parameters.append(printed);
                        } else {
                            parameters.append("#{}");
                            templateArguments.add(printed);
                        }
                        parameters.append(", ");
                    }
                }
                if (kotlin) {
                    parameters.append(parameterName).append(": ").append(simpleTypeName());
                    return addKotlinParameters(method, parameters.toString());
                }
                parameters.append(simpleTypeName()).append(' ').append(parameterName);
                return JavaTemplate.builder(parameters.toString())
                                   .imports(fullyQualifiedTypeName)
                                   .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                                   .build()
                                   .apply(getCursor(), method.getCoordinates().replaceParameters(),
                                          templateArguments.toArray());
            }

            /**
             * Kotlin has no {@code JavaTemplate}: the parameter list is parsed from a stub function and grafted onto
             * the method, as {@link MigrateSagaLifecycle} does.
             */
            private J.MethodDeclaration addKotlinParameters(J.MethodDeclaration method, String parameters) {
                String snippet = "package _temp\n\nimport " + fullyQualifiedTypeName + "\n\nfun _f("
                        + parameters + ") {}\n";
                List<SourceFile> parsed;
                try {
                    parsed = KotlinParser.builder().build().parse(snippet)
                                         .filter(source -> source instanceof K.CompilationUnit)
                                         .toList();
                } catch (RuntimeException exception) {
                    return method;
                }
                if (parsed.isEmpty()) {
                    return method;
                }
                for (Statement statement : ((K.CompilationUnit) parsed.get(0)).getStatements()) {
                    if (statement instanceof J.MethodDeclaration stub) {
                        return method.getPadding().withParameters(stub.getPadding().getParameters());
                    }
                }
                return method;
            }

            private J.MethodInvocation withArgument(J.MethodInvocation invocation, String name) {
                J.Identifier argument = new J.Identifier(Tree.randomId(), Space.format(" "), invocation.getMarkers(),
                                                         Collections.emptyList(), name, null, null);
                List<Expression> arguments = invocation.getArguments();
                if (arguments.size() == 1 && arguments.get(0) instanceof J.Empty) {
                    return invocation.withArguments(Collections.singletonList(argument.withPrefix(Space.EMPTY)));
                }
                List<Expression> extended = new ArrayList<>(arguments);
                extended.add(argument);
                return invocation.withArguments(extended);
            }

            /**
             * Removes the import of the moved type and of the injection annotations once the class no longer needs
             * them. An annotation import is only offered for removal when no annotation of that simple name remains in
             * the source file, because an unresolved annotation type would otherwise lose an import another field still
             * needs.
             */
            private void removeUnusedImports(J.ClassDeclaration migrated, Plan plan) {
                if (plan.methods().isEmpty()) {
                    maybeRemoveImport(fullyQualifiedTypeName);
                }
                SourceFile sourceFile = getCursor().firstEnclosing(SourceFile.class);
                Set<String> annotationsInUse = annotationNames(migrated, null);
                if (sourceFile != null) {
                    annotationsInUse.addAll(annotationNames(sourceFile, migrated.getId()));
                }
                INJECTION_ANNOTATIONS.forEach((fqn, simpleName) -> {
                    if (!annotationsInUse.contains(simpleName)) {
                        maybeRemoveImport(fqn);
                    }
                });
            }
        };
    }

    /**
     * Analyses the class for the single field of the configured type and returns how to move it, or {@code null} when
     * the class has no such field or the field has a use that cannot become a handler parameter.
     */
    private @Nullable Plan planFor(J.ClassDeclaration classDeclaration) {
        J.VariableDeclarations field = candidateField(classDeclaration);
        if (field == null) {
            return null;
        }
        String fieldName = field.getVariables().get(0).getSimpleName();
        Usages usages = new Usages();
        Map<UUID, J.MethodDeclaration> methods = new HashMap<>();
        for (Statement statement : classDeclaration.getBody().getStatements()) {
            if (statement == field) {
                continue;
            }
            J.MethodDeclaration method = statement instanceof J.MethodDeclaration declaration ? declaration : null;
            if (method != null) {
                methods.put(method.getId(), method);
            }
            new UsageCollector(classDeclaration, fieldName, method, usages).visit(statement, 0);
        }
        if (usages.unmigratable) {
            return null;
        }

        Set<UUID> needing = new LinkedHashSet<>();
        for (Site reference : usages.references) {
            if (reference.method() == null || reference.nested()) {
                return null;
            }
            needing.add(reference.method());
        }
        Map<UUID, Set<UUID>> callTargets = new HashMap<>();
        for (CallSite call : usages.calls) {
            callTargets.put(call.site().id(), targetsOf(call, methods.values()));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CallSite call : usages.calls) {
                Set<UUID> targets = callTargets.get(call.site().id());
                if (targets.stream().noneMatch(needing::contains)) {
                    continue;
                }
                if (targets.size() > 1 || call.site().method() == null || call.site().nested()) {
                    return null;
                }
                changed |= needing.add(call.site().method());
            }
        }
        for (UUID id : needing) {
            J.MethodDeclaration method = methods.get(id);
            if (usages.methodReferences.contains(method.getSimpleName())
                    || isConstructor(method)
                    || method.hasModifier(J.Modifier.Type.Static)
                    || !(isHandler(method) || method.hasModifier(J.Modifier.Type.Private))) {
                return null;
            }
        }

        Map<UUID, MethodPlan> methodPlans = new HashMap<>();
        for (UUID id : needing) {
            J.MethodDeclaration method = methods.get(id);
            String existing = parameterOfType(method);
            methodPlans.put(id, existing != null
                    ? new MethodPlan(existing, false)
                    : new MethodPlan(availableName(fieldName, declaredNames(method)), true));
        }
        Set<UUID> references = new HashSet<>();
        for (Site reference : usages.references) {
            references.add(reference.id());
        }
        Set<UUID> calls = new HashSet<>();
        for (CallSite call : usages.calls) {
            Set<UUID> targets = callTargets.get(call.site().id());
            if (targets.size() == 1 && methodPlans.containsKey(targets.iterator().next())
                    && methodPlans.get(targets.iterator().next()).addsParameter()) {
                calls.add(call.site().id());
            }
        }
        return new Plan(field.getId(), methodPlans, references, calls);
    }

    /**
     * Returns the one instance field of the configured type without an initializer, or {@code null} when there is none
     * or more than one.
     */
    private J.@Nullable VariableDeclarations candidateField(J.ClassDeclaration classDeclaration) {
        J.VariableDeclarations candidate = null;
        for (Statement statement : classDeclaration.getBody().getStatements()) {
            if (statement instanceof J.VariableDeclarations declarations
                    && !declarations.hasModifier(J.Modifier.Type.Static)
                    && isConfiguredType(declarations.getTypeExpression())) {
                if (candidate != null || declarations.getVariables().size() != 1
                        || declarations.getVariables().get(0).getInitializer() != null) {
                    return null;
                }
                candidate = declarations;
            }
        }
        return candidate;
    }

    /**
     * Resolves the own-class methods a call may target: by name and arity, narrowed by the attributed parameter types
     * when overloads share both.
     */
    private static Set<UUID> targetsOf(CallSite call, Iterable<J.MethodDeclaration> methods) {
        List<J.MethodDeclaration> candidates = new ArrayList<>();
        for (J.MethodDeclaration method : methods) {
            if (!isConstructor(method) && method.getSimpleName().equals(call.name())
                    && parameterCount(method) == call.arity()) {
                candidates.add(method);
            }
        }
        if (candidates.size() > 1 && call.type() != null) {
            List<J.MethodDeclaration> matching = new ArrayList<>();
            for (J.MethodDeclaration candidate : candidates) {
                JavaType.Method declared = candidate.getMethodType();
                if (declared != null && sameParameterTypes(declared, call.type())) {
                    matching.add(candidate);
                }
            }
            if (matching.size() == 1) {
                candidates = matching;
            }
        }
        Set<UUID> targets = new LinkedHashSet<>();
        candidates.forEach(candidate -> targets.add(candidate.getId()));
        return targets;
    }

    /**
     * Kotlin functions without a declared return type look like constructors to
     * {@link J.MethodDeclaration#isConstructor()}, so the attributed method type decides when there is one.
     */
    private static boolean isConstructor(J.MethodDeclaration method) {
        JavaType.Method type = method.getMethodType();
        if (type != null) {
            return type.isConstructor();
        }
        String name = method.getSimpleName();
        return name.equals("constructor")
                || (method.isConstructor() && !name.isEmpty() && Character.isUpperCase(name.charAt(0)));
    }

    private static boolean sameParameterTypes(JavaType.Method declared, JavaType.Method invoked) {
        List<JavaType> declaredTypes = declared.getParameterTypes();
        List<JavaType> invokedTypes = invoked.getParameterTypes();
        if (declaredTypes.size() != invokedTypes.size()) {
            return false;
        }
        for (int i = 0; i < declaredTypes.size(); i++) {
            if (!TypeUtils.isOfType(declaredTypes.get(i), invokedTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static int parameterCount(J.MethodDeclaration method) {
        List<Statement> parameters = method.getParameters();
        return parameters.size() == 1 && parameters.get(0) instanceof J.Empty ? 0 : parameters.size();
    }

    private @Nullable String parameterOfType(J.MethodDeclaration method) {
        for (Statement parameter : method.getParameters()) {
            if (parameter instanceof J.VariableDeclarations declarations
                    && declarations.getVariables().size() == 1
                    && isConfiguredType(declarations.getTypeExpression())) {
                return declarations.getVariables().get(0).getSimpleName();
            }
        }
        return null;
    }

    private static String availableName(String baseName, Set<String> taken) {
        String candidate = baseName;
        int suffix = 1;
        while (taken.contains(candidate)) {
            candidate = baseName + suffix++;
        }
        return candidate;
    }

    /** Names of the parameters and local variables a method declares, lambda parameters included. */
    private static Set<String> declaredNames(J.MethodDeclaration method) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable,
                                                                      Integer p) {
                names.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }
        }.visit(method, 0);
        return names;
    }

    private static Set<String> annotationNames(Tree tree, @Nullable UUID skippedClass) {
        Set<String> names = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration, Integer p) {
                return classDeclaration.getId().equals(skippedClass)
                        ? classDeclaration
                        : super.visitClassDeclaration(classDeclaration, p);
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, Integer p) {
                names.add(annotation.getSimpleName());
                return super.visitAnnotation(annotation, p);
            }
        }.visit(tree, 0);
        return names;
    }

    private static J.ClassDeclaration removeField(J.ClassDeclaration classDeclaration, UUID fieldId) {
        List<Statement> retained = new ArrayList<>();
        Iterator<Statement> statements = classDeclaration.getBody().getStatements().iterator();
        while (statements.hasNext()) {
            Statement statement = statements.next();
            if (!statement.getId().equals(fieldId)) {
                retained.add(statement);
                continue;
            }
            if (retained.isEmpty() && statements.hasNext()) {
                // The first member was removed: its successor takes its line, without a leading blank line.
                Statement next = statements.next();
                retained.add(next.withPrefix(next.getPrefix().withWhitespace(statement.getPrefix().getWhitespace())));
            }
        }
        return classDeclaration.withBody(classDeclaration.getBody().withStatements(retained));
    }

    private boolean isConfiguredType(@Nullable Tree typeExpression) {
        if (!(typeExpression instanceof TypeTree typeTree)) {
            return false;
        }
        String resolved = resolvedName(typeTree.getType());
        if (resolved != null) {
            return resolved.equals(fullyQualifiedTypeName);
        }
        String source = typeExpression.toString().trim();
        return source.equals(fullyQualifiedTypeName) || source.equals(simpleTypeName());
    }

    private String simpleTypeName() {
        return fullyQualifiedTypeName.substring(fullyQualifiedTypeName.lastIndexOf('.') + 1);
    }

    private static boolean isSaga(J.ClassDeclaration classDeclaration) {
        if (classDeclaration.getLeadingAnnotations().stream()
                            .anyMatch(annotation -> isAnnotation(annotation, SAGA_STEREOTYPE_FQN))) {
            return true;
        }
        return classDeclaration.getBody().getStatements().stream()
                               .filter(J.MethodDeclaration.class::isInstance)
                               .map(J.MethodDeclaration.class::cast)
                               .flatMap(method -> method.getLeadingAnnotations().stream())
                               .anyMatch(annotation -> isAnnotation(annotation, SAGA_EVENT_HANDLER_FQN));
    }

    private static boolean isHandler(J.MethodDeclaration method) {
        return method.getLeadingAnnotations().stream().anyMatch(
                annotation -> isAnnotation(annotation, SAGA_EVENT_HANDLER_FQN)
                        || isAnnotation(annotation, DEADLINE_HANDLER_FQN)
        );
    }

    private static boolean isAnnotation(J.Annotation annotation, String fullyQualifiedName) {
        String type = resolvedName(annotation.getType());
        return type != null
                ? fullyQualifiedName.equals(type)
                : fullyQualifiedName.endsWith("." + annotation.getSimpleName());
    }

    /**
     * Returns the fully qualified name of a resolved type, or {@code null} when the type could not be resolved. The
     * callers fall back to the simple name only in that case, so a user type that happens to share a simple name with
     * the configured type is not mistaken for it.
     */
    private static @Nullable String resolvedName(@Nullable JavaType javaType) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(javaType);
        return type == null || type instanceof JavaType.Unknown ? null : type.getFullyQualifiedName();
    }

    /**
     * Collects, within one member of the Saga class, the references to the field and the calls of own-class methods,
     * with the method they appear in and whether they sit in a nested, local or anonymous class.
     */
    private static final class UsageCollector extends JavaIsoVisitor<Integer> {

        private final J.ClassDeclaration saga;
        private final String fieldName;
        private final J.@Nullable MethodDeclaration method;
        private final Set<String> shadowingNames;
        private final Usages usages;

        private UsageCollector(J.ClassDeclaration saga, String fieldName, J.@Nullable MethodDeclaration method,
                               Usages usages) {
            this.saga = saga;
            this.fieldName = fieldName;
            this.method = method;
            this.shadowingNames = method == null ? Set.of() : declaredNames(method);
            this.usages = usages;
        }

        @Override
        public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
            if (identifier.getSimpleName().equals(fieldName) && isVariableReference(identifier)
                    && refersToField(identifier)) {
                record(identifier);
            }
            return identifier;
        }

        @Override
        public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Integer p) {
            if (!fieldAccess.getSimpleName().equals(fieldName)) {
                return super.visitFieldAccess(fieldAccess, p);
            }
            if (fieldAccess.getTarget() instanceof J.Identifier target && target.getSimpleName().equals("this")) {
                record(fieldAccess);
                return fieldAccess;
            }
            if (isOwnedBySaga(fieldAccess.getName().getFieldType())) {
                // The field of another instance, which a handler parameter cannot replace.
                usages.unmigratable = true;
            }
            return super.visitFieldAccess(fieldAccess, p);
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
            Expression select = invocation.getSelect();
            if (select == null || (select instanceof J.Identifier identifier
                    && identifier.getSimpleName().equals("this"))) {
                usages.calls.add(new CallSite(site(invocation), invocation.getSimpleName(),
                                              argumentCount(invocation), invocation.getMethodType()));
            }
            return super.visitMethodInvocation(invocation, p);
        }

        @Override
        public J.MemberReference visitMemberReference(J.MemberReference memberReference, Integer p) {
            usages.methodReferences.add(memberReference.getReference().getSimpleName());
            return super.visitMemberReference(memberReference, p);
        }

        @Override
        public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
            if (isFieldExpression(assignment.getVariable())) {
                usages.unmigratable = true;
            }
            return super.visitAssignment(assignment, p);
        }

        @Override
        public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation assignment, Integer p) {
            if (isFieldExpression(assignment.getVariable())) {
                usages.unmigratable = true;
            }
            return super.visitAssignmentOperation(assignment, p);
        }

        private boolean isFieldExpression(Expression expression) {
            if (expression instanceof J.FieldAccess access) {
                return access.getSimpleName().equals(fieldName)
                        && access.getTarget() instanceof J.Identifier target
                        && target.getSimpleName().equals("this");
            }
            return expression instanceof J.Identifier identifier
                    && identifier.getSimpleName().equals(fieldName)
                    && refersToField(identifier);
        }

        /** Excludes declarations, member names of a field access or call, and method reference names. */
        private boolean isVariableReference(J.Identifier identifier) {
            Object parent = getCursor().getParentTreeCursor().getValue();
            if (parent instanceof J.VariableDeclarations.NamedVariable variable) {
                return variable.getName() != identifier;
            }
            if (parent instanceof J.FieldAccess access) {
                return access.getName() != identifier;
            }
            if (parent instanceof J.MethodInvocation invocation) {
                return invocation.getName() != identifier;
            }
            if (parent instanceof J.MemberReference reference) {
                return reference.getReference() != identifier;
            }
            return !(parent instanceof J.Annotation);
        }

        /**
         * Uses the attributed owner when available. Otherwise a parameter or local of the same name anywhere in the
         * method is taken to shadow the field there.
         */
        private boolean refersToField(J.Identifier identifier) {
            JavaType.Variable variable = identifier.getFieldType();
            if (variable != null && variable.getOwner() != null) {
                return isOwnedBySaga(variable);
            }
            return !shadowingNames.contains(fieldName);
        }

        private boolean isOwnedBySaga(JavaType.@Nullable Variable variable) {
            if (variable == null || !(variable.getOwner() instanceof JavaType.FullyQualified owner)) {
                return false;
            }
            JavaType.FullyQualified sagaType = saga.getType();
            return sagaType == null || owner instanceof JavaType.Unknown
                    || owner.getFullyQualifiedName().equals(sagaType.getFullyQualifiedName());
        }

        private void record(J reference) {
            usages.references.add(site(reference));
        }

        private Site site(J element) {
            return new Site(element.getId(), method == null ? null : method.getId(), isNested());
        }

        /** Whether the current element sits inside a nested, local or anonymous class of the visited member. */
        private boolean isNested() {
            Object child = null;
            for (Cursor cursor = getCursor(); cursor != null; cursor = cursor.getParent()) {
                Object value = cursor.getValue();
                if (value instanceof J.ClassDeclaration) {
                    return true;
                }
                if (value instanceof J.NewClass newClass && newClass.getBody() != null
                        && newClass.getBody() == child) {
                    return true;
                }
                if (value instanceof Tree) {
                    child = value;
                }
            }
            return false;
        }

        private static int argumentCount(J.MethodInvocation invocation) {
            List<Expression> arguments = invocation.getArguments();
            return arguments.size() == 1 && arguments.get(0) instanceof J.Empty ? 0 : arguments.size();
        }
    }

    /** What {@link UsageCollector} found in the members of one Saga class. */
    private static final class Usages {

        private final List<Site> references = new ArrayList<>();
        private final List<CallSite> calls = new ArrayList<>();
        private final Set<String> methodReferences = new HashSet<>();
        private boolean unmigratable;
    }

    /**
     * An element of interest, with the top-level method it appears in ({@code null} outside methods) and whether it
     * sits in a nested, local or anonymous class.
     */
    private record Site(UUID id, @Nullable UUID method, boolean nested) {

    }

    private record CallSite(Site site, String name, int arity, JavaType.@Nullable Method type) {

    }

    /** How one method receives the collaborator: under which name, and whether the parameter must be added. */
    private record MethodPlan(String parameterName, boolean addsParameter) {

    }

    /**
     * The migration of one field: the methods receiving the parameter, the field references to rename to it, and the
     * calls that must pass it on.
     */
    private record Plan(UUID fieldId, Map<UUID, MethodPlan> methods, Set<UUID> references, Set<UUID> calls) {

    }
}
