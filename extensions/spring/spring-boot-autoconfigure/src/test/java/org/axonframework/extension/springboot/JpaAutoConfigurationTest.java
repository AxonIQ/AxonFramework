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

package org.axonframework.extension.springboot;

import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.extension.springboot.autoconfig.JpaAutoConfiguration;
import org.axonframework.extension.springboot.util.jpa.ContainerManagedEntityManagerProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.sql.SQLException;
import java.time.Duration;
import java.time.temporal.TemporalAmount;
import java.util.Map;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests JPA auto-configuration
 *
 * @author Sara Pellegrini
 * @author Simon Zambrovski
 */
class JpaAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0");
    }

    @Test
    void contextInitialization() {
        testContext
                .run(context -> {
            Map<String, EntityManagerProvider> entityManagerProviders =
                    context.getBeansOfType(EntityManagerProvider.class);
            assertTrue(entityManagerProviders.containsKey("entityManagerProvider"));
            assertEquals(ContainerManagedEntityManagerProvider.class,
                         entityManagerProviders.get("entityManagerProvider").getClass());

            Map<String, TokenStore> tokenStores =
                    context.getBeansOfType(TokenStore.class);
            assertTrue(tokenStores.containsKey("tokenStore"));
            assertEquals(JpaTokenStore.class,
                         tokenStores.get("tokenStore").getClass());

            /*
            TODO re-enable as part of #3097
            //noinspection rawtypes
            Map<String, SagaStore> sagaStores =
                    context.getBeansOfType(SagaStore.class);
            assertTrue(sagaStores.containsKey("sagaStore"));
            assertEquals(JpaSagaStore.class,
                         sagaStores.get("sagaStore").getClass());
             */
            PersistenceExceptionResolver persistenceExceptionResolver =
                    context.getBean("persistenceExceptionResolver", PersistenceExceptionResolver.class);
            assertThat(persistenceExceptionResolver).isInstanceOf(SmartLifecycle.class);
            assertThat(((SmartLifecycle) persistenceExceptionResolver).isRunning()).isTrue();
        });
    }

    @Nested
    class PersistenceExceptionResolverConfiguration {

        @Test
        void accessesDataSourceOnlyWhenLifecycleStarts() throws SQLException {
            // given
            JDBCDataSource actualDataSource = new JDBCDataSource();
            actualDataSource.setUrl("jdbc:hsqldb:mem:lifecycle-aware-resolver");
            actualDataSource.setUser("sa");
            actualDataSource.setPassword("");
            DataSource dataSource = spy(actualDataSource);

            // when
            PersistenceExceptionResolver resolver =
                    new JpaAutoConfiguration().persistenceExceptionResolver(dataSource);

            // then
            verify(dataSource, never()).getConnection();
            assertThat(resolver.isDuplicateKeyViolation(new SQLException("duplicate", "", -104))).isFalse();

            // when
            SmartLifecycle lifecycle = (SmartLifecycle) resolver;
            lifecycle.start();

            // then
            verify(dataSource).getConnection();
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(lifecycle.getPhase()).isEqualTo(Phase.EXTERNAL_CONNECTIONS);
            assertThat(resolver.isDuplicateKeyViolation(new SQLException("duplicate", "", -104))).isTrue();
        }
    }

    @Test
    void setTokenStoreClaimTimeout() {
        testContext
                .withPropertyValues("axon.eventhandling.tokenstore.claim-timeout=3001")
                   .run(context -> {
                       Map<String, TokenStore> tokenStores =
                               context.getBeansOfType(TokenStore.class);
                       assertTrue(tokenStores.containsKey("tokenStore"));
                       TokenStore tokenStore = tokenStores.get("tokenStore");
                       TemporalAmount tokenClaimInterval = ReflectionUtils.getFieldValue(
                               JpaTokenStore.class.getDeclaredField("claimTimeout"), tokenStore
                       );
                       assertEquals(Duration.ofMillis(3001), tokenClaimInterval);
                   });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class TestContext {

        @Bean
        public EntityManagerFactory entityManagerFactory() {
            return mock();
        }
    }
}
