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

package org.axonframework.messaging.unitofwork;

import org.axonframework.messaging.core.Metadata;
import org.jspecify.annotations.Nullable;


import java.util.Deque;
import java.util.LinkedList;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Default entry point to gain access to the current UnitOfWork. Components managing transactional boundaries can
 * register and clear UnitOfWork instances, which components can use.
 *
 * @author Allard Buijze
 * @since 0.6
 * @deprecated This instance will be removed.
 */
@Deprecated(since ="5.0.0")
public abstract class CurrentUnitOfWork {

    private static final ThreadLocal<@Nullable Deque<LegacyUnitOfWork<?>>> CURRENT = new ThreadLocal<>();

    /**
     * Indicates whether a unit of work has already been started. This method can be used by interceptors to prevent
     * nesting of UnitOfWork instances.
     *
     * @return whether a UnitOfWork has already been started.
     */
    public static boolean isStarted() {
        return CURRENT.get() != null && !requireNonNull(CURRENT.get()).isEmpty();
    }

    /**
     * If a UnitOfWork is started, invokes the given {@code consumer} with the active Unit of Work. Otherwise,
     * it does nothing
     *
     * @param consumer The consumer to invoke if a Unit of Work is active
     * @return {@code true} if a unit of work is active, {@code false} otherwise
     */
    public static boolean ifStarted(Consumer<LegacyUnitOfWork<?>> consumer) {
        if (isStarted()) {
            consumer.accept(get());
            return true;
        }
        return false;
    }

    /**
     * If a Unit of Work is started, execute the given {@code function} on it. Otherwise, returns an empty Optional.
     * Use this method when you wish to retrieve information from a Unit of Work, reverting to a default when no Unit
     * of Work is started.
     *
     * @param function The function to apply to the unit of work, if present
     * @param <T>      The type of return value expected
     * @return an optional containing the result of the function, or an empty Optional when no Unit of Work was started
     * @throws NullPointerException when a Unit of Work is present and the function returns null
     */
    public static <T> Optional<T> map(Function<LegacyUnitOfWork<?>, T> function) {
        return isStarted() ? Optional.ofNullable(function.apply(get())) : Optional.empty();
    }

    /**
     * Gets the UnitOfWork bound to the current thread. If no UnitOfWork has been started, an {@link
     * IllegalStateException} is thrown.
     * <p/>
     * To verify whether a UnitOfWork is already active, use {@link #isStarted()}.
     *
     * @return The UnitOfWork bound to the current thread.
     * @throws IllegalStateException if no UnitOfWork is active
     */
    public static LegacyUnitOfWork<?> get() {
        if (isEmpty()) {
            throw new IllegalStateException("No UnitOfWork is currently started for this thread.");
        }
        Deque<LegacyUnitOfWork<?>> unitsOfWork = requireNonNull(CURRENT.get());
        return requireNonNull(unitsOfWork.peek());
    }

    private static boolean isEmpty() {
        Deque<LegacyUnitOfWork<?>> unitsOfWork = CURRENT.get();
        return unitsOfWork == null || unitsOfWork.isEmpty();
    }

    /**
     * Commits the current UnitOfWork. If no UnitOfWork was started, an {@link IllegalStateException} is thrown.
     *
     * @throws IllegalStateException if no UnitOfWork is currently started.
     * @see LegacyUnitOfWork#commit()
     */
    public static void commit() {
        get().commit();
    }

    /**
     * Binds the given {@code unitOfWork} to the current thread. If other UnitOfWork instances were bound, they
     * will be marked as inactive until the given UnitOfWork is cleared.
     *
     * @param unitOfWork The UnitOfWork to bind to the current thread.
     */
    public static void set(LegacyUnitOfWork<?> unitOfWork) {
        if (CURRENT.get() == null) {
            CURRENT.set(new LinkedList<>());
        }
        CURRENT.get().push(unitOfWork);
    }

    /**
     * Clears the UnitOfWork currently bound to the current thread, if that UnitOfWork is the given
     * {@code unitOfWork}.
     *
     * @param unitOfWork The UnitOfWork expected to be bound to the current thread.
     * @throws IllegalStateException when the given UnitOfWork was not the current active UnitOfWork. This exception
     *                               indicates a potentially wrong nesting of Units Of Work.
     */
    public static void clear(LegacyUnitOfWork<?> unitOfWork) {
        if (!isStarted()) {
            throw new IllegalStateException("Could not clear this UnitOfWork. There is no UnitOfWork active.");
        }
        if (CURRENT.get().peek() == unitOfWork) {
            CURRENT.get().pop();
            if (CURRENT.get().isEmpty()) {
                CURRENT.remove();
            }
        } else {
            throw new IllegalStateException("Could not clear this UnitOfWork. It is not the active one.");
        }
    }

    /**
     * Returns the Correlation Data attached to the current Unit of Work, or an empty {@link Metadata} instance
     * if no Unit of Work is started.
     *
     * @return a Metadata instance representing the current Unit of Work's correlation data, or an empty Metadata
     * instance if no Unit of Work is started.
     * @see LegacyUnitOfWork#getCorrelationData()
     */
    public static Metadata correlationData() {
        return CurrentUnitOfWork.map(LegacyUnitOfWork::getCorrelationData).orElse(Metadata.emptyInstance());
    }

    private CurrentUnitOfWork() {
    }
}
