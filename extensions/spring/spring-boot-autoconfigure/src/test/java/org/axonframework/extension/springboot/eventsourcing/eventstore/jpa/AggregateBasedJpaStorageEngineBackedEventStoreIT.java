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

package org.axonframework.extension.springboot.eventsourcing.eventstore.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.FactoryBasedEntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.commandhandling.AppendCriteriaInterceptor;
import org.axonframework.eventsourcing.commandhandling.AppendCriteriaResolver;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStoreTestSuite;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.jpa.JpaPollingEventCoordinator;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.extension.springboot.autoconfig.JpaTransactionAutoConfiguration;
import org.axonframework.extension.springboot.eventsourcing.eventstore.jpa.AggregateBasedJpaStorageEngineBackedEventStoreIT.TestConfig;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandHandlingComponent;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link AggregateBasedJpaEventStorageEngine}.
 *
 * @author John Hendrikx
 */
@SpringBootTest(classes = TestConfig.class)
@ImportAutoConfiguration(JpaTransactionAutoConfiguration.class)
class AggregateBasedJpaStorageEngineBackedEventStoreIT extends
        StorageEngineBackedEventStoreTestSuite<AggregateBasedJpaEventStorageEngine> {

    private static AggregateBasedJpaEventStorageEngine engine;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private SpringTransactionManager springTransactionManager;

    @NonNull
    @Override
    protected AggregateBasedJpaEventStorageEngine getStorageEngine(@NonNull EventConverter converter) {
        if(engine == null) {
            FactoryBasedEntityManagerProvider entityManagerProvider = new FactoryBasedEntityManagerProvider(entityManagerFactory);

            engine = new AggregateBasedJpaEventStorageEngine(
                new JpaTransactionalExecutorProvider(entityManagerFactory),
                converter,
                config -> config
                    .eventCoordinator(new JpaPollingEventCoordinator(entityManagerProvider, Duration.ofMillis(500)))
                    .persistenceExceptionResolver(new PersistenceExceptionResolver() {
                        @Override
                        public boolean isDuplicateKeyViolation(@NonNull Exception exception) {
                            return causeIsEntityExistsException(exception);
                        }

                        private boolean causeIsEntityExistsException(Throwable exception) {
                            return exception instanceof java.sql.SQLIntegrityConstraintViolationException
                                || (exception.getCause() != null
                                    && causeIsEntityExistsException(exception.getCause()));
                        }
                    })
            );
        }

        return engine;
    }

    @NonNull
    @Override
    protected UnitOfWork unitOfWork() {
        TransactionalUnitOfWorkFactory factory = new TransactionalUnitOfWorkFactory(
                springTransactionManager,
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );

        return factory.create();
    }

    @Override
    protected boolean supportsDynamicConsistencyBoundaries() {
        return false;
    }

    /**
     * Pins how append criteria behave on aggregate-based storage, which detects conflicts by aggregate sequence numbers
     * only and never checks the criteria of an {@link AppendCondition}.
     */
    @Nested
    class AppendCriteria {

        private static final QualifiedName REGISTER_USER = new QualifiedName("test.RegisterUser");

        @Test
        void criteriaSelectingSomethingOtherThanTheAppendedAggregateAreNotChecked() {
            // given a uniqueness reservation: a command that loads nothing and declares criteria on another tag
            String userId = UUID.randomUUID().toString();
            CommandHandlingComponent component = registration(
                    false,
                    (command, context, sourcingCriteria) -> EventCriteria.havingTags(new Tag("username", userId))
            );

            // when
            CompletableFuture<Void> result = execute(component, userId);

            // then aggregate-based storage only checks the sequence of the appended aggregate, so these criteria are
            // not checked and the event is appended; the reference guide states this limitation
            result.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(storedEventCount(userId)).isOne();
        }

        @Test
        void criteriaSelectingTheAppendedAggregateAreEnforcedThroughItsSequence() {
            // given a command that loads nothing and declares criteria selecting the aggregate it creates
            String userId = UUID.randomUUID().toString();
            CommandHandlingComponent component = registration(
                    false, (command, context, sourcingCriteria) -> userCriteria(userId)
            );
            execute(component, userId).orTimeout(5, TimeUnit.SECONDS).join();

            // when the same aggregate is created again
            CompletableFuture<Void> result = execute(component, userId);

            // then its first sequence number is taken, so the second creation is rejected
            assertThatThrownBy(() -> result.orTimeout(5, TimeUnit.SECONDS).join())
                    .hasStackTraceContaining(AppendEventsTransactionRejectedException.class.getSimpleName());
            assertThat(storedEventCount(userId)).isOne();
        }

        @Test
        void unchangedCriteriaOfASourcedCommandAreAccepted() {
            // given
            String userId = UUID.randomUUID().toString();
            CommandHandlingComponent component = registration(
                    true, (command, context, sourcingCriteria) -> sourcingCriteria
            );

            // when
            CompletableFuture<Void> result = execute(component, userId);

            // then
            result.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(storedEventCount(userId)).isOne();
        }

        @Test
        void narrowedCriteriaOfASourcedCommandAreRejected() {
            // given
            String userId = UUID.randomUUID().toString();
            CommandHandlingComponent component = registration(
                    true,
                    (command, context, sourcingCriteria) ->
                            sourcingCriteria.withEventTypes(new QualifiedName("test.OtherEvent"))
            );

            // when
            CompletableFuture<Void> result = execute(component, userId);

            // then
            assertThatThrownBy(() -> result.orTimeout(5, TimeUnit.SECONDS).join())
                    .hasStackTraceContaining("aggregate-based consistency marker");
            assertThat(storedEventCount(userId)).isZero();
        }

        @Test
        void sourcingThatIsNotReadToTheEndStillAppendsToANewAggregate() {
            // given a transaction that starts sourcing but never reads the stream, so its marker stays at ORIGIN
            String userId = UUID.randomUUID().toString();

            // when
            CompletableFuture<Void> result = unitOfWork().executeWithResult(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                transaction.source(SourcingCondition.conditionFor(userCriteria(userId)));
                transaction.appendEvent(registered(userId));
                return CompletableFuture.completedFuture(null);
            });

            // then the append goes through, as on main: the criteria select the aggregate being appended to
            result.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(storedEventCount(userId)).isOne();
        }

        @Test
        void overriddenAppendConditionSelectingTheAppendedAggregateKeepsAppending() {
            // given the released EventStoreTransaction#overrideAppendCondition, used the way aggregate storage supports
            String userId = UUID.randomUUID().toString();

            // when
            CompletableFuture<Void> result = appendWithOverriddenCriteria(userId, userCriteria(userId));

            // then
            result.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(storedEventCount(userId)).isOne();
        }

        @Test
        void overriddenAppendConditionWithCriteriaOnAnotherTagIsNotChecked() {
            // given the released overrideAppendCondition with criteria on a tag other than the appended aggregate's
            String userId = UUID.randomUUID().toString();

            // when
            CompletableFuture<Void> result =
                    appendWithOverriddenCriteria(userId, EventCriteria.havingTags(new Tag("username", userId)));

            // then, as for append criteria, the criteria are not checked and the event is appended
            result.orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(storedEventCount(userId)).isOne();
        }

        private CompletableFuture<Void> appendWithOverriddenCriteria(String userId, EventCriteria criteria) {
            return unitOfWork().executeWithResult(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                transaction.overrideAppendCondition(condition -> AppendCondition.withCriteria(criteria));
                transaction.appendEvent(registered(userId));
                return CompletableFuture.completedFuture(null);
            });
        }

        private CommandHandlingComponent registration(boolean sourceFirst, AppendCriteriaResolver resolver) {
            SimpleCommandHandlingComponent handler = SimpleCommandHandlingComponent.create("registration");
            handler.subscribe(REGISTER_USER, (command, context) -> {
                String userId = (String) command.payload();
                EventStoreTransaction transaction = eventStore.transaction(context);
                if (sourceFirst) {
                    FluxUtils.of(transaction.source(SourcingCondition.conditionFor(userCriteria(userId)))).blockLast();
                }
                transaction.appendEvent(registered(userId));
                return MessageStream.empty();
            });
            return new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(eventStore, resolver)), handler
            );
        }

        private CompletableFuture<Void> execute(CommandHandlingComponent component, String userId) {
            return unitOfWork().executeWithResult(context -> {
                component.handle(new GenericCommandMessage(new MessageType(REGISTER_USER), userId), context);
                return CompletableFuture.completedFuture(null);
            });
        }

        private long storedEventCount(String userId) {
            return unitOfWork().executeWithResult(context -> FluxUtils
                                       .of(eventStore.transaction(context).source(
                                               SourcingCondition.conditionFor(userCriteria(userId))
                                       ))
                                       .count()
                                       .toFuture())
                               .orTimeout(5, TimeUnit.SECONDS)
                               .join();
        }

        private static EventCriteria userCriteria(String userId) {
            return EventCriteria.havingTags(new Tag("User", userId));
        }

        // the suite's message(...) keeps event timestamps in the range the shared store's other tests expect
        private EventMessage registered(String userId) {
            return message(new UserRegistered(userId));
        }
    }

    private record UserRegistered(@EventTag(key = "User") String userId) {
    }

    @Configuration
    public static class TestConfig {

        @Configuration
        public static class PersistenceConfig {

            @PersistenceContext
            private EntityManager entityManager;

            @Bean
            public EntityManagerProvider entityManagerProvider() {
                return new SimpleEntityManagerProvider(entityManager);
            }
        }

        @Bean
        public DataSource dataSource() {
            String uniqueDbName = "jdbc:hsqldb:mem:aggregatebasedjpaeventstorageenginetest-" + System.nanoTime();
            DriverManagerDataSource driverManagerDataSource =
                    new DriverManagerDataSource(uniqueDbName, "sa", "password");
            driverManagerDataSource.setDriverClassName("org.hsqldb.jdbcDriver");
            return driverManagerDataSource;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean entityManagerFactoryBean =
                    new LocalContainerEntityManagerFactoryBean();
            entityManagerFactoryBean.setPersistenceUnitName("integrationtest");

            HibernateJpaVendorAdapter jpaVendorAdapter = new HibernateJpaVendorAdapter();
            jpaVendorAdapter.setGenerateDdl(true);
            jpaVendorAdapter.setShowSql(false);

            entityManagerFactoryBean.setJpaVendorAdapter(jpaVendorAdapter);
            entityManagerFactoryBean.setDataSource(dataSource);

            return entityManagerFactoryBean;
        }

        @Bean
        public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
                                                        DataSource dataSource) {
            JpaTransactionManager jpaTransactionManager = new JpaTransactionManager(entityManagerFactory);
            jpaTransactionManager.setDataSource(dataSource);
            return jpaTransactionManager;
        }

        @Bean
        public static PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
            return new PersistenceAnnotationBeanPostProcessor();
        }
    }
}
