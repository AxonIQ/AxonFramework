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

package org.axonframework.messaging.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

/**
 * Describes functionality off processes which can be 'in scope', like an Aggregate or Saga.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public abstract class Scope {

    private static final Logger logger = LoggerFactory.getLogger(Scope.class);

    private static final ThreadLocal<Deque<Scope>> CURRENT_SCOPE = ThreadLocal.withInitial(LinkedList::new);

    /**
     * Retrieve the current {@link Scope}.
     *
     * @param <S> a type implementing {@link Scope}
     * @return the current {@link Scope}
     *
     * @throws IllegalStateException in case no current {@link Scope} is active, in which case #startScope() should be
     *                               called first
     */
    @SuppressWarnings("unchecked")
    public static <S extends Scope> S getCurrentScope() throws IllegalStateException {
        try {
            return (S) CURRENT_SCOPE.get().getFirst();
        } catch (NoSuchElementException e) {
            throw new IllegalStateException("Cannot request current Scope if none is active");
        }
    }

    /**
     * Provide a description of the current {@link Scope}.
     *
     * @return a {@link ScopeDescriptor} describing the current {@link Scope}
     */
    public static ScopeDescriptor describeCurrentScope() {
        return getCurrentScope().describeScope();
    }

    /**
     * Start a {@link Scope} by adding {@code this} to a {@link java.util.Deque} contained in a
     * {@link java.lang.ThreadLocal}.
     */
    protected void startScope() {
        CURRENT_SCOPE.get().push(this);
    }

    /**
     * End a {@link Scope} by removing {@code this} from a {@link java.util.Deque} contained in a
     * {@link java.lang.ThreadLocal}.
     * If {@code this} isn't on the top of the Deque, an {@link IllegalStateException} will be thrown, as that signals a
     * process is trying to end somebody else's scope.
     * If the Deque is empty, it will be removed from the ThreadLocal.
     */
    protected void endScope() {
        Deque<Scope> scopes = CURRENT_SCOPE.get();
        if (this != scopes.peek()) {
            throw new IllegalStateException(
                    "Incorrectly trying to end another Scope then which the calling process is contained in."
            );
        }
        scopes.pop();

        if (scopes.isEmpty()) {
            logger.debug("Clearing out ThreadLocal current Scope, as no Scopes are present");
            CURRENT_SCOPE.remove();
        }
    }

    /**
     * {@link Scope} instance method to execute given {@code task} of type {@link Callable} in the context of this
     * Scope. This update the thread's current scope before executing the task. If a scope is already registered with
     * the current thread that one will be temporarily replaced with this scope until the task completes. This method
     * returns the execution result of the task.
     *
     * @param task the task to execute of type  {@link Callable}
     * @param <V>  the type of execution result of the task
     * @return the execution result of type {@code V}
     *
     * @throws Exception if executing the task results in an exception
     */
    protected <V> V executeWithResult(Callable<V> task) throws Exception {
        startScope();
        try {
            return task.call();
        } finally {
            endScope();
        }
    }

    /**
     * Provide a description of this {@link Scope}.
     *
     * @return a {@link ScopeDescriptor} describing this {@link Scope}
     */
    public abstract ScopeDescriptor describeScope();
}
