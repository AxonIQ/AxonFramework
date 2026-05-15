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

package org.axonframework.integrationtests.testsuite.administration;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.administration.commands.AssignTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.ChangeEmailAddress;
import org.axonframework.integrationtests.testsuite.administration.commands.CompleteTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateCustomer;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateEmployee;
import org.axonframework.integrationtests.testsuite.administration.commands.GiveRaise;
import org.axonframework.integrationtests.testsuite.administration.common.PersonIdentifier;
import org.axonframework.integrationtests.testsuite.administration.events.CustomerCreated;
import org.axonframework.integrationtests.testsuite.administration.events.EmployeeCreated;
import org.axonframework.integrationtests.testsuite.administration.events.TaskCompleted;
import org.axonframework.integrationtests.testsuite.administration.state.immutable.ImmutableCustomer;
import org.axonframework.integrationtests.testsuite.administration.state.immutable.ImmutableEmployee;
import org.axonframework.integrationtests.testsuite.administration.state.immutable.ImmutablePerson;
import org.axonframework.integrationtests.testsuite.administration.state.immutable.ImmutableSalaryInformation;
import org.axonframework.integrationtests.testsuite.administration.state.immutable.ImmutableTask;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.axonframework.modelling.entity.ConcreteEntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;
import org.axonframework.modelling.entity.child.ChildEntityFieldDefinition;
import org.axonframework.modelling.entity.child.EntityChildMetamodel;

import java.util.Objects;

import static java.lang.String.format;

/**
 * Runs the administration test suite using the builders of {@link EntityMetamodel} and related classes.
 */
public abstract class ImmutableBuilderEntityModelAdministrationIT extends AbstractAdministrationIT {

    EntityMetamodel<ImmutablePerson> buildEntityMetamodel(Configuration configuration,
                                                          EntityMetamodelBuilder<ImmutablePerson> builder) {
        MessageTypeResolver typeResolver = configuration.getComponent(MessageTypeResolver.class);
        EventConverter eventConverter = configuration.getComponent(EventConverter.class);

        // Task is the list-based child-metamodel of Employee
        EntityMetamodel<ImmutableTask> taskMetamodel = ConcreteEntityMetamodel
                .forEntityClass(ImmutableTask.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        ImmutableTask.class, eventConverter, typeResolver
                ))
                .instanceCommandHandler(typeResolver.resolveOrThrow(CompleteTaskCommand.class).qualifiedName(),
                                        (command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            CompleteTaskCommand convertedPayload = command.payloadAs(CompleteTaskCommand.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        })
                .build();

        // SalaryInformation is the singular child-metamodel of Employee
        EntityMetamodel<ImmutableSalaryInformation> salaryInformationMetamodel = ConcreteEntityMetamodel
                .forEntityClass(ImmutableSalaryInformation.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        ImmutableSalaryInformation.class, eventConverter, typeResolver
                ))
                .instanceCommandHandler(typeResolver.resolveOrThrow(GiveRaise.class).qualifiedName(),
                                        (command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            GiveRaise convertedPayload = command.payloadAs(GiveRaise.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        })
                .build();

        // Employee is a concrete entity type
        EntityMetamodel<ImmutableEmployee> employeeMetamodel = ConcreteEntityMetamodel
                .forEntityClass(ImmutableEmployee.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        ImmutableEmployee.class, eventConverter, typeResolver
                ))
                .creationalCommandHandler(typeResolver.resolveOrThrow(CreateEmployee.class).qualifiedName(),
                                          ((command, context) -> {
                                              EventAppender eventAppender = EventAppender.forContext(context);
                                              CreateEmployee convertedPayload = command.payloadAs(CreateEmployee.class);
                                              ImmutableEmployee.handle(convertedPayload, eventAppender);
                                              return MessageStream.empty().cast();
                                          }))
                .instanceCommandHandler(typeResolver.resolveOrThrow(AssignTaskCommand.class).qualifiedName(),
                                        ((command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            AssignTaskCommand convertedPayload = command.payloadAs(AssignTaskCommand.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        }))
                .addChild(EntityChildMetamodel
                                  .list(ImmutableEmployee.class, taskMetamodel)
                                  .childEntityFieldDefinition(ChildEntityFieldDefinition.forGetterEvolver(
                                          ImmutableEmployee::getTaskList, ImmutableEmployee::evolveTaskList
                                  ))
                                  .commandTargetResolver((candidates, commandMessage, ctx) -> {
                                      if (commandMessage.type().name().equals(CompleteTaskCommand.class.getName())) {
                                          CompleteTaskCommand convertedPayload =
                                                  commandMessage.payloadAs(CompleteTaskCommand.class);
                                          return candidates.stream()
                                                           .filter(task -> task.getTaskId()
                                                                               .equals(convertedPayload.taskId()))
                                                           .findFirst()
                                                           .orElse(null);
                                      }
                                      return null;
                                  })
                                  .eventTargetMatcher((o, eventMessage, ctx) -> {
                                      if (eventMessage.type().name().equals(TaskCompleted.class.getName())) {
                                          TaskCompleted taskCompleted = eventConverter.convertPayload(
                                                  eventMessage, TaskCompleted.class
                                          );
                                          Objects.requireNonNull(
                                                  taskCompleted,
                                                  "TaskCompleted event payload cannot be null"
                                          );
                                          return o.getTaskId().equals(taskCompleted.taskId());
                                      }
                                      return false;
                                  })
                                  .build()

                )
                .addChild(EntityChildMetamodel
                                  .single(ImmutableEmployee.class, salaryInformationMetamodel)
                                  .childEntityFieldDefinition(ChildEntityFieldDefinition.forGetterEvolver(
                                          ImmutableEmployee::salaryInformation,
                                          ImmutableEmployee::evolveSalaryInformation
                                  ))
                                  .build()
                )
                .build();

        // Customer is a concrete entity type
        EntityMetamodel<ImmutableCustomer> customerMetamodel = ConcreteEntityMetamodel
                .forEntityClass(ImmutableCustomer.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        ImmutableCustomer.class, eventConverter, typeResolver
                ))
                .creationalCommandHandler(typeResolver.resolveOrThrow(CreateCustomer.class).qualifiedName(),
                                          ((command, context) -> {
                                              EventAppender eventAppender = EventAppender.forContext(context);
                                              CreateCustomer convertedPayload = command.payloadAs(CreateCustomer.class);
                                              ImmutableCustomer.handle(convertedPayload, eventAppender);
                                              return MessageStream.empty().cast();
                                          }))
                .build();

