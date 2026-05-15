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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.JdbcSQLErrorCodesResolver;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.TokenSchema;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.time.temporal.TemporalAmount;
import java.util.Map;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests JDBC auto-configuration.
 *
 * @author Milan Savic
 */
class JdbcAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class);
    }

    @Test
    void allJdbcComponentsAutoConfigured() {
        testContext.run(context -> {
            assertThat(context).getBean(ConnectionProvider.class).isInstanceOf(SpringDataSourceConnectionProvider.class);
            assertThat(context).getBean(TokenStore.class).isInstanceOf(JdbcTokenStore.class);
            assertThat(context).getBean(PersistenceExceptionResolver.class).isInstanceOf(JdbcSQLErrorCodesResolver.class);
        });
    }

    @Test
    void defaultTokenSchemaDefinedWhenNoneAvailable() {
        testContext.run(context -> {
            assertThat(context).hasSingleBean(TokenSchema.class);
            assertThat(context).hasSingleBean(TokenStore.class);
        });
    }

    @Test
    void customTokenSchema() {
        TokenSchema tokenSchema = TokenSchema.builder()
                                             .setTokenTable("TEST123")
                                             .build();

        testContext.withBean(TokenSchema.class, () -> tokenSchema)
                   .run(context -> {
                       assertThat(context).hasSingleBean(TokenStore.class);
                       assertThat(context).getBean(TokenStore.class).extracting("schema").isSameAs(tokenSchema);
                   });
    }

    @Test
    void setTokenStoreClaimTimeout() {
        testContext.withPropertyValues("axon.eventhandling.tokenstore.claim-timeout=10m")
                   .run(context -> {
                       Map<String, TokenStore> tokenStores = context.getBeansOfType(TokenStore.class);
                       assertTrue(tokenStores.containsKey("tokenStore"));
                       TokenStore tokenStore = tokenStores.get("tokenStore");
                       TemporalAmount tokenClaimInterval = ReflectionUtils.getFieldValue(
                               JdbcTokenStore.class.getDeclaredField("claimTimeout"), tokenStore
                       );
                       assertEquals(Duration.ofMinutes(10L), tokenClaimInterval);
                   });
    }

    @ContextConfiguration
    @EnableAutoConfiguration(exclude = {
            JpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    private static class TestContext {

        @Bean
        public DataSource dataSource() {
            return mock(DataSource.class);
        }
    }
}
