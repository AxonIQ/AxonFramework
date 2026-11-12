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

package org.axonframework.extension.spring.jdbc;

import org.axonframework.common.jdbc.PhysicalConnectionAware;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * A lazy {@link DataSource} like Spring's {@link LazyConnectionDataSourceProxy}, whose connections are
 * {@link PhysicalConnectionAware}: they can tell whether a physical connection was taken from the pool for them yet.
 * <p>
 * Use it, in place of the plain data source, for the {@code PlatformTransactionManager} and the
 * {@link SpringDataSourceConnectionProvider} that are given to a
 * {@link org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager}. A unit of work then takes a
 * physical connection only once something needs one, and in the meantime the framework can read in parallel, using
 * other connections. As soon as the unit of work holds a physical connection, reads use that one, so a unit of work
 * never needs a second connection while it holds one. Without this data source, the framework assumes the connection
 * of a unit of work is in use from the start, and reads never use another one.
 * <p>
 * Every connection is given a lazy proxy of its own, on top of a target that only serves that connection. The lazy
 * proxy asks its target for a physical connection exactly when it needs one, whichever way that is triggered, so the
 * target knows precisely when the connection became physical. The default auto-commit mode and transaction isolation
 * level of the connections are determined once, on the first use, so that the lazy proxies do not take a physical
 * connection to find them out.
 * <p>
 * All components that use the same pool, including the event storage engine, should be given this data source, so
 * that they take part in the transaction of the unit of work.
 * <p>
 * Connections that are requested with credentials are plain connections of the target data source. They are not
 * tracked.
 *
 * @author John Hendrikx
 * @since 5.5.0
 */
public class TrackingLazyConnectionDataSource extends DelegatingDataSource {

    private final Object defaultsLock = new Object();

    private volatile Defaults defaults;

    /**
     * Constructs a new instance.
     *
     * @param targetDataSource the data source to take physical connections from, cannot be {@code null}
     * @throws NullPointerException if {@code targetDataSource} is {@code null}
     */
    public TrackingLazyConnectionDataSource(DataSource targetDataSource) {
        super(Objects.requireNonNull(targetDataSource, "The target data source cannot be null."));
    }

    @Override
    public Connection getConnection() throws SQLException {
        Defaults connectionDefaults = defaults();
        PhysicalTarget physicalTarget = new PhysicalTarget(obtainTargetDataSource());
        LazyConnectionDataSourceProxy lazyDataSource = new LazyConnectionDataSourceProxy(physicalTarget);

        lazyDataSource.setDefaultAutoCommit(connectionDefaults.autoCommit());
        lazyDataSource.setDefaultTransactionIsolation(connectionDefaults.transactionIsolation());

        return (Connection) Proxy.newProxyInstance(
            TrackingLazyConnectionDataSource.class.getClassLoader(),
            new Class<?>[]{ConnectionProxy.class, PhysicalConnectionAware.class},
            new LogicalConnectionHandler(lazyDataSource.getConnection(), physicalTarget)
        );
    }

    private Defaults defaults() throws SQLException {
        Defaults result = defaults;

        if (result == null) {
            synchronized (defaultsLock) {
                result = defaults;

                if (result == null) {
                    try (Connection connection = obtainTargetDataSource().getConnection()) {
                        result = new Defaults(connection.getAutoCommit(), connection.getTransactionIsolation());
                    }

                    defaults = result;
                }
            }
        }

        return result;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private record Defaults(boolean autoCommit, int transactionIsolation) {
    }

    /*
     * Serves the physical connection of a single logical connection, and knows whether it has been taken. The lock
     * lets a decision rely on the connection not being taken for as long as it runs.
     */
    private static final class PhysicalTarget extends DelegatingDataSource {

        private final ReadWriteLock guard = new ReentrantReadWriteLock();

        private volatile boolean physical;
        private Connection connection;  // guarded by the write lock of the guard

        PhysicalTarget(DataSource pool) {
            super(pool);
        }

        @Override
        public Connection getConnection() throws SQLException {
            guard.writeLock().lock();

            try {
                if (connection == null) {
                    connection = obtainTargetDataSource().getConnection();
                    physical = true;
                }

                return connection;
            }
            finally {
                guard.writeLock().unlock();
            }
        }

        <R> R withPhysicalConnectionShielded(Function<Boolean, R> decision) {
            guard.readLock().lock();

            try {
                return decision.apply(physical);
            }
            finally {
                guard.readLock().unlock();
            }
        }
    }

    private static final class LogicalConnectionHandler implements InvocationHandler {

        private final Connection lazy;
        private final PhysicalTarget target;

        LogicalConnectionHandler(Connection lazy, PhysicalTarget target) {
            this.lazy = lazy;
            this.target = target;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "isPhysicallyConnected" -> {
                    return target.physical;
                }
                case "withPhysicalConnectionShielded" -> {
                    return target.withPhysicalConnectionShielded((Function<Boolean, Object>) args[0]);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "unwrap" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                }
                case "isWrapperFor" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return true;
                    }
                }
                default -> {
                    // everything else is served by the lazy connection
                }
            }

            return TrackingLazyConnectionDataSource.invoke(lazy, method, args);
        }
    }
}
