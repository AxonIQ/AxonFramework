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

package org.axonframework.extension.spring.eventhandling.tokenstore;

import com.atomikos.icatch.jta.UserTransactionManager;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.conversion.TestConverter;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.extension.spring.messaging.unitofwork.NonTransactionalConnectionException;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.GenericTokenTableFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hsqldb.jdbc.JDBCDataSource;
import org.hsqldb.jdbc.pool.JDBCXADataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.jta.JtaTransactionManager;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * Tests how the {@link JdbcTokenStore} behaves in the transaction setups commonly found in production: the connection
 * of the unit of work must either take part in its transaction, or be refused with a
 * {@link NonTransactionalConnectionException}.
 *
 * @author John Hendrikx
 */
class JdbcTokenStoreTransactionSetupsTest {

    private static final String PROCESSOR = "processor";
    private static final String ATOMIKOS_ENABLE_LOGGING = "com.atomikos.icatch.enable_logging";
    private static final String ATOMIKOS_LOG_BASE_DIR = "com.atomikos.icatch.log_base_dir";

    private static UserTransactionManager jta;

    @BeforeAll
    static void startJta() throws Exception {
        System.setProperty(ATOMIKOS_ENABLE_LOGGING, "false");
        System.setProperty(ATOMIKOS_LOG_BASE_DIR, Files.createTempDirectory("atomikos").toAbsolutePath().toString());
        jta = new UserTransactionManager();
        jta.init();
    }

    @AfterAll
    static void stopJta() {
        jta.close();
        System.clearProperty(ATOMIKOS_ENABLE_LOGGING);
        System.clearProperty(ATOMIKOS_LOG_BASE_DIR);
    }

    @Test
    void shouldWorkWithDataSourceTransactionManagerOnTheSameDataSource() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        Setup setup = jdbcSetup(new DataSourceTransactionManager(ds), ds, ds);

