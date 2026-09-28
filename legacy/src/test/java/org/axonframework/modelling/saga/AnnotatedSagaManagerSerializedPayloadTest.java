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

package org.axonframework.modelling.saga;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.saga.AnnotatedSagaManagerTest.MyTestSaga;
import org.axonframework.modelling.saga.AnnotatedSagaManagerTest.StartingEvent;
import org.axonframework.modelling.saga.AnnotatedSagaManagerTest.UnrelatedDomainEvent;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Events read from an event store carry a serialized payload. The saga manager must convert it to the handler's
 * payload type before it matches handlers and resolves association values, or every stored event is ignored.
 */
class AnnotatedSagaManagerSerializedPayloadTest {

    private AxonConfiguration configuration;
    private InMemorySagaStore sagaStore;
    private AnnotatedSagaManager<MyTestSaga> testSubject;

    @BeforeEach
    void setUp() {
        configuration = MessagingConfigurer.create().build();
        sagaStore = new InMemorySagaStore();
        testSubject = AnnotatedSagaManager.<MyTestSaga>builder()
                                          .sagaRepository(AnnotatedSagaRepository.<MyTestSaga>builder()
                                                                                 .sagaType(MyTestSaga.class)
                                                                                 .sagaStore(sagaStore)
                                                                                 .build())
                                          .sagaType(MyTestSaga.class)
                                          .sagaFactory(MyTestSaga::new)
                                          .build();
    }

    @AfterEach
    void tearDown() {
        configuration.shutdown();
    }

    @Test
    void aSerializedStartingEventStartsASaga() {
        // given a StartingEvent as it comes from an event store: a byte[] payload under the event's qualified name
        EventConverter converter = configuration.getComponent(EventConverter.class);
        byte[] serialized = converter.convert(new StartingEvent("123"), byte[].class);
        EventMessage stored = new GenericEventMessage(new MessageType(StartingEvent.class), serialized);

        // when
        handle(stored);

        // then the saga was created and associated through the converted payload
        assertThat(sagaStore.findSagas(MyTestSaga.class, new AssociationValue("myIdentifier", "123"))).hasSize(1);
    }

    @Test
    void anAlreadyDeserializedEventIsHandledUnchanged() {
        handle(new GenericEventMessage(new MessageType(StartingEvent.class), new StartingEvent("456")));

        assertThat(sagaStore.findSagas(MyTestSaga.class, new AssociationValue("myIdentifier", "456"))).hasSize(1);
    }

    @Test
    void aSerializedEventWithoutADeclaredHandlerPassesThroughUnconverted() {
        // given a byte[] payload under a name no handler of the saga declares
        EventMessage stored = new GenericEventMessage(new MessageType(UnrelatedDomainEvent.class), new byte[]{1, 2, 3});

        // when
        handle(stored);

        // then no conversion was attempted and no saga was started
        assertThat(sagaStore.size()).isZero();
    }

    private void handle(EventMessage event) {
        var unitOfWork = configuration.getComponent(UnitOfWorkFactory.class).create();
        unitOfWork.runOnInvocation(context -> testSubject.handle(event, context));
        FutureUtils.joinAndUnwrap(unitOfWork.execute(), Duration.ofSeconds(5));
    }
}
