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

import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.StringUtils.lowerCaseFirstCharacterOf;

/**
 * Test class validating {@link Repository} beans are wired as intended to an external command handler.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #4185")
class RepositoryWiringTest {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner().withUserConfiguration(DefaultContext.class);
    }

    @Test
    void repositoryIsWiredToExternalCommandHandler() {
        testApplicationContext.withUserConfiguration(SingleEventSourcedEntityContext.class).run(context -> {
            assertThat(context).hasSingleBean(SingleEventSourcedEntityContext.ExternalCommandHandlerForEntityOne.class);
            SingleEventSourcedEntityContext.ExternalCommandHandlerForEntityOne externalHandler =
                    context.getBean(SingleEventSourcedEntityContext.ExternalCommandHandlerForEntityOne.class);

            Repository<String, SingleEventSourcedEntityContext.EntityOne> repositoryFromHandler = externalHandler.getRepository();
            assertThat(repositoryFromHandler).isNotNull();
            assertThat(repositoryFromHandler).isInstanceOf(EventSourcingRepository.class);

            assertThat(context).hasBean(repositoryBeanName(SingleEventSourcedEntityContext.EntityOne.class));
            Object repositoryFromContext =
                    context.getBean(repositoryBeanName(SingleEventSourcedEntityContext.EntityOne.class));
            assertThat(repositoryFromHandler).isEqualTo(repositoryFromContext);
        });
    }

    @Test
    void repositoriesAreWiredToExternalCommandHandlerBasedOnGenerics() {
        testApplicationContext.withUserConfiguration(SeveralEventSourcedEntitiesContext.class).run(context -> {
            assertThat(context)
                    .hasSingleBean(SeveralEventSourcedEntitiesContext.ExternalCommandHandlerWiringThroughGenerics.class);
            SeveralEventSourcedEntitiesContext.ExternalCommandHandlerWiringThroughGenerics externalHandler =
                    context.getBean(SeveralEventSourcedEntitiesContext.ExternalCommandHandlerWiringThroughGenerics.class);

            Repository<String, SeveralEventSourcedEntitiesContext.EntityOne> repositoryOneFromHandler =
                    externalHandler.getRepositoryOne();
            assertThat(repositoryOneFromHandler).isNotNull();
            assertThat(repositoryOneFromHandler).isInstanceOf(EventSourcingRepository.class);
            String repoEntityOneQualifier = repositoryBeanName(SeveralEventSourcedEntitiesContext.EntityOne.class);
            assertThat(context).hasBean(repoEntityOneQualifier);
            Object repositoryOneFromContext = context.getBean(repoEntityOneQualifier);
            assertThat(repositoryOneFromHandler).isEqualTo(repositoryOneFromContext);

            Repository<String, SeveralEventSourcedEntitiesContext.EntityTwo> repositoryTwoFromHandler =
                    externalHandler.getRepositoryTwo();
            assertThat(repositoryTwoFromHandler).isNotNull();
            assertThat(repositoryTwoFromHandler).isInstanceOf(EventSourcingRepository.class);
            String repoEntityTwoQualifier = repositoryBeanName(SeveralEventSourcedEntitiesContext.EntityTwo.class);
            assertThat(context).hasBean(repoEntityTwoQualifier);
            Object repositoryTwoFromContext = context.getBean(repoEntityTwoQualifier);
            assertThat(repositoryTwoFromHandler).isEqualTo(repositoryTwoFromContext);

            Repository<String, SeveralEventSourcedEntitiesContext.EntityThree> repositoryThreeFromHandler =
                    externalHandler.getRepositoryThree();
            assertThat(repositoryThreeFromHandler).isNotNull();
            assertThat(repositoryThreeFromHandler).isInstanceOf(EventSourcingRepository.class);
            String repoEntityThreeQualifier = repositoryBeanName(SeveralEventSourcedEntitiesContext.EntityThree.class);
            assertThat(context).hasBean(repoEntityThreeQualifier);
            Object repositoryThreeFromContext = context.getBean(repoEntityThreeQualifier);
            assertThat(repositoryThreeFromHandler).isEqualTo(repositoryThreeFromContext);
        });
    }

    @Test
    void repositoriesAreWiredToExternalCommandHandlerBasedOnBeanName() {
        testApplicationContext.withUserConfiguration(SeveralEventSourcedEntitiesContext.class).run(context -> {
            assertThat(context)
                    .hasSingleBean(SeveralEventSourcedEntitiesContext.ExternalCommandHandlerWiringThroughBeanNames.class);
            SeveralEventSourcedEntitiesContext.ExternalCommandHandlerWiringThroughBeanNames externalHandler =
                    context.getBean(SeveralEventSourcedEntitiesContext.ExternalCommandHandlerWiringThroughBeanNames.class);

            Repository<?, ?> repositoryOneFromHandler = externalHandler.getRepositoryOne();
            assertThat(repositoryOneFromHandler).isNotNull();
            assertThat(repositoryOneFromHandler).isInstanceOf(EventSourcingRepository.class);
            String repoEntityOneQualifier = repositoryBeanName(SeveralEventSourcedEntitiesContext.EntityOne.class);
            Object repositoryOneFromContext = context.getBean(repoEntityOneQualifier);
            assertThat(context).hasBean(repoEntityOneQualifier);
            assertThat(repositoryOneFromHandler).isEqualTo(repositoryOneFromContext);

            Repository<?, ?> repositoryTwoFromHandler = externalHandler.getRepositoryTwo();
            assertThat(repositoryTwoFromHandler).isNotNull();
            assertThat(repositoryTwoFromHandler).isInstanceOf(EventSourcingRepository.class);
            String repoEntityTwoQualifier = repositoryBeanName(SeveralEventSourcedEntitiesContext.EntityTwo.class);
            Object repositoryTwoFromContext = context.getBean(repoEntityTwoQualifier);
            assertThat(context).hasBean(repoEntityTwoQualifier);
            assertThat(repositoryTwoFromHandler).isEqualTo(repositoryTwoFromContext);

            Repository<?, ?> repositoryThreeFromHandler = externalHandler.getRepositoryThree();
            assertThat(repositoryThreeFromHandler).isNotNull();
            assertThat(repositoryThreeFromHandler).isInstanceOf(EventSourcingRepository.class);
            String repoEntityThreeQualifier = repositoryBeanName(SeveralEventSourcedEntitiesContext.EntityThree.class);
            Object repositoryThreeFromContext = context.getBean(repoEntityThreeQualifier);
            assertThat(context).hasBean(repoEntityThreeQualifier);
            assertThat(repositoryThreeFromHandler).isEqualTo(repositoryThreeFromContext);
        });
    }

    private static String repositoryBeanName(Class<?> aggregateClass) {
        return lowerCaseFirstCharacterOf(aggregateClass.getSimpleName()) + "Repository";
    }

    @Configuration
    @EnableAutoConfiguration
    static class DefaultContext {

        @Bean
        public EventStorageEngine eventStorageEngine() {
            return new InMemoryEventStorageEngine();
        }
    }

    @Configuration
    static class SingleEventSourcedEntityContext {

        @EventSourced
        static class EntityOne {

            public EntityOne() {
            }
        }

        @Component
        static class ExternalCommandHandlerForEntityOne {

            private final Repository<String, EntityOne> repository;

            ExternalCommandHandlerForEntityOne(Repository<String, EntityOne> repository) {
                this.repository = repository;
            }

            public Repository<String, EntityOne> getRepository() {
                return repository;
            }
        }
    }

    @Configuration
    static class SeveralEventSourcedEntitiesContext {

        @EventSourced
        static class EntityOne {

            public EntityOne() {
            }
        }

        @EventSourced
        static class EntityTwo {

            public EntityTwo() {
            }
        }

        @EventSourced
        static class EntityThree {

            public EntityThree() {
            }
        }

        @Component
        static class ExternalCommandHandlerWiringThroughGenerics {

            private final Repository<String, EntityOne> repositoryOne;
            private final Repository<String, EntityTwo> repositoryTwo;
            private final Repository<String, EntityThree> repositoryThree;

            ExternalCommandHandlerWiringThroughGenerics(
                    Repository<String, EntityOne> repositoryOne,
                    Repository<String, EntityTwo> repositoryTwo,
                    Repository<String, EntityThree> repositoryThree
            ) {
                this.repositoryOne = repositoryOne;
                this.repositoryTwo = repositoryTwo;
                this.repositoryThree = repositoryThree;
            }

            public Repository<String, EntityOne> getRepositoryOne() {
                return repositoryOne;
            }

            public Repository<String, EntityTwo> getRepositoryTwo() {
                return repositoryTwo;
            }

            public Repository<String, EntityThree> getRepositoryThree() {
                return repositoryThree;
            }
        }

        @Component
        static class ExternalCommandHandlerWiringThroughBeanNames {

            private final Repository<?, ?> repositoryOne;
            private final Repository<?, ?> repositoryTwo;
            private final Repository<?, ?> repositoryThree;

            ExternalCommandHandlerWiringThroughBeanNames(
                    @Qualifier("entityOneRepository") Repository<?, ?> repositoryOne,
                    @Qualifier("entityTwoRepository") Repository<?, ?> repositoryTwo,
                    @Qualifier("entityThreeRepository") Repository<?, ?> repositoryThree
            ) {
                this.repositoryOne = repositoryOne;
                this.repositoryTwo = repositoryTwo;
                this.repositoryThree = repositoryThree;
            }

            public Repository<?, ?> getRepositoryOne() {
                return repositoryOne;
            }

            public Repository<?, ?> getRepositoryTwo() {
                return repositoryTwo;
            }

            public Repository<?, ?> getRepositoryThree() {
                return repositoryThree;
            }
        }
    }
}