        return EntityMetamodel
                .forPolymorphicEntityType(ImmutablePerson.class)
                .addConcreteType(employeeMetamodel)
                .addConcreteType(customerMetamodel)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        ImmutablePerson.class, eventConverter, typeResolver
                ))
                .instanceCommandHandler(typeResolver.resolveOrThrow(ChangeEmailAddress.class).qualifiedName(),
                                        (command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            ChangeEmailAddress convertedPayload = command.payloadAs(ChangeEmailAddress.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        })
                .build();
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        EventSourcedEntityModule<PersonIdentifier, ImmutablePerson> personEntityModule = EventSourcedEntityModule
                .declarative(PersonIdentifier.class, ImmutablePerson.class)
                .messagingModel(this::buildEntityMetamodel)
                .entityFactory(c -> EventSourcedEntityFactory.fromEventMessage((identifier, eventMessage) -> {
                    EventConverter converter = c.getComponent(EventConverter.class);
                    if (eventMessage.type().name().equals(EmployeeCreated.class.getName())) {
                        var employeeCreated = converter.convertPayload(eventMessage, EmployeeCreated.class);
                        Objects.requireNonNull(employeeCreated, "EmployeeCreated event payload cannot be null");
                        return new ImmutableEmployee(employeeCreated);
                    }
                    if (eventMessage.type().name().equals(CustomerCreated.class.getName())) {
                        var customerCreated = converter.convertPayload(eventMessage, CustomerCreated.class);
                        Objects.requireNonNull(customerCreated, "CustomerCreated event payload cannot be null");
                        return new ImmutableCustomer(customerCreated);
                    }
                    throw new IllegalArgumentException(
                            format("Unknown event type: %s", eventMessage.type()));
                }))
                .criteriaResolver(c -> (s, ctx) -> EventCriteria.havingTags("Person", s.key()))
                .entityIdResolver(config -> new PersonIdentifierEntityIdResolver())
                .build();
        return configurer.componentRegistry(cr -> cr.registerModule(personEntityModule));
    }
}
