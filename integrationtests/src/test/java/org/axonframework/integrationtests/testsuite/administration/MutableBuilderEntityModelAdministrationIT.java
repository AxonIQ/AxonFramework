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
import org.axonframework.integrationtests.testsuite.administration.common.PersonType;
import org.axonframework.integrationtests.testsuite.administration.events.TaskCompleted;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutableCustomer;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutableEmployee;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutablePerson;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutableSalaryInformation;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutableTask;
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

/**
 * Runs the administration test suite using the builders of {@link EntityMetamodel} and related classes.
 */
public abstract class MutableBuilderEntityModelAdministrationIT extends AbstractAdministrationIT {

    EntityMetamodel<MutablePerson> buildEntityMetamodel(Configuration configuration,
                                                        EntityMetamodelBuilder<MutablePerson> builder) {
        MessageTypeResolver typeResolver = configuration.getComponent(MessageTypeResolver.class);
        EventConverter eventConverter = configuration.getComponent(EventConverter.class);

        // Task is the list-based child-model of Employee
        EntityMetamodel<MutableTask> taskMetamodel = ConcreteEntityMetamodel
                .forEntityClass(MutableTask.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        MutableTask.class, eventConverter, typeResolver
                ))
                .instanceCommandHandler(typeResolver.resolveOrThrow(CompleteTaskCommand.class).qualifiedName(),
                                        (command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            CompleteTaskCommand convertedPayload =
                                                    command.payloadAs(CompleteTaskCommand.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        })
                .build();

        // SalaryInformation is the singular child-model of Employee
        EntityMetamodel<MutableSalaryInformation> salaryInformationMetamodel = ConcreteEntityMetamodel
                .forEntityClass(MutableSalaryInformation.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        MutableSalaryInformation.class, eventConverter, typeResolver
                ))
                .instanceCommandHandler(typeResolver.resolveOrThrow(GiveRaise.class).qualifiedName(),
                                        (command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            GiveRaise convertedPayload =
                                                    command.payloadAs(GiveRaise.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        })
                .build();

        // Employee is a concrete entity type
        EntityMetamodel<MutableEmployee> employeeMetamodel = ConcreteEntityMetamodel
                .forEntityClass(MutableEmployee.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        MutableEmployee.class, eventConverter, typeResolver
                ))
                .creationalCommandHandler(typeResolver.resolveOrThrow(CreateEmployee.class).qualifiedName(),
                                          ((command, context) -> {
                                              MutableEmployee.handle(
                                                      command.payloadAs(CreateEmployee.class),
                                                      EventAppender.forContext(context)
                                              );
                                              return MessageStream.empty().cast();
                                          }))
                .instanceCommandHandler(typeResolver.resolveOrThrow(AssignTaskCommand.class).qualifiedName(),
                                        ((command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            AssignTaskCommand convertedPayload =
                                                    command.payloadAs(AssignTaskCommand.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        }))
                .addChild(EntityChildMetamodel
                                  .list(MutableEmployee.class, taskMetamodel)
                                  .childEntityFieldDefinition(ChildEntityFieldDefinition.forGetterSetter(
                                          MutableEmployee::getTaskList, MutableEmployee::setTaskList
                                  ))
                                  .commandTargetResolver((candidates, commandMessage, ctx) -> {
                                      if (commandMessage.type().name().equals(CompleteTaskCommand.class.getName())) {
                                          CompleteTaskCommand assignTaskCommand = commandMessage.payloadAs(
                                                  CompleteTaskCommand.class);
                                          Objects.requireNonNull(assignTaskCommand,
                                                                 "AssignTaskCommand payload cannot be null");
                                          return candidates.stream()
                                                           .filter(task -> task.getTaskId()
                                                                               .equals(assignTaskCommand.taskId()))
                                                           .findFirst()
                                                           .orElse(null);
                                      }
                                      return null;
                                  })
                                  .eventTargetMatcher((o, eventMessage, ctx) -> {
                                      if (eventMessage.type().name().equals(TaskCompleted.class.getName())) {
                                          TaskCompleted taskCompleted =
                                                  eventConverter.convertPayload(eventMessage, TaskCompleted.class);
                                          Objects.requireNonNull(taskCompleted,
                                                                 "TaskCompleted event payload cannot be null");
                                          return o.getTaskId().equals(taskCompleted.taskId());
                                      }
                                      return false;
                                  })
                                  .build()

                )
                .addChild(EntityChildMetamodel
                                  .single(MutableEmployee.class, salaryInformationMetamodel)
                                  .childEntityFieldDefinition(ChildEntityFieldDefinition.forFieldName(
                                          MutableEmployee.class, "salary"
                                  ))
                                  .build()
                )
                .build();

        // Customer is a concrete entity type
        EntityMetamodel<MutableCustomer> customerMetamodel = ConcreteEntityMetamodel
                .forEntityClass(MutableCustomer.class)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        MutableCustomer.class, eventConverter, typeResolver
                ))
                .creationalCommandHandler(
                        typeResolver.resolveOrThrow(CreateCustomer.class).qualifiedName(),
                        ((command, context) -> {
                            MutableCustomer.handle(
                                    command.payloadAs(CreateCustomer.class),
                                    EventAppender.forContext(context)
                            );
                            return MessageStream.empty().cast();
                        })
                )
                .build();

        // Person is the polymorphic entity type
        return EntityMetamodel
                .forPolymorphicEntityType(MutablePerson.class)
                .addConcreteType(employeeMetamodel)
                .addConcreteType(customerMetamodel)
                .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(
                        MutablePerson.class, eventConverter, typeResolver
                ))
                .instanceCommandHandler(typeResolver.resolveOrThrow(ChangeEmailAddress.class).qualifiedName(),
                                        (command, entity, context) -> {
                                            EventAppender eventAppender = EventAppender.forContext(context);
                                            ChangeEmailAddress convertedPayload =
                                                    command.payloadAs(ChangeEmailAddress.class);
                                            entity.handle(convertedPayload, eventAppender);
                                            return MessageStream.empty().cast();
                                        })
                .build();
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        EventSourcedEntityModule<PersonIdentifier, MutablePerson> personEntityModule = EventSourcedEntityModule
                .declarative(PersonIdentifier.class, MutablePerson.class)
                .messagingModel(this::buildEntityMetamodel)
                .entityFactory(c -> EventSourcedEntityFactory.fromIdentifier(id -> {
                    if (id.type() == PersonType.EMPLOYEE) {
                        return new MutableEmployee();
                    } else if (id.type() == PersonType.CUSTOMER) {
                        return new MutableCustomer();
                    }
                    throw new IllegalArgumentException("Unknown type: " + id.type());
                }))
                .criteriaResolver(c -> (s, ctx) -> EventCriteria.havingTags("Person", s.key()))
                .entityIdResolver(config -> new PersonIdentifierEntityIdResolver())
                .build();
        return configurer.componentRegistry(cr -> cr.registerModule(personEntityModule));
    }
}
