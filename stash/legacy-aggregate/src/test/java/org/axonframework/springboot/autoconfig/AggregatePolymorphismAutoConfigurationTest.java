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

package org.axonframework.springboot.autoconfig;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.springboot.autoconfig.context.Animal;
import org.axonframework.springboot.autoconfig.context.Cat;
import org.axonframework.springboot.autoconfig.context.CreateCatCommand;
import org.axonframework.springboot.autoconfig.context.CreateDogCommand;
import org.axonframework.springboot.autoconfig.context.Dog;
import org.axonframework.springboot.autoconfig.context.RenameAnimalCommand;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.axonframework.common.StringUtils.lowerCaseFirstCharacterOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating Aggregate Polymorphism works as intended when using Spring Boot autoconfiguration.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #3499")
class AggregatePolymorphismAutoConfigurationTest {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner().withUserConfiguration(DefaultContext.class);
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void polymorphicAggregateWiringHandlesCommandsAndIsEventSourcedAsExpected() {
        String catId = UUID.randomUUID().toString();
        String dogId = UUID.randomUUID().toString();

        testApplicationContext.withUserConfiguration(PolymorphicAggregateContext.class)
                              .run(context -> {
                                  CommandGateway commandGateway =
                                          context.getBean("commandGateway", CommandGateway.class);

                                  commandGateway.sendAndWait(new CreateCatCommand(catId, "Felix"));
                                  commandGateway.sendAndWait(new RenameAnimalCommand(catId, "Wokkel"));
                                  commandGateway.sendAndWait(new CreateDogCommand(dogId, "Milou"));
                                  commandGateway.sendAndWait(new RenameAnimalCommand(dogId, "Medor"));
                              });
    }

    @Test
    void polymorphicAggregateWiringConstructsSingleAggregateFactory() {
        String catFactoryBeanName = aggregateFactoryBeanNameFor(Cat.class);
        String dogFactoryBeanName = aggregateFactoryBeanNameFor(Dog.class);
        String animalFactoryBeanName = aggregateFactoryBeanNameFor(Animal.class);

//        testApplicationContext.withUserConfiguration(PolymorphicAggregateContext.class)
//                              .run(context -> {
//                                  // Only a single Aggregate Factory should exist, for both Cats and Dogs.
//                                  Assertions.assertThat(context).hasSingleBean(SpringPrototypeAggregateFactory.class);
//                                  Assertions.assertThat(context)
//                                            .getBean(catFactoryBeanName, SpringPrototypeAggregateFactory.class)
//                                            .isNull();
//                                  Assertions.assertThat(context)
//                                            .getBean(dogFactoryBeanName, SpringPrototypeAggregateFactory.class)
//                                            .isNull();
//                                  Assertions.assertThat(context)
//                                            .getBean(animalFactoryBeanName, SpringPrototypeAggregateFactory.class)
//                                            .isNotNull();
//                              });
    }

    private static String aggregateFactoryBeanNameFor(Class<?> aggregateClass) {
        return lowerCaseFirstCharacterOf(aggregateClass.getSimpleName()) + "AggregateFactory";
    }

    @Test
    void polymorphicAggregateWiringConstructsSingleRepository() {
        String animalRepositoryBeanName = repositoryBeanName(Animal.class);

//        testApplicationContext.withUserConfiguration(PolymorphicAggregateContext.class)
//                              .run(context -> {
//                                  Assertions.assertThat(context).hasSingleBean(LegacyRepository.class);
//                                  Assertions.assertThat(context).getBean(LegacyRepository.class)
//                                            .isInstanceOf(LegacyEventSourcingRepository.class);
//                                  String[] namesForRepositoryBeans =
//                                          context.getBeanNamesForType(LegacyRepository.class);
//                                  assertThat(namesForRepositoryBeans.length).isEqualTo(1);
//
//                                  assertThat(namesForRepositoryBeans[0]).isEqualTo(animalRepositoryBeanName);
//                              });
    }

    private static String repositoryBeanName(@SuppressWarnings("SameParameterValue") Class<?> aggregateClass) {
        return lowerCaseFirstCharacterOf(aggregateClass.getSimpleName()) + "Repository";
    }

