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
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.conversion.TestConverter;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenStoreTransactionRequiredException;
import org.hibernate.engine.transaction.jta.platform.internal.AbstractJtaPlatform;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hsqldb.jdbc.JDBCDataSource;
import org.hsqldb.jdbc.pool.JDBCXADataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.jta.JtaTransactionManager;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * Tests how the {@link JpaTokenStore} behaves in the transaction setups commonly found in production: the token store
 * must either work and commit together with the unit of work, or refuse to run with a
 * {@link TokenStoreTransactionRequiredException}.
 *
 * @author John Hendrikx
 */
class JpaTokenStoreTransactionSetupsTest {

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
    void shouldWorkWithJpaTransactionManagerAndASharedEntityManager() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        EntityManagerFactory emf = emf(ds, false, "create-drop");
        Setup setup = jpaSetup(new JpaTransactionManager(emf), emf, emf, ds);

        // when / then
        assertWorks(setup);
    }

    @Test
    void shouldRefuseAnEntityManagerFactoryOtherThanTheOneOfTheTransactionManager() throws Exception {
        // given
        String db = uniqueDbName();
        DataSource ds1 = hsqlDataSource(db);
        DataSource ds2 = hsqlDataSource(db);
        EntityManagerFactory txEmf = emf(ds1, false, "create-drop");
        EntityManagerFactory otherEmf = emf(ds2, false, "none");
        Setup setup = jpaSetup(new JpaTransactionManager(txEmf), otherEmf, otherEmf, ds1);

        // when / then
        assertRefused(setup);
    }

    @Test
    void shouldRefuseAJpaTokenStoreWhenOnlyADataSourceTransactionManagerIsPresent() throws Exception {
        // given
        DataSource ds = hsqlDataSource(uniqueDbName());
        EntityManagerFactory emf = emf(ds, false, "create-drop");
        Setup setup = jpaSetup(new DataSourceTransactionManager(ds), emf, emf, ds);

        // when / then
        assertRefused(setup);
    }

    @Test
    void shouldWorkWithJpaAndJta() throws Exception {
        // given
        AtomikosDataSourceBean xa = xaDataSource(uniqueDbName());

        try {
            EntityManagerFactory emf = emf(xa, true, "create-drop");
            Setup setup = jpaSetup(new JtaTransactionManager(jta, jta), emf, emf, xa);

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
                .isInstanceOf(TokenStoreTransactionRequiredException.class);
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
     * Builds what Spring Boot builds for a JPA token store: a {@code SpringTransactionManager} over the given
     * transaction manager with a shared entity manager of {@code providerEmf}, and a {@code JpaTokenStore} that takes
     * its entity manager from the unit of work.
     */
    private static Setup jpaSetup(PlatformTransactionManager ptm,
                                  EntityManagerFactory providerEmf,
                                  EntityManagerFactory storeEmf,
                                  DataSource countDataSource) {
        EntityManagerProvider provider =
                new SimpleEntityManagerProvider(SharedEntityManagerCreator.createSharedEntityManager(providerEmf));
        JpaTokenStore store = new JpaTokenStore(new JpaTransactionalExecutorProvider(storeEmf),
                                                TestConverter.JACKSON.getConverter(),
                                                JpaTokenStoreConfiguration.DEFAULT);
        UnitOfWorkFactory unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new SpringTransactionManager(ptm, provider, null),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));

        return new Setup(unitOfWorkFactory, store, countDataSource);
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

    private static EntityManagerFactory emf(DataSource ds, boolean jtaMode, String ddl) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        Map<String, Object> props = new HashMap<>();

        bean.setPersistenceProvider(new HibernatePersistenceProvider());
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        bean.setPackagesToScan(TokenEntry.class.getPackage().getName());
        props.put("hibernate.hbm2ddl.auto", ddl);

        if (jtaMode) {
            bean.setJtaDataSource(ds);
            props.put("hibernate.transaction.coordinator_class", "jta");
            props.put("hibernate.transaction.jta.platform", AtomikosPlatform.class.getName());
        }
        else {
            bean.setDataSource(ds);
        }

        bean.setJpaPropertyMap(props);
        bean.afterPropertiesSet();

        return bean.getObject();
    }

    /**
     * Hibernate JTA platform on top of the Atomikos transaction manager of this test.
     */
    public static class AtomikosPlatform extends AbstractJtaPlatform {

        @Override
        protected TransactionManager locateTransactionManager() {
            return jta;
        }

        @Override
        protected UserTransaction locateUserTransaction() {
            return jta;
        }
    }
}