        // when / then
        assertWorks(setup);
    }

    @Test
    void shouldWorkWithDataSourceTransactionManagerOnTheSameHikariPool() throws Exception {
        // given
        HikariDataSource ds = hikari(uniqueDbName(), true);
        Setup setup = jdbcSetup(new DataSourceTransactionManager(ds), ds, ds);

        // when / then
        assertWorks(setup);
        ds.close();
    }

    @Test
    void shouldWorkWithHibernateJpaTransactionManagerOnTheSameDataSource() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        EntityManagerFactory emf = emf(ds, true);
        Setup setup = jdbcSetup(new JpaTransactionManager(emf), ds, ds);

        // when / then
        assertWorks(setup);
    }

    @Test
    void shouldWorkWithALazyConnectionDataSourceProxy() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        DataSource lazy = new LazyConnectionDataSourceProxy(ds);
        Setup setup = jdbcSetup(new DataSourceTransactionManager(lazy), lazy, ds);

        // when / then
        assertWorks(setup);
    }

    @Test
    void shouldWorkWithATransactionAwareDataSourceProxyForTheConnectionProvider() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        Setup setup = jdbcSetup(new DataSourceTransactionManager(ds), new TransactionAwareDataSourceProxy(ds), ds);

        // when / then
        assertWorks(setup);
    }

    @Test
    void shouldRefuseAJpaTransactionManagerWithoutAHibernateVendorAdapter() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        EntityManagerFactory emf = emf(ds, false);
        Setup setup = jdbcSetup(new JpaTransactionManager(emf), ds, ds);

        // when / then
        assertRefused(setup);
    }

    @Test
    void shouldRefuseAJpaTransactionManagerThatOnlyKnowsTheNativeEntityManagerFactory() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        LocalContainerEntityManagerFactoryBean bean = emfBean(ds, true);
        Setup setup = jdbcSetup(new JpaTransactionManager(bean.getNativeEntityManagerFactory()), ds, ds);

        // when / then
        assertRefused(setup);
    }

    @Test
    void shouldRefuseAConnectionProviderOnASecondPoolForTheSameDatabase() throws Exception {
        // given
        String db = uniqueDbName();
        DataSource txPool = hsqlDataSource(db);
        DataSource tokenPool = hsqlDataSource(db);
        Setup setup = jdbcSetup(new DataSourceTransactionManager(txPool), tokenPool, tokenPool);

        // when / then
        assertRefused(setup);
    }

    /*
     * Known gap: a connection that does not auto-commit is assumed to be part of a transaction. A pool configured
     * that way is not detected, and nothing ever commits the statements run on it. The failure is not subtle
     * however: no token is ever persisted, so the problem shows up the first time a processor runs.
     */
    @Test
    void shouldNotDetectASecondPoolThatHandsOutConnectionsWithAutoCommitDisabled() throws Exception {
        // given
        String db = uniqueDbName();
        HikariDataSource txPool = hikari(db, true);
        HikariDataSource tokenPool = hikari(db, false);

        try {
            Setup setup = jdbcSetup(new DataSourceTransactionManager(txPool), tokenPool, tokenPool);

            // when
            joinAndUnwrap(setup.uowf().create().executeWithResult(
                    ctx -> setup.store().initializeTokenSegments(PROCESSOR, 1, null, ctx)));

            // then
            assertThat(committedSegments(tokenPool)).isZero();
        } finally {
            txPool.close();
            tokenPool.close();
        }
    }

    @Test
    void shouldWorkWithJtaAndAnXaDataSource() throws Exception {
        // given
        String db = uniqueDbName();
        AtomikosDataSourceBean xa = xaDataSource(db);

        try {
            /* the schema is created outside the Atomikos pool, so no pooled connection carries state from it */
            Setup setup = jdbcSetup(new JtaTransactionManager(jta, jta), xa, hsqlDataSource(db));

            // when / then
            assertWorks(setup);
        } finally {
            xa.close();
        }
    }

    private static void assertWorks(Setup setup) throws SQLException {
        joinAndUnwrap(setup.uowf().create().executeWithResult(
                ctx -> setup.store().initializeTokenSegments(PROCESSOR, 1, null, ctx)));

        assertThat(committedSegments(setup.countDataSource())).isEqualTo(1);
    }

    private static void assertRefused(Setup setup) {
        assertThatThrownBy(() -> joinAndUnwrap(setup.uowf().create().executeWithResult(
                ctx -> setup.store().initializeTokenSegments(PROCESSOR, 1, null, ctx))))
                .isInstanceOf(NonTransactionalConnectionException.class);
    }

    private static int committedSegments(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM TokenEntry WHERE processorName = '" + PROCESSOR + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private record Setup(UnitOfWorkFactory uowf, TokenStore store, DataSource countDataSource) {

    }

    /**
     * Builds what Spring Boot builds for a JDBC token store: a {@code SpringTransactionManager} over the given
     * transaction manager with a {@code SpringDataSourceConnectionProvider} on {@code providerDataSource}, and a
     * {@code JdbcTokenStore} that takes its connection from the unit of work.
     */
    private static Setup jdbcSetup(PlatformTransactionManager ptm,
                                   DataSource providerDataSource,
                                   DataSource schemaDataSource) throws SQLException {
        ConnectionProvider provider = new SpringDataSourceConnectionProvider(providerDataSource);
        JdbcTokenStore store = new JdbcTokenStore(new JdbcTransactionalExecutorProvider(schemaDataSource),
                                                  TestConverter.JACKSON.getConverter(),
                                                  JdbcTokenStoreConfiguration.DEFAULT);
        store.createSchema(GenericTokenTableFactory.INSTANCE);
        UnitOfWorkFactory unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new SpringTransactionManager(ptm, null, provider),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));

        return new Setup(unitOfWorkFactory, store, schemaDataSource);
    }

    private static String uniqueDbName() {
        return "setups" + UUID.randomUUID().toString().replace("-", "");
    }

    private static DataSource hsqlDataSource(String db) {
        JDBCDataSource ds = new JDBCDataSource();
        ds.setUrl("jdbc:hsqldb:mem:" + db);
        ds.setUser("SA");
        ds.setPassword("");
        return ds;
    }

    private static HikariDataSource hikari(String db, boolean autoCommit) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:hsqldb:mem:" + db);
        config.setUsername("SA");
        config.setPassword("");
        config.setAutoCommit(autoCommit);
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private static AtomikosDataSourceBean xaDataSource(String db) {
        AtomikosDataSourceBean xa = new AtomikosDataSourceBean();
        xa.setUniqueResourceName("xa" + db);
        xa.setXaDataSourceClassName(JDBCXADataSource.class.getName());
        Properties props = new Properties();
        props.setProperty("url", "jdbc:hsqldb:mem:" + db);
        props.setProperty("user", "SA");
        props.setProperty("password", "");
        xa.setXaProperties(props);
        xa.setPoolSize(3);
        return xa;
    }

    private static EntityManagerFactory emf(DataSource ds, boolean vendorAdapter) {
        return emfBean(ds, vendorAdapter).getObject();
    }

    /*
     * Spring Boot always configures a HibernateJpaVendorAdapter, whose dialect lets Spring expose the JDBC connection
     * of a JPA transaction. Without one Spring falls back to a dialect that cannot.
     */
    private static LocalContainerEntityManagerFactoryBean emfBean(DataSource ds, boolean vendorAdapter) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();

        bean.setPersistenceProvider(new HibernatePersistenceProvider());
        if (vendorAdapter) {
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        }
        bean.setPackagesToScan(TokenEntry.class.getPackage().getName());
        bean.setDataSource(ds);
        bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        bean.afterPropertiesSet();

        return bean;
    }
}
