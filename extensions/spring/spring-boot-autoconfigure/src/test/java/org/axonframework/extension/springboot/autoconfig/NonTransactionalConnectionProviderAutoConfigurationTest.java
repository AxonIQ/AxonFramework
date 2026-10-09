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

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.extension.spring.messaging.unitofwork.NonTransactionalConnectionProviderException;
import org.axonframework.extension.springboot.NonTransactionalConnectionProviderFailureAnalyzer;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests how the transaction autoconfiguration treats a {@link ConnectionProvider} whose connections are not part of
 * the transactions of the {@link PlatformTransactionManager}.
 */
class NonTransactionalConnectionProviderAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcTransactionAutoConfiguration.class));
    }

    @Nested
    class WhenTheConnectionProviderUsesAnotherDataSource {

        @Test
        void applicationFailsToStart() {
            testContext.withUserConfiguration(SeparateDataSourcesContext.class)
                       .run(context -> assertThat(context)
                               .hasFailed()
                               .getFailure()
                               .hasRootCauseInstanceOf(NonTransactionalConnectionProviderException.class));
        }

        @Test
        void applicationStartsWhenAllowed() {
            testContext.withUserConfiguration(SeparateDataSourcesContext.class)
                       .withPropertyValues("axon.transaction.allow-non-transactional-connection-provider=true")
                       .run(context -> assertThat(context).hasSingleBean(TransactionManager.class));
        }

        @Test
        void failureAnalysisExplainsHowToAllowIt() {
            testContext.withUserConfiguration(SeparateDataSourcesContext.class).run(context -> {
                FailureAnalysis analysis = new NonTransactionalConnectionProviderFailureAnalyzer()
                        .analyze(context.getStartupFailure());

                assertThat(analysis).isNotNull();
                assertThat(analysis.getDescription()).contains("not part of the transaction");
                assertThat(analysis.getAction())
                        .contains("axon.transaction.allow-non-transactional-connection-provider=true");
            });
        }
    }

    @Nested
    class WhenTheConnectionProviderUsesTheManagedDataSource {

        @Test
        void applicationStarts() {
            testContext.withUserConfiguration(SingleDataSourceContext.class)
                       .run(context -> assertThat(context).hasSingleBean(TransactionManager.class));
        }
    }

    private static DataSource embeddedDatabase() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.HSQL)
                                            .generateUniqueName(true)
                                            .build();
    }

    @Configuration
    static class SingleDataSourceContext {

        @Bean
        DataSource dataSource() {
            return embeddedDatabase();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ConnectionProvider connectionProvider(DataSource dataSource) {
            return new SpringDataSourceConnectionProvider(dataSource);
        }
    }

    @Configuration
    static class SeparateDataSourcesContext {

        @Bean
        @Primary
        DataSource dataSource() {
            return embeddedDatabase();
        }

        @Bean
        DataSource tokenDataSource() {
            return embeddedDatabase();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ConnectionProvider connectionProvider() {
            return new SpringDataSourceConnectionProvider(tokenDataSource());
        }
    }
}
