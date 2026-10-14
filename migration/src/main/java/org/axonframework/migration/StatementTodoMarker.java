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
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Leaves {@code // TODO(axon4to5):} markers on the statements of a block that contain a method invocation needing
 * manual migration.
 * <p>
 * The marker goes on the statement rather than on the invocation itself, so a call in the middle of a multi-line
 * fluent chain is flagged on its own line above the chain, where a reader and a follow-up tool find it, instead of
 * being wedged between two chained calls. A statement holding several such invocations gets one marker per distinct
 * marker key, in source order. Invocations inside a nested block (a lambda body, an anonymous class) belong to the
 * statements of that block, which are marked when that block is visited.
 * <p>
 * Internal to the migration recipes, which share it so their markers render the same way.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class StatementTodoMarker {

    /**
     * A marker to leave: the {@code key} is a stable substring of the {@code text} that makes marking idempotent.
     *
     * @param key  the stable substring identifying the marker in an existing comment
     * @param text the full comment text, starting with a space after the {@code //}
     */
    record Todo(String key, String text) {

    }

    private StatementTodoMarker() {
    }

    /**
     * Marks each statement of the given {@code block} that contains an invocation for which {@code todoFor} returns a
     * {@link Todo}.
     *
     * @param block   the block whose statements to inspect
     * @param todoFor the marker an invocation needs, or {@code null} when it needs none
     * @return the block with its statements marked, or the same block when nothing needs a marker
     */
    static J.Block markStatements(J.Block block, Function<J.MethodInvocation, @Nullable Todo> todoFor) {
        return block.withStatements(ListUtils.map(block.getStatements(), statement -> {
            Statement marked = statement;
            for (Todo todo : todosIn(statement, todoFor)) {
                marked = mark(marked, todo);
            }
            return marked;
        }));
    }

    private static List<Todo> todosIn(Statement statement, Function<J.MethodInvocation, @Nullable Todo> todoFor) {
        List<Todo> todos = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block nested, Integer p) {
                // The statements of a nested block are marked on their own.
                return nested;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, Integer p) {
                J.MethodInvocation visited = super.visitMethodInvocation(invocation, p);
                Todo todo = todoFor.apply(visited);
                if (todo != null && todos.stream().noneMatch(existing -> existing.key().equals(todo.key()))) {
                    todos.add(todo);
                }
                return visited;
            }
        }.visit(statement, 0);
        return todos;
    }

    /**
     * Prepends the TODO line comment to the element's prefix, keeping the element on its original indent below it.
     * Leaves the element alone when its prefix already holds a comment containing the marker key.
     *
     * @param element the element to mark
     * @param todo    the marker to leave
     * @param <T>     the type of the element
     * @return the marked element
     */
    static <T extends J> T mark(T element, Todo todo) {
        Space prefix = element.getPrefix();
        for (Comment comment : prefix.getComments()) {
            if (comment instanceof TextComment text && text.getText().contains(todo.key())) {
                return element;
            }
        }
        String leading = prefix.getComments().isEmpty()
                ? prefix.getWhitespace()
                : prefix.getComments().getLast().getSuffix();
        String indent = leading.contains("\n") ? leading.substring(leading.lastIndexOf('\n') + 1) : "";
        TextComment comment = new TextComment(false, todo.text(), "\n" + indent, Markers.EMPTY);
        return element.withPrefix(prefix.withComments(ListUtils.concat(prefix.getComments(), comment)));
    }
}
