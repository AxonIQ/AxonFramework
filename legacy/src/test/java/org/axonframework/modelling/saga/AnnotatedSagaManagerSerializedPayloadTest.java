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

    private void handle(EventMessage event) {
        var unitOfWork = configuration.getComponent(UnitOfWorkFactory.class).create();
        unitOfWork.runOnInvocation(context -> testSubject.handle(event, context));
        FutureUtils.joinAndUnwrap(unitOfWork.execute(), Duration.ofSeconds(5));
    }
}
