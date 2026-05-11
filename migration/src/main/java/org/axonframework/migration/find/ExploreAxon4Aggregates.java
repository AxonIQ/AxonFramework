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

package org.axonframework.migration.find;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Search-only recipe that inventories every Axon Framework 4 aggregate in a Java or Kotlin
 * codebase and emits one YAML document per aggregate to
 * {@code <project>/.axon4-explore/components/aggregate@<fqn>.yaml}, plus a flat CSV row
 * per aggregate to {@link AggregateFeaturesTable}.
 * <p>
 * Designed to run independently of the {@code Axon4ToAxon5} umbrella migration so users
 * can take an LLM-readable snapshot of the AF4 surface area before applying any rewriting
 * recipes. Discovery proceeds in three sub-passes inside the scanner phase:
 * <ul>
 *   <li><b>Pass A</b> — class declarations carrying {@code @Aggregate}
 *       ({@code org.axonframework.spring.stereotype.Aggregate}) or {@code @AggregateRoot}.
 *   <li><b>Pass B</b> — method invocations of {@code configureAggregate(X.class)} or
 *       {@code configureAggregate<X>(…)} (Kotlin generic form).
 *   <li><b>Pass C</b> — chained {@code withSubtypes(Child.class, …)} invocations, used as
 *       polymorphism Signal 2.
 * </ul>
 * The accumulator merges these signals during {@link #generate}: a class becomes an
 * aggregate if any of the three passes flags it. Polymorphism Signal 1 (a child class
 * {@code extends} a parent that is also annotated with {@code @Aggregate}) is computed
 * during merge by walking the {@code extends} clause of every annotated class.
 * <p>
 * The Kotlin LST extends the Java LST, so a single {@link JavaIsoVisitor} handles both
 * languages. Type-resolved matching via {@link TypeUtils#isOfClassType} is preferred; the
 * recipe falls back to simple-name matching whenever the LST is partially typed (e.g. tests
 * without an Axon 4 classpath, or projects with unresolved imports).
 *
 * @author Axon Framework Team
 * @since 5.2.0
 */
public class ExploreAxon4Aggregates extends ScanningRecipe<AggregateInventory> {

    private static final String AGGREGATE_FQN = "org.axonframework.spring.stereotype.Aggregate";
    private static final String AGGREGATE_SIMPLE = "Aggregate";
    private static final String AGGREGATE_ROOT_SIMPLE = "AggregateRoot";
    private static final String EVENT_SOURCING_HANDLER_FQN = "org.axonframework.eventsourcing.EventSourcingHandler";
    private static final String EVENT_HANDLER_FQN = "org.axonframework.eventhandling.EventHandler";
    private static final String COMMAND_HANDLER_FQN_AF4 = "org.axonframework.commandhandling.CommandHandler";
    private static final String COMMAND_HANDLER_FQN_AF5 = "org.axonframework.messaging.commandhandling.annotation.CommandHandler";
    private static final String AGGREGATE_MEMBER_FQN = "org.axonframework.modelling.command.AggregateMember";
    private static final String DEADLINE_HANDLER_FQN = "org.axonframework.deadline.annotation.DeadlineHandler";
    private static final String REVISION_FQN = "org.axonframework.modelling.command.Revision";
    private static final String AGGREGATE_VERSION_SIMPLE = "AggregateVersion";
    private static final String CREATION_POLICY_SIMPLE = "CreationPolicy";
    private static final String JPA_ENTITY_JAKARTA = "jakarta.persistence.Entity";
    private static final String JPA_ENTITY_JAVAX = "javax.persistence.Entity";

    private static final String GENERATED_FLAG_KEY = "axon-migration.explore-aggregates.generated";

    private final transient AggregateFeaturesTable table = new AggregateFeaturesTable(this);

    @Override
    public String getDisplayName() {
        return "Inventory Axon 4 aggregates";
    }

    @Override
    public String getDescription() {
        return "Locates every AF4 aggregate (Java and Kotlin) via `@Aggregate` annotations, "
                + "`configureAggregate(...)` calls, and `withSubtypes(...)` chains. Emits one "
                + "YAML document per aggregate to `.axon4-explore/components/aggregate@<fqn>.yaml` "
                + "and a flat CSV row per aggregate to a data table. Read-only — does not modify "
                + "any source files.";
    }

    @Override
    public AggregateInventory getInitialValue(ExecutionContext ctx) {
        return new AggregateInventory();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(AggregateInventory acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, ExecutionContext ctx) {
                String fqn = resolveFqn(cd, getCursor());
                if (fqn == null || fqn.isEmpty()) {
                    return super.visitClassDeclaration(cd, ctx);
                }
                AggregateRecord rec = acc.classIndex.computeIfAbsent(fqn, key -> initRecord(key, cd, getCursor()));
                captureExtendsTarget(cd, rec);
                inspectLeadingAnnotations(cd, acc, rec);
                if (cd.getBody() != null) {
                    inspectBody(cd, rec);
                }
                return super.visitClassDeclaration(cd, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
                String name = mi.getSimpleName();
                if ("configureAggregate".equals(name)) {
                    String typeFqn = extractTypeArgOrFirstClassLiteral(mi);
                    if (typeFqn != null) {
                        acc.configured.add(typeFqn);
                    }
                } else if ("withSubtypes".equals(name)) {
                    capturePolymorphismSignal2(mi, acc);
                } else if ("createNew".equals(name)) {
                    J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (enclosing != null) {
                        String enclosingFqn = resolveFqn(enclosing, getCursor());
                        AggregateRecord rec = acc.classIndex.get(enclosingFqn);
                        if (rec != null) {
                            rec.createNew = true;
                        }
                    }
                }
                return super.visitMethodInvocation(mi, ctx);
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(AggregateInventory acc, ExecutionContext ctx) {
        // OpenRewrite calls generate() once per cycle. Without this guard the recipe would
        // re-emit identical YAML files in cycle 2+, which the test harness counts as
        // additional change cycles. ExecutionContext is shared across cycles so a flag here
        // survives accumulator-resetting (ScanningRecipe accumulators may or may not persist
        // between cycles depending on the runtime — ExecutionContext is the durable surface).
        if (Boolean.TRUE.equals(ctx.getMessage(GENERATED_FLAG_KEY))) {
            return List.of();
        }
        ctx.putMessage(GENERATED_FLAG_KEY, Boolean.TRUE);
        merge(acc);
        List<SourceFile> generated = new ArrayList<>();
        for (AggregateRecord r : acc.classIndex.values()) {
            if (!r.isAggregate) {
                continue;
            }
            table.insertRow(ctx, toRow(r));
            Path path = Paths.get(".axon4-explore/components/aggregate@" + r.fqn + ".yaml");
            generated.add(PlainText.builder()
                                  .sourcePath(path)
                                  .text(YamlEmitter.toYaml(r))
                                  .build());
        }
        return generated;
    }

    private static AggregateRecord initRecord(String fqn, J.ClassDeclaration cd, Cursor cursor) {
        AggregateRecord r = new AggregateRecord();
        r.fqn = fqn;
        int dot = fqn.lastIndexOf('.');
        r.packageName = dot > 0 ? fqn.substring(0, dot) : "";
        r.simpleName = cd.getSimpleName();
        SourceFile sf = cursor.firstEnclosing(SourceFile.class);
        r.sourcePath = sf == null ? "<unknown>" : sf.getSourcePath().toString();
        r.language = (r.sourcePath.endsWith(".kt") || r.sourcePath.endsWith(".kts")) ? "kotlin" : "java";
        return r;
    }

    private static void captureExtendsTarget(J.ClassDeclaration cd, AggregateRecord rec) {
        if (rec.parentFqn != null) {
            return;
        }
        if (cd.getExtends() != null) {
            String fqn = fqnOfTypeTree(cd.getExtends());
            if (fqn != null) {
                rec.parentFqn = fqn;
                return;
            }
        }
        // Kotlin parent class often surfaces as the first entry of `getImplements()` — check it.
        if (cd.getImplements() != null) {
            for (TypeTree impl : cd.getImplements()) {
                String fqn = fqnOfTypeTree(impl);
                if (fqn != null) {
                    rec.parentFqn = fqn;
                    return;
                }
            }
        }
    }

    private static void inspectLeadingAnnotations(J.ClassDeclaration cd, AggregateInventory acc, AggregateRecord rec) {
        for (J.Annotation ann : cd.getLeadingAnnotations()) {
            if (isAggregateAnnotation(ann)) {
                acc.annotated.add(rec.fqn);
                rec.imports.add(AGGREGATE_FQN);
                if (ann.getArguments() != null) {
                    for (Expression arg : ann.getArguments()) {
                        String argName = annotationArgumentName(arg);
                        if ("snapshotTriggerDefinition".equals(argName)) {
                            rec.hasSnapshotTrigger = true;
                        } else if ("cache".equals(argName)) {
                            rec.hasCache = true;
                        }
                    }
                }
            }
            if (TypeUtils.isOfClassType(ann.getType(), REVISION_FQN)
                    || "Revision".equals(annotationSimpleName(ann))) {
                rec.hasRevision = true;
                rec.imports.add(REVISION_FQN);
            }
            if (TypeUtils.isOfClassType(ann.getType(), JPA_ENTITY_JAKARTA)
                    || TypeUtils.isOfClassType(ann.getType(), JPA_ENTITY_JAVAX)
                    || "Entity".equals(annotationSimpleName(ann))) {
                rec.jpaEntity = true;
            }
        }
    }

    private static void inspectBody(J.ClassDeclaration cd, AggregateRecord rec) {
        for (Statement s : cd.getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration md) {
                boolean isCommandHandler = false;
                boolean isEventHandler = false;
                for (J.Annotation a : md.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(a.getType(), EVENT_SOURCING_HANDLER_FQN)
                            || "EventSourcingHandler".equals(annotationSimpleName(a))) {
                        rec.persistence = "event-sourcing";
                        rec.imports.add(EVENT_SOURCING_HANDLER_FQN);
                        isEventHandler = true;
                    }
                    if (TypeUtils.isOfClassType(a.getType(), EVENT_HANDLER_FQN)
                            || "EventHandler".equals(annotationSimpleName(a))) {
                        rec.imports.add(EVENT_HANDLER_FQN);
                        isEventHandler = true;
                    }
                    if (TypeUtils.isOfClassType(a.getType(), COMMAND_HANDLER_FQN_AF4)
                            || TypeUtils.isOfClassType(a.getType(), COMMAND_HANDLER_FQN_AF5)
                            || "CommandHandler".equals(annotationSimpleName(a))) {
                        rec.imports.add(COMMAND_HANDLER_FQN_AF4);
                        isCommandHandler = true;
                    }
                    if (TypeUtils.isOfClassType(a.getType(), DEADLINE_HANDLER_FQN)
                            || "DeadlineHandler".equals(annotationSimpleName(a))) {
                        rec.deadline = true;
                        rec.imports.add(DEADLINE_HANDLER_FQN);
                    }
                    if (CREATION_POLICY_SIMPLE.equals(annotationSimpleName(a))) {
                        rec.hasCreationPolicy = true;
                    }
                }
                if (isCommandHandler || isEventHandler) {
                    String firstParamFqn = firstParameterFqn(md);
                    if (firstParamFqn != null) {
                        if (isCommandHandler) {
                            rec.commands.add(firstParamFqn);
                        }
                        if (isEventHandler) {
                            rec.events.add(firstParamFqn);
                        }
                    }
                }
            } else if (s instanceof J.VariableDeclarations vd) {
                for (J.Annotation a : vd.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(a.getType(), AGGREGATE_MEMBER_FQN)
                            || "AggregateMember".equals(annotationSimpleName(a))) {
                        rec.multiEntity = true;
                        rec.imports.add(AGGREGATE_MEMBER_FQN);
                    }
                    if (AGGREGATE_VERSION_SIMPLE.equals(annotationSimpleName(a))) {
                        rec.hasAggregateVersion = true;
                    }
                }
            }
        }
    }

    private static void capturePolymorphismSignal2(J.MethodInvocation withSubtypes, AggregateInventory acc) {
        Expression select = withSubtypes.getSelect();
        String parentFqn = null;
        if (select instanceof J.MethodInvocation parentCall
                && "configureAggregate".equals(parentCall.getSimpleName())) {
            parentFqn = extractTypeArgOrFirstClassLiteral(parentCall);
        }
        if (parentFqn == null) {
            return;
        }
        Set<String> children = new LinkedHashSet<>();
        for (Expression a : withSubtypes.getArguments()) {
            String c = fqnOfClassLiteral(a);
            if (c != null) {
                children.add(c);
            }
        }
        if (!children.isEmpty()) {
            acc.withSubtypesMap.computeIfAbsent(parentFqn, k -> new LinkedHashSet<>()).addAll(children);
        }
    }

    /**
     * Merge phase: promote class records to aggregates based on the union of Pass A / B / C
     * signals, then resolve {@code config_style}, {@code persistence}, and polymorphism roles.
     */
    private static void merge(AggregateInventory acc) {
        for (String fqn : acc.annotated) {
            AggregateRecord r = acc.classIndex.get(fqn);
            if (r != null) {
                r.isAggregate = true;
                r.discovery.add("@Aggregate");
            }
        }
        for (String fqn : acc.configured) {
            AggregateRecord r = acc.classIndex.get(fqn);
            if (r != null) {
                r.isAggregate = true;
                r.discovery.add("configureAggregate");
            }
        }
        for (Map.Entry<String, Set<String>> e : acc.withSubtypesMap.entrySet()) {
            AggregateRecord parent = acc.classIndex.get(e.getKey());
            if (parent != null) {
                parent.isAggregate = true;
                if (parent.discovery.isEmpty()) {
                    parent.discovery.add("withSubtypes");
                }
            }
            for (String childFqn : e.getValue()) {
                AggregateRecord child = acc.classIndex.get(childFqn);
                if (child != null) {
                    child.isAggregate = true;
                    if (child.discovery.isEmpty()) {
                        child.discovery.add("withSubtypes");
                    }
                }
            }
        }

        for (AggregateRecord r : acc.classIndex.values()) {
            if (!r.isAggregate) {
                continue;
            }
            r.configStyle = acc.annotated.contains(r.fqn) ? "spring" : "non-spring";
            if (r.persistence == null) {
                r.persistence = "state-stored";
            }

            // Polymorphism Signal 1: parent class also annotated with @Aggregate.
            if (r.parentFqn != null) {
                AggregateRecord parent = acc.classIndex.get(r.parentFqn);
                if (parent != null && acc.annotated.contains(parent.fqn)) {
                    r.polymorphic = true;
                    r.polymorphicRole = "child";
                    r.polymorphicParent = r.parentFqn;
                    r.notes.add("polymorphism detected via: extends-chain");
                    parent.polymorphic = true;
                    if (!"child".equals(parent.polymorphicRole)) {
                        parent.polymorphicRole = "parent";
                    }
                    if (!parent.notes.contains("polymorphism detected via: extends-chain")) {
                        parent.notes.add("polymorphism detected via: extends-chain");
                    }
                }
            }

            // Polymorphism Signal 2: appears as parent or child in any withSubtypesMap entry.
            for (Map.Entry<String, Set<String>> e : acc.withSubtypesMap.entrySet()) {
                if (r.fqn.equals(e.getKey()) && !r.polymorphic) {
                    r.polymorphic = true;
                    r.polymorphicRole = "parent";
                    r.notes.add("polymorphism detected via: withSubtypes");
                }
                if (e.getValue().contains(r.fqn) && !r.polymorphic) {
                    r.polymorphic = true;
                    r.polymorphicRole = "child";
                    r.polymorphicParent = e.getKey();
                    r.notes.add("polymorphism detected via: withSubtypes");
                }
            }
        }
    }

    private static AggregateFeaturesTable.Row toRow(AggregateRecord r) {
        return new AggregateFeaturesTable.Row(
                r.fqn,
                emptyIfNull(r.packageName),
                r.language,
                r.sourcePath,
                String.join(",", r.discovery),
                emptyIfNull(r.configStyle),
                emptyIfNull(r.persistence),
                r.multiEntity,
                r.deadline,
                r.createNew,
                r.polymorphic,
                emptyIfNull(r.polymorphicRole),
                emptyIfNull(r.polymorphicParent),
                r.hasSnapshotTrigger,
                r.hasCache,
                r.hasRevision,
                r.hasAggregateVersion,
                r.hasCreationPolicy,
                r.jpaEntity,
                String.join(",", r.commands),
                String.join(",", r.events),
                String.join("\n", r.notes)
        );
    }

    /**
     * Resolves the fully-qualified name of a class declaration. Prefers the LST type when
     * available; otherwise reconstructs the FQN from the enclosing compilation unit's
     * package declaration and the class's simple name. Returns the simple name (with no
     * package prefix) as a last resort.
     */
    private static String resolveFqn(J.ClassDeclaration cd, Cursor cursor) {
        if (cd.getType() != null) {
            return cd.getType().getFullyQualifiedName();
        }
        J.CompilationUnit cu = cursor.firstEnclosing(J.CompilationUnit.class);
        if (cu != null && cu.getPackageDeclaration() != null) {
            String pkg = cu.getPackageDeclaration().getExpression().toString();
            return pkg + "." + cd.getSimpleName();
        }
        return cd.getSimpleName();
    }

    private static String fqnOfTypeTree(TypeTree tt) {
        JavaType.FullyQualified t = TypeUtils.asFullyQualified(tt.getType());
        return t == null ? null : t.getFullyQualifiedName();
    }

    /**
     * Resolves the FQN of a method's first parameter type. Returns {@code null} when the
     * method has no parameters, the first slot is a Kotlin {@code J.Empty} placeholder, or
     * the type cannot be resolved (e.g. unresolved imports in a partially-typed LST).
     * Works for both regular methods and constructors — OpenRewrite represents them both
     * as {@link J.MethodDeclaration}.
     */
    private static String firstParameterFqn(J.MethodDeclaration md) {
        List<Statement> params = md.getParameters();
        if (params == null || params.isEmpty()) {
            return null;
        }
        Statement first = params.get(0);
        if (first instanceof J.Empty) {
            return null;
        }
        if (first instanceof J.VariableDeclarations vd && vd.getTypeExpression() != null) {
            JavaType.FullyQualified t = TypeUtils.asFullyQualified(vd.getTypeExpression().getType());
            if (t != null) {
                return t.getFullyQualifiedName();
            }
        }
        return null;
    }

    /**
     * Extracts the FQN referenced by {@code configureAggregate(MyAgg.class)} or
     * {@code configureAggregate<MyAgg>(...)} (Kotlin generic form). Returns {@code null}
     * if neither form yields a resolvable type.
     */
    private static String extractTypeArgOrFirstClassLiteral(J.MethodInvocation mi) {
        if (mi.getTypeParameters() != null && !mi.getTypeParameters().isEmpty()) {
            String fqn = TypeUtils.asFullyQualified(mi.getTypeParameters().get(0).getType()) == null
                    ? null
                    : TypeUtils.asFullyQualified(mi.getTypeParameters().get(0).getType()).getFullyQualifiedName();
            if (fqn != null) {
                return fqn;
            }
        }
        if (mi.getArguments() != null && !mi.getArguments().isEmpty()) {
            return fqnOfClassLiteral(mi.getArguments().get(0));
        }
        return null;
    }

    /**
     * Extracts the FQN referenced by a class literal expression: {@code X.class} (Java) or
     * {@code X::class.java} (Kotlin). Returns {@code null} when the type cannot be resolved.
     */
    private static String fqnOfClassLiteral(Expression e) {
        if (e instanceof J.FieldAccess fa) {
            String selectorName = fa.getName().getSimpleName();
            if ("class".equals(selectorName) || "java".equals(selectorName)) {
                JavaType.FullyQualified t = TypeUtils.asFullyQualified(fa.getTarget().getType());
                if (t != null) {
                    return t.getFullyQualifiedName();
                }
            }
        }
        JavaType.FullyQualified fallback = TypeUtils.asFullyQualified(e.getType());
        return fallback == null ? null : fallback.getFullyQualifiedName();
    }

    private static boolean isAggregateAnnotation(J.Annotation ann) {
        if (TypeUtils.isOfClassType(ann.getType(), AGGREGATE_FQN)) {
            return true;
        }
        String simple = annotationSimpleName(ann);
        return AGGREGATE_SIMPLE.equals(simple) || AGGREGATE_ROOT_SIMPLE.equals(simple);
    }

    private static String annotationSimpleName(J.Annotation ann) {
        if (ann.getAnnotationType() instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        return "";
    }

    private static String annotationArgumentName(Expression arg) {
        if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        return null;
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }
}