    /**
     * Although snapshot creation on aggregate creation typically isn't realistic, the snapshot creation path is
     * different enough to merit a test. However, Axon Framework will disregard snapshots at event position zero as an
     * optimization. Hence, we validate the first event <b>not</b> to be a snapshot.
     */
    @Test
    @Disabled("TODO #3195 - Migration Module")
    void snapshottingOnAggregateCreationAreCreatedAndUsableForAnyPolymorphicAggregateChildType() {
        String catId = "catId";
        String dogId = "dogId";

        testApplicationContext.withUserConfiguration(PolymorphicAggregateContext.class)
                              .withPropertyValues("snapshot-count=1")
                              .run(context -> {
                                  CommandGateway commandGateway =
                                          context.getBean("commandGateway", CommandGateway.class);

                                  commandGateway.sendAndWait(new CreateCatCommand(catId, "Felix"));
                                  commandGateway.sendAndWait(new CreateDogCommand(dogId, "Milou"));

//                                  LegacyEventStore eventStore = context.getBean(LegacyEventStore.class);
//                                  DomainEventStream catStream = eventStore.readEvents(catId);
//                                  assertThat(catStream.hasNext()).isTrue();
//                                  DomainEventMessage firstCatEvent = catStream.next();
//                                  assertThat(catStream.hasNext()).isFalse();
//                                  // Validate whether the payload equals the Cat, as aggregate == snapshot.
//                                  assertThat(firstCatEvent.payloadType()).isEqualTo(CatCreatedEvent.class);
//                                  assertThat(firstCatEvent.getType()).isEqualTo(Cat.class.getSimpleName());
//
//                                  DomainEventStream dogStream = eventStore.readEvents(dogId);
//                                  assertThat(dogStream.hasNext()).isTrue();
//                                  DomainEventMessage firstDogEvent = dogStream.next();
//                                  assertThat(dogStream.hasNext()).isFalse();
//                                  // Validate whether the payload equals the Dog, as aggregate == snapshot.
//                                  assertThat(firstDogEvent.payloadType()).isEqualTo(DogCreatedEvent.class);
//                                  assertThat(firstDogEvent.getType()).isEqualTo(Dog.class.getSimpleName());

                                  assertDoesNotThrow(
                                          () -> commandGateway.sendAndWait(new RenameAnimalCommand(catId, "Wokkel"))
                                  );
                                  assertDoesNotThrow(
                                          () -> commandGateway.sendAndWait(new RenameAnimalCommand(dogId, "Medor"))
                                  );
                              });
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void snapshotsAreCreatedAndUsableForAnyPolymorphicAggregateChildType() {
        String catId = "catId";
        String dogId = "dogId";
        String expectedAggregateType = Animal.class.getSimpleName();

        testApplicationContext.withUserConfiguration(PolymorphicAggregateContext.class)
                              .run(context -> {
                                  CommandGateway commandGateway =
                                          context.getBean("commandGateway", CommandGateway.class);

                                  // Create Cat aggregate instance up to snapshot
                                  commandGateway.sendAndWait(new CreateCatCommand(catId, "Felix"));
                                  commandGateway.sendAndWait(new RenameAnimalCommand(catId, "Wokkel"));
                                  commandGateway.sendAndWait(new RenameAnimalCommand(catId, "Keetje"));
                                  // Create Dog aggregate instance up to snapshot
                                  commandGateway.sendAndWait(new CreateDogCommand(dogId, "Milou"));
                                  commandGateway.sendAndWait(new RenameAnimalCommand(dogId, "Medor"));
                                  commandGateway.sendAndWait(new RenameAnimalCommand(dogId, "Brutus"));

//                                  LegacyEventStore eventStore = context.getBean(LegacyEventStore.class);
//                                  DomainEventStream catStream = eventStore.readEvents(catId);
//                                  assertThat(catStream.hasNext()).isTrue();
//                                  DomainEventMessage firstCatEvent = catStream.next();
//                                  assertThat(catStream.hasNext()).isFalse();
//                                  // Validate whether the payload equals the Cat, as aggregate == snapshot.
//                                  assertThat(firstCatEvent.payloadType()).isEqualTo(Cat.class);
//                                  assertThat(firstCatEvent.getType()).isEqualTo(expectedAggregateType);
//
//                                  DomainEventStream dogStream = eventStore.readEvents(dogId);
//                                  assertThat(dogStream.hasNext()).isTrue();
//                                  DomainEventMessage firstDogEvent = dogStream.next();
//                                  assertThat(dogStream.hasNext()).isFalse();
//                                  // Validate whether the payload equals the Dog, as aggregate == snapshot.
//                                  assertThat(firstDogEvent.payloadType()).isEqualTo(Dog.class);
//                                  assertThat(firstDogEvent.getType()).isEqualTo(expectedAggregateType);

                                  assertDoesNotThrow(
                                          () -> commandGateway.sendAndWait(new RenameAnimalCommand(catId, "Wokkel"))
                                  );
                                  assertDoesNotThrow(
                                          () -> commandGateway.sendAndWait(new RenameAnimalCommand(dogId, "Medor"))
                                  );
                              });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class DefaultContext {

        @Bean
        public EventStorageEngine eventStorageEngine() {
            return new InMemoryEventStorageEngine();
        }
    }

    @Configuration
    @ComponentScan(basePackages = {"org.axonframework.springboot.autoconfig.context"})
    static class PolymorphicAggregateContext {

//        @Bean
//        public SnapshotTriggerDefinition animalSnapshotTriggerDefinition(
//                @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") Snapshotter snapshotter,
//                @Value("${snapshot-count:3}") int snapshotCount
//        ) {
//            return new EventCountSnapshotTriggerDefinition(snapshotter, snapshotCount);
//        }
    }
}
