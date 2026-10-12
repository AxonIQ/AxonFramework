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
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangePackage;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;

import java.util.List;

/**
 * Moves a package like OpenRewrite's {@link ChangePackage}, but keeps the given types in the old package.
 * <p>
 * A file referencing nothing of the old package but excluded types is left alone. Any other file is moved by
 * {@link ChangePackage}, after which the excluded types are moved back with {@link ChangeType}. Running
 * {@link ChangePackage} and a {@link ChangeType} back as two steps of a composite recipe instead moves an excluded type
 * back and forth in every cycle of a run, so the run never settles.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class ChangePackageExcludingTypes extends Recipe {

    @Option(displayName = "Old package name", description = "The package to move.", example = "com.yourorg.foo")
    private final String oldPackageName;

    @Option(displayName = "New package name", description = "The package to move to.", example = "com.yourorg.bar")
    private final String newPackageName;

    @Option(displayName = "Recursive", description = "Whether to move the subpackages too.", required = false)
    private final @Nullable Boolean recursive;

    @Option(displayName = "Excluded types",
            description = "The fully qualified names of the types of the old package that stay in it.",
            example = "com.yourorg.foo.Kept")
    private final List<String> excludedTypes;

    /**
     * Creates the recipe moving the given package, keeping the given types in it.
     *
     * @param oldPackageName the package to move
     * @param newPackageName the package to move to
     * @param recursive      whether to move the subpackages too, {@code false} when {@code null}
     * @param excludedTypes  the fully qualified names of the types of the old package that stay in it, none when
     *                       {@code null}
     */
    @JsonCreator
    public ChangePackageExcludingTypes(@JsonProperty("oldPackageName") String oldPackageName,
                                       @JsonProperty("newPackageName") String newPackageName,
                                       @JsonProperty("recursive") @Nullable Boolean recursive,
                                       @JsonProperty("excludedTypes") @Nullable List<String> excludedTypes) {
        this.oldPackageName = oldPackageName;
        this.newPackageName = newPackageName;
        this.recursive = recursive;
        this.excludedTypes = excludedTypes == null ? List.of() : List.copyOf(excludedTypes);
    }

    /**
     * Returns the package this recipe moves.
     *
     * @return the old package name
     */
    public String getOldPackageName() {
        return oldPackageName;
    }

    /**
     * Returns the package this recipe moves to.
     *
     * @return the new package name
     */
    public String getNewPackageName() {
        return newPackageName;
    }

    /**
     * Returns whether this recipe moves the subpackages too.
     *
     * @return whether subpackages move, or {@code null} for the default of not moving them
     */
    public @Nullable Boolean getRecursive() {
        return recursive;
    }

    /**
     * Returns the types of the old package this recipe keeps in it.
     *
     * @return the fully qualified names of the excluded types
     */
    public List<String> getExcludedTypes() {
        return excludedTypes;
    }

    @Override
    public String getDisplayName() {
        return "Change a package, keeping some of its types";
    }

    @Override
    public String getDescription() {
        return "Moves a package like `org.openrewrite.java.ChangePackage`, but keeps the excluded types in the old "
                + "package. A file referencing nothing of the old package but excluded types is left alone.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> changePackage =
                new ChangePackage(oldPackageName, newPackageName, recursive).getVisitor();
        List<TreeVisitor<?, ExecutionContext>> moveBack = excludedTypes.stream()
                .<TreeVisitor<?, ExecutionContext>>map(type -> new ChangeType(
                        newPackageName + type.substring(oldPackageName.length()), type, null
                ).getVisitor())
                .toList();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public boolean isAcceptable(SourceFile sourceFile, ExecutionContext ctx) {
                return changePackage.isAcceptable(sourceFile, ctx);
            }

            @Override
            public @Nullable Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                if (tree instanceof JavaSourceFile sourceFile && referencesExcludedTypesOnly(sourceFile)) {
                    return tree;
                }
                Tree moved = changePackage.visit(tree, ctx, getCursor().getParentOrThrow());
                for (TreeVisitor<?, ExecutionContext> visitor : moveBack) {
                    if (moved == tree || moved == null) {
                        break;
                    }
                    moved = visitor.visit(moved, ctx, getCursor().getParentOrThrow());
                }
                return moved;
            }
        };
    }

    private boolean referencesExcludedTypesOnly(JavaSourceFile sourceFile) {
        if (sourceFile.getPackageDeclaration() != null
                && inOldPackage(sourceFile.getPackageDeclaration().getPackageName())) {
            return false;
        }
        for (J.Import anImport : sourceFile.getImports()) {
            if (inOldPackage(anImport.getPackageName()) && !excluded(anImport.getTypeName())) {
                return false;
            }
        }
        for (JavaType type : sourceFile.getTypesInUse().getTypesInUse()) {
            if (type instanceof JavaType.FullyQualified fullyQualified
                    && inOldPackage(fullyQualified.getPackageName())
                    && !excluded(fullyQualified.getFullyQualifiedName())) {
                return false;
            }
        }
        return !sourceFile.getTypesInUse().hasDocReferenceInPackage(oldPackageName, Boolean.TRUE.equals(recursive));
    }

    private boolean inOldPackage(String packageName) {
        return packageName.equals(oldPackageName)
                || Boolean.TRUE.equals(recursive) && packageName.startsWith(oldPackageName + ".");
    }

    private boolean excluded(String typeName) {
        return excludedTypes.stream()
                            .anyMatch(type -> typeName.equals(type) || typeName.startsWith(type + "$")
                                    || typeName.startsWith(type + "."));
    }
}
