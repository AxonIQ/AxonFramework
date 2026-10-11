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

import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Appends a parameter of a given type to a Java or Kotlin method declaration, leaving the existing parameters as they
 * are.
 * <p>
 * Only the new parameter is parsed, from a stub method importing its type. The existing parameter trees are kept, so
 * they retain their type attribution, their annotations and their layout. Re-printing and re-parsing them instead
 * would detach them from the source file's classpath: later type-based recipes, such as a type rename or a type
 * argument removal, would then skip them, and the imports they need would be removed as unused.
 * <p>
 * The new parameter starts on its own line when the existing parameters are laid out one per line, and follows the
 * last parameter on the same line otherwise.
 * <p>
 * Internal to the migration recipes that move a collaborator into a handler method parameter, which share it so they
 * add parameters the same way.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class MethodParameters {

    private MethodParameters() {
    }

    /**
     * Appends a parameter of the given type and name to the given method.
     *
     * @param method         the method declaration to extend
     * @param kotlin         whether the method is declared in a Kotlin source
     * @param typeFqn        the fully qualified name of the parameter type
     * @param parameterName  the name of the new parameter
     * @return the method with the new parameter as its last parameter
     * @throws IllegalStateException if the stub declaring the parameter cannot be parsed
     */
    static J.MethodDeclaration append(J.MethodDeclaration method, boolean kotlin, String typeFqn,
                                      String parameterName) {
        J.VariableDeclarations parameter = kotlin
                ? kotlinParameter(typeFqn, parameterName)
                : javaParameter(typeFqn, parameterName);
        List<JRightPadded<Statement>> existing = method.getPadding().getParameters().getPadding().getElements();
        List<JRightPadded<Statement>> parameters = new ArrayList<>();
        if (existing.size() == 1 && existing.get(0).getElement() instanceof J.Empty) {
            parameters.add(JRightPadded.build((Statement) parameter.withPrefix(Space.EMPTY)));
        } else {
            JRightPadded<Statement> last = existing.get(existing.size() - 1);
            parameters.addAll(existing.subList(0, existing.size() - 1));
            parameters.add(last.withAfter(Space.EMPTY).withMarkers(Markers.EMPTY));
            Space prefix = onePerLine(existing) ? last.getElement().getPrefix() : Space.SINGLE_SPACE;
            // The new last parameter takes over what followed the old one: the space before the closing
            // parenthesis and, in Kotlin, a trailing comma.
            parameters.add(new JRightPadded<>((Statement) parameter.withPrefix(prefix), last.getAfter(),
                                              last.getMarkers()));
        }
        JContainer<Statement> container = method.getPadding().getParameters().getPadding().withElements(parameters);
        return withParameterType(method.getPadding().withParameters(container), parameter);
    }

    /**
     * Whether every parameter but the first starts on a new line, or the only parameter does.
     */
    private static boolean onePerLine(List<JRightPadded<Statement>> parameters) {
        return parameters.stream()
                         .skip(parameters.size() > 1 ? 1 : 0)
                         .allMatch(parameter -> parameter.getElement().getPrefix().getWhitespace().contains("\n"));
    }

    /**
     * Adds the new parameter to the attributed method type, so later visitors see the extended signature. A method
     * without type attribution, or a parameter whose type could not be resolved, is left as is.
     */
    private static J.MethodDeclaration withParameterType(J.MethodDeclaration method, J.VariableDeclarations parameter) {
        JavaType.Method methodType = method.getMethodType();
        JavaType parameterType = parameter.getType();
        if (methodType == null || parameterType == null) {
            return method;
        }
        List<String> names = new ArrayList<>(methodType.getParameterNames());
        names.add(parameter.getVariables().get(0).getSimpleName());
        List<JavaType> types = new ArrayList<>(methodType.getParameterTypes());
        types.add(parameterType);
        JavaType.Method extended = methodType.withParameterNames(names).withParameterTypes(types);
        return method.withMethodType(extended).withName(method.getName().withType(extended));
    }

    private static J.VariableDeclarations javaParameter(String typeFqn, String parameterName) {
        String snippet = "import " + typeFqn + ";\n\nclass _T {\n    void _f(" + simpleName(typeFqn) + " "
                + parameterName + ") {\n    }\n}\n";
        Stream<SourceFile> parsed = JavaParser.fromJavaVersion()
                                              .classpath(JavaParser.runtimeClasspath())
                                              .build()
                                              .parse(snippet);
        return parsed.filter(J.CompilationUnit.class::isInstance)
                     .map(J.CompilationUnit.class::cast)
                     .flatMap(unit -> unit.getClasses().stream())
                     .flatMap(type -> type.getBody().getStatements().stream())
                     .filter(J.MethodDeclaration.class::isInstance)
                     .map(stub -> ((J.MethodDeclaration) stub).getParameters().get(0))
                     .filter(J.VariableDeclarations.class::isInstance)
                     .map(J.VariableDeclarations.class::cast)
                     .findFirst()
                     .orElseThrow(() -> new IllegalStateException("Unable to declare a parameter: " + snippet));
    }

    private static J.VariableDeclarations kotlinParameter(String typeFqn, String parameterName) {
        String snippet = "package _temp\n\nimport " + typeFqn + "\n\nfun _f(" + parameterName + ": "
                + simpleName(typeFqn) + ") {}\n";
        Stream<SourceFile> parsed = KotlinParser.builder().build().parse(snippet);
        return parsed.filter(K.CompilationUnit.class::isInstance)
                     .map(K.CompilationUnit.class::cast)
                     .flatMap(unit -> unit.getStatements().stream())
                     .filter(J.MethodDeclaration.class::isInstance)
                     .map(stub -> ((J.MethodDeclaration) stub).getParameters().get(0))
                     .filter(J.VariableDeclarations.class::isInstance)
                     .map(J.VariableDeclarations.class::cast)
                     .findFirst()
                     .orElseThrow(() -> new IllegalStateException("Unable to declare a parameter: " + snippet));
    }

    private static String simpleName(String typeFqn) {
        return typeFqn.substring(typeFqn.lastIndexOf('.') + 1);
    }
}
