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

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.marker.Markers;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migrates command dispatch from an Axon Framework 4 {@code CommandGateway} field in legacy Saga event handlers to
 * an Axon Framework 5 {@code CommandDispatcher} method parameter.
 * <p>
 * The recipe preserves the dispatch contract of each call. {@code send(...)} remains fire-and-forget.
 * {@code sendAndWait(...)} waits through {@code FutureUtils.joinAndUnwrap(...)} so the Saga handler remains
 * synchronous and command failures retain their original exception type. When no references remain, the obsolete
 * gateway field and its constructor injection are removed.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class MigrateCommandGatewayInSagaEventHandler extends Recipe {

    private static final String SAGA_EVENT_HANDLER_FQN = "org.axonframework.modelling.saga.SagaEventHandler";
    private static final String COMMAND_GATEWAY_AF4_FQN = "org.axonframework.commandhandling.gateway.CommandGateway";
    private static final String COMMAND_GATEWAY_AF5_FQN =
            "org.axonframework.messaging.commandhandling.gateway.CommandGateway";
    private static final String COMMAND_DISPATCHER_FQN =
            "org.axonframework.messaging.commandhandling.gateway.CommandDispatcher";
    private static final String FUTURE_UTILS_FQN = "org.axonframework.common.FutureUtils";

    @Override
    public String getDisplayName() {
        return "Replace CommandGateway with CommandDispatcher in legacy Saga event handlers";
    }

    @Override
    public String getDescription() {
        return "Replaces a class-level `CommandGateway` used by `@SagaEventHandler` methods with an injected "
                + "`CommandDispatcher` parameter. `send` remains fire-and-forget, while `sendAndWait` remains "
                + "synchronous without changing the handler return type.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDeclaration,
                                                             ExecutionContext ctx) {
                String gatewayFieldName = gatewayFieldName(classDeclaration);
                if (gatewayFieldName == null || !hasSagaHandlerCallingGateway(classDeclaration, gatewayFieldName)) {
                    return super.visitClassDeclaration(classDeclaration, ctx);
                }
                getCursor().putMessage("axon.sagaGatewayFieldName", gatewayFieldName);
                J.ClassDeclaration migrated = super.visitClassDeclaration(classDeclaration, ctx);
                if (!isFieldStillReferenced(migrated, gatewayFieldName)) {
                    migrated = removeGatewayField(migrated, gatewayFieldName);
                    migrated = removeGatewayFromConstructors(migrated, gatewayFieldName);
                    maybeRemoveImport(COMMAND_GATEWAY_AF4_FQN);
                    maybeRemoveImport(COMMAND_GATEWAY_AF5_FQN);
                    maybeRemoveImport("org.springframework.beans.factory.annotation.Autowired");
                }
                return migrated;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                if (!isSagaEventHandler(method) || method.getBody() == null) {
                    return super.visitMethodDeclaration(method, ctx);
                }
                String gatewayFieldName = getCursor().getNearestMessage("axon.sagaGatewayFieldName");
                if (gatewayFieldName == null || !referencesGatewayCall(method, gatewayFieldName)) {
                    return super.visitMethodDeclaration(method, ctx);
                }

                String existingDispatcher = dispatcherParameterName(method);
                String dispatcherName = existingDispatcher == null
                        ? availableParameterName(method, "commandDispatcher")
                        : existingDispatcher;
                J.MethodDeclaration migrated = existingDispatcher == null
                        ? addDispatcherParameter(method, dispatcherName)
                        : method;
                boolean[] waits = {false};

                migrated = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        J.MethodInvocation visited = super.visitMethodInvocation(invocation, executionContext);
                        if (!isGatewayCall(visited, gatewayFieldName)) {
                            return visited;
                        }
                        String originalMethod = visited.getSimpleName();
                        if (!originalMethod.equals("send") && !originalMethod.equals("sendAndWait")) {
                            return visited;
                        }

                        Space prefix = visited.getSelect() == null
                                ? Space.EMPTY
                                : visited.getSelect().getPrefix();
                        J.Identifier dispatcher = new J.Identifier(
                                Tree.randomId(),
                                prefix,
                                Markers.EMPTY,
                                Collections.emptyList(),
                                dispatcherName,
                                null,
                                null
                        );
                        J.MethodInvocation dispatch = visited.withSelect(dispatcher)
                                                             .withName(visited.getName().withSimpleName("send"));
                        if (originalMethod.equals("send")) {
                            return dispatch;
                        }

                        waits[0] = true;
                        boolean statementExpression = getCursor().getParentTreeCursor().getValue() instanceof J.Block;
                        String template = statementExpression
                                ? "FutureUtils.joinAndUnwrap(#{any()}.getResultMessage())"
                                : "FutureUtils.joinAndUnwrap(#{any()}.getResultMessage()).payload()";
                        return JavaTemplate.builder(template)
                                .imports(FUTURE_UTILS_FQN)
                                .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                                .build()
                                .apply(getCursor(), visited.getCoordinates().replace(), dispatch);
                    }
                }.visitNonNull(migrated, ctx, getCursor().getParentOrThrow());

                maybeAddImport(COMMAND_DISPATCHER_FQN, false);
                if (waits[0]) {
                    maybeAddImport(FUTURE_UTILS_FQN, false);
                }
                return migrated;
            }

            private String gatewayFieldName(J.ClassDeclaration classDeclaration) {
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations) {
                        J.VariableDeclarations declarations = (J.VariableDeclarations) statement;
                        if (isGatewayDeclaration(declarations) && !declarations.getVariables().isEmpty()) {
                            return declarations.getVariables().get(0).getSimpleName();
                        }
                    }
                }
                return null;
            }

            private boolean isGatewayDeclaration(J.VariableDeclarations declarations) {
                if (declarations.getTypeExpression() == null) {
                    return false;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(declarations.getTypeExpression().getType());
                if (type != null && (type.getFullyQualifiedName().equals(COMMAND_GATEWAY_AF4_FQN)
                        || type.getFullyQualifiedName().equals(COMMAND_GATEWAY_AF5_FQN))) {
                    return true;
                }
                String sourceType = declarations.getTypeExpression().toString();
                return sourceType.equals("CommandGateway")
                        || sourceType.equals(COMMAND_GATEWAY_AF4_FQN)
                        || sourceType.equals(COMMAND_GATEWAY_AF5_FQN);
            }

            private boolean hasSagaHandlerCallingGateway(J.ClassDeclaration classDeclaration, String fieldName) {
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if (isSagaEventHandler(method) && referencesGatewayCall(method, fieldName)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean isSagaEventHandler(J.MethodDeclaration method) {
                for (J.Annotation annotation : method.getLeadingAnnotations()) {
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
                    if (type != null && type.getFullyQualifiedName().equals(SAGA_EVENT_HANDLER_FQN)) {
                        return true;
                    }
                    if (annotation.getSimpleName().equals("SagaEventHandler")) {
                        return true;
                    }
                }
                return false;
            }

            private boolean referencesGatewayCall(J.MethodDeclaration method, String fieldName) {
                if (method.getBody() == null) {
                    return false;
                }
                boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation,
                                                                    ExecutionContext executionContext) {
                        if (isGatewayCall(invocation, fieldName)
                                && (invocation.getSimpleName().equals("send")
                                || invocation.getSimpleName().equals("sendAndWait"))) {
                            found[0] = true;
                        }
                        return found[0] ? invocation : super.visitMethodInvocation(invocation, executionContext);
                    }
                }.visit(method.getBody(), new InMemoryExecutionContext());
                return found[0];
            }

            private boolean isGatewayCall(J.MethodInvocation invocation, String fieldName) {
                Expression select = invocation.getSelect();
                if (select instanceof J.Identifier) {
                    return ((J.Identifier) select).getSimpleName().equals(fieldName);
                }
                if (select instanceof J.FieldAccess) {
                    J.FieldAccess access = (J.FieldAccess) select;
                    return access.getName().getSimpleName().equals(fieldName)
                            && access.getTarget() instanceof J.Identifier
                            && ((J.Identifier) access.getTarget()).getSimpleName().equals("this");
                }
                return false;
            }

            private String dispatcherParameterName(J.MethodDeclaration method) {
                for (Statement parameter : method.getParameters()) {
                    if (!(parameter instanceof J.VariableDeclarations)) {
                        continue;
                    }
                    J.VariableDeclarations declarations = (J.VariableDeclarations) parameter;
                    if (declarations.getTypeExpression() == null || declarations.getVariables().isEmpty()) {
                        continue;
                    }
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(
                            declarations.getTypeExpression().getType()
                    );
                    if ((type != null && type.getFullyQualifiedName().equals(COMMAND_DISPATCHER_FQN))
                            || declarations.getTypeExpression().toString().equals("CommandDispatcher")) {
                        return declarations.getVariables().get(0).getSimpleName();
                    }
                }
                return null;
            }

            private String availableParameterName(J.MethodDeclaration method, String baseName) {
                String candidate = baseName;
                int suffix = 1;
                while (hasParameterNamed(method, candidate)) {
                    candidate = baseName + suffix++;
                }
                return candidate;
            }

            private boolean hasParameterNamed(J.MethodDeclaration method, String name) {
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations) {
                        J.VariableDeclarations declarations = (J.VariableDeclarations) parameter;
                        if (!declarations.getVariables().isEmpty()
                                && declarations.getVariables().get(0).getSimpleName().equals(name)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private J.MethodDeclaration addDispatcherParameter(J.MethodDeclaration method, String parameterName) {
                List<Object> templateArguments = new ArrayList<>();
                StringBuilder template = new StringBuilder();
                List<Statement> existing = method.getParameters();
                boolean hasExisting = !(existing.size() == 1 && existing.get(0) instanceof J.Empty);
                if (hasExisting) {
                    for (int i = 0; i < existing.size(); i++) {
                        if (i > 0) {
                            template.append(", ");
                        }
                        template.append("#{}");
                        templateArguments.add(existing.get(i).print(getCursor()));
                    }
                    template.append(", ");
                }
                template.append("CommandDispatcher ").append(parameterName);
                return JavaTemplate.builder(template.toString())
                        .imports(COMMAND_DISPATCHER_FQN)
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), method.getCoordinates().replaceParameters(), templateArguments.toArray());
            }

            private boolean isFieldStillReferenced(J.ClassDeclaration classDeclaration, String fieldName) {
                boolean[] referenced = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations declarations,
                                                                            ExecutionContext executionContext) {
                        if (isGatewayDeclaration(declarations)
                                && !declarations.getVariables().isEmpty()
                                && declarations.getVariables().get(0).getSimpleName().equals(fieldName)) {
                            return declarations;
                        }
                        return super.visitVariableDeclarations(declarations, executionContext);
                    }

                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                                      ExecutionContext executionContext) {
                        if (isConstructorWithGatewayParameter(method, fieldName)) {
                            return method;
                        }
                        return super.visitMethodDeclaration(method, executionContext);
                    }

                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier,
                                                        ExecutionContext executionContext) {
                        if (identifier.getSimpleName().equals(fieldName)) {
                            referenced[0] = true;
                        }
                        return identifier;
                    }
                }.visit(classDeclaration, new InMemoryExecutionContext());
                return referenced[0];
            }

            private boolean isConstructorWithGatewayParameter(J.MethodDeclaration method, String fieldName) {
                if (!method.isConstructor()) {
                    return false;
                }
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.VariableDeclarations) {
                        J.VariableDeclarations declarations = (J.VariableDeclarations) parameter;
                        if (isGatewayDeclaration(declarations)
                                && !declarations.getVariables().isEmpty()
                                && declarations.getVariables().get(0).getSimpleName().equals(fieldName)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private J.ClassDeclaration removeGatewayField(J.ClassDeclaration classDeclaration, String fieldName) {
                List<Statement> retained = new ArrayList<>();
                boolean removed = false;
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (statement instanceof J.VariableDeclarations) {
                        J.VariableDeclarations declarations = (J.VariableDeclarations) statement;
                        if (isGatewayDeclaration(declarations)
                                && !declarations.getVariables().isEmpty()
                                && declarations.getVariables().get(0).getSimpleName().equals(fieldName)) {
                            removed = true;
                            continue;
                        }
                    }
                    if (removed && retained.isEmpty() && statement.getPrefix().getComments().isEmpty()
                            && statement.getPrefix().getWhitespace().chars().filter(character -> character == '\n')
                                        .count() > 1) {
                        statement = (Statement) statement.withPrefix(Space.format("\n    "));
                    }
                    retained.add(statement);
                }
                return classDeclaration.withBody(classDeclaration.getBody().withStatements(retained));
            }

            private J.ClassDeclaration removeGatewayFromConstructors(J.ClassDeclaration classDeclaration,
                                                                      String fieldName) {
                List<Statement> retained = new ArrayList<>();
                for (Statement statement : classDeclaration.getBody().getStatements()) {
                    if (!(statement instanceof J.MethodDeclaration)) {
                        retained.add(statement);
                        continue;
                    }
                    J.MethodDeclaration method = (J.MethodDeclaration) statement;
                    if (!method.isConstructor() || !isConstructorWithGatewayParameter(method, fieldName)) {
                        retained.add(statement);
                        continue;
                    }

                    List<Statement> parameters = new ArrayList<>();
                    for (Statement parameter : method.getParameters()) {
                        if (parameter instanceof J.VariableDeclarations) {
                            J.VariableDeclarations declarations = (J.VariableDeclarations) parameter;
                            if (isGatewayDeclaration(declarations)
                                    && !declarations.getVariables().isEmpty()
                                    && declarations.getVariables().get(0).getSimpleName().equals(fieldName)) {
                                continue;
                            }
                        }
                        parameters.add(parameter);
                    }
                    if (parameters.isEmpty()) {
                        continue;
                    }
                    method = method.withParameters(parameters);
                    if (method.getBody() != null) {
                        List<Statement> body = new ArrayList<>();
                        for (Statement bodyStatement : method.getBody().getStatements()) {
                            if (!isFieldAssignment(bodyStatement, fieldName)) {
                                body.add(bodyStatement);
                            }
                        }
                        method = method.withBody(method.getBody().withStatements(body));
                    }
                    retained.add(method);
                }
                return classDeclaration.withBody(classDeclaration.getBody().withStatements(retained));
            }

            private boolean isFieldAssignment(Statement statement, String fieldName) {
                if (!(statement instanceof J.Assignment)) {
                    return false;
                }
                Expression variable = ((J.Assignment) statement).getVariable();
                if (variable instanceof J.FieldAccess) {
                    return ((J.FieldAccess) variable).getName().getSimpleName().equals(fieldName);
                }
                return variable instanceof J.Identifier
                        && ((J.Identifier) variable).getSimpleName().equals(fieldName);
            }
        };
    }
}
