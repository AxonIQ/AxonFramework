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

package org.axonframework.messaging.commandhandling.gateway;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ContextAwareCommandDispatcher}.
 *
 * @author Steven van Beelen
 */
class ContextAwareCommandDispatcherTest {

    private static final String TEST_COMMAND_PAYLOAD_ONE = "My command 1";
    private static final int TEST_COMMAND_PAYLOAD_TWO = 500;
    private static final GenericCommandResultMessage TEST_RESULT_MESSAGE_ONE =
            new GenericCommandResultMessage(new MessageType("result"), "resultOne");
    private static final GenericCommandResultMessage TEST_RESULT_MESSAGE_TWO =
            new GenericCommandResultMessage(new MessageType("result"), "resultTwo");

    private CommandGateway commandGateway = mock(CommandGateway.class);
    private ProcessingContext context;
    private CommandDispatcher testSubject;

    private ArgumentCaptor<Object> payloadCaptor;
    private CompletableFuture<Message> messageOneFuture;
    private CompletableFuture<Message> messageTwoFuture;

    @BeforeEach
    void setUp() {
        commandGateway = mock(CommandGateway.class);
        context = new StubProcessingContext();

        testSubject = new ContextAwareCommandDispatcher(commandGateway, context);

        payloadCaptor = ArgumentCaptor.forClass(Object.class);
        messageOneFuture = new CompletableFuture<>();
        messageTwoFuture = new CompletableFuture<>();
        when(commandGateway.send(any(), eq(context)))
                .thenReturn(() -> messageOneFuture)
                .thenReturn(() -> messageTwoFuture);
        when(commandGateway.send(any(), any(Metadata.class), eq(context)))
                .thenReturn(() -> messageOneFuture)
                .thenReturn(() -> messageTwoFuture);
    }

    @Test
    void sendCommandsAreGivenToCommandGateway() {
        CommandResult resultOne = testSubject.send(TEST_COMMAND_PAYLOAD_ONE);
        CompletableFuture<? extends Message> resultMessageOne = resultOne.getResultMessage();
        assertThat(resultMessageOne).isNotCompleted();
        messageOneFuture.complete(TEST_RESULT_MESSAGE_ONE);
        assertThat(resultMessageOne).isCompleted();

        CommandResult resultTwo = testSubject.send(TEST_COMMAND_PAYLOAD_TWO);
        CompletableFuture<? extends Message> resultMessageTwo = resultTwo.getResultMessage();
        assertThat(resultMessageTwo).isNotCompleted();
        messageTwoFuture.complete(TEST_RESULT_MESSAGE_TWO);
        assertThat(resultMessageTwo).isCompleted();

        verify(commandGateway, times(2)).send(payloadCaptor.capture(), eq(context));

        List<Object> resultCommandPayloads = payloadCaptor.getAllValues();
        assertThat(resultCommandPayloads.size()).isEqualTo(2);
        Object resultPayloadOne = resultCommandPayloads.get(0);
        Object resultPayloadTwo = resultCommandPayloads.get(1);
        assertThat(TEST_COMMAND_PAYLOAD_ONE).isEqualTo(resultPayloadOne);
        assertThat(TEST_COMMAND_PAYLOAD_TWO).isEqualTo(resultPayloadTwo);
    }

    @Test
    void sendCommandsWithMetadataAreGivenToCommandGateway() {
        Metadata metadataOne = Metadata.with("keyOne", "valueOne");
        Metadata metadataTwo = Metadata.with("keyTwo", "valueTwo");

        CommandResult resultOne = testSubject.send(TEST_COMMAND_PAYLOAD_ONE, metadataOne);
        CompletableFuture<? extends Message> resultMessageOne = resultOne.getResultMessage();
        assertThat(resultMessageOne).isNotCompleted();
        messageOneFuture.complete(new GenericCommandResultMessage(new MessageType("result"), "resultOne"));
        assertThat(resultMessageOne).isCompleted();

        CommandResult resultTwo = testSubject.send(TEST_COMMAND_PAYLOAD_TWO, metadataTwo);
        CompletableFuture<? extends Message> resultMessageTwo = resultTwo.getResultMessage();
        assertThat(resultMessageTwo).isNotCompleted();
        messageTwoFuture.complete(TEST_RESULT_MESSAGE_TWO);
        assertThat(resultMessageTwo).isCompleted();

        ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);

        verify(commandGateway, times(2)).send(payloadCaptor.capture(), metadataCaptor.capture(), eq(context));

        List<Object> resultCommandPayloads = payloadCaptor.getAllValues();
        assertThat(resultCommandPayloads.size()).isEqualTo(2);
        Object resultPayloadOne = resultCommandPayloads.get(0);
        Object resultPayloadTwo = resultCommandPayloads.get(1);
        assertThat(TEST_COMMAND_PAYLOAD_ONE).isEqualTo(resultPayloadOne);
        assertThat(TEST_COMMAND_PAYLOAD_TWO).isEqualTo(resultPayloadTwo);

        List<Metadata> resultMetadata = metadataCaptor.getAllValues();
        assertThat(resultMetadata.size()).isEqualTo(2);
        Metadata resultMetadataOne = resultMetadata.get(0);
        Metadata resultMetadataTwo = resultMetadata.get(1);
        assertThat(metadataOne).isEqualTo(resultMetadataOne);
        assertThat(metadataTwo).isEqualTo(resultMetadataTwo);
    }

    @Test
    void sendCommandsWithResponseTypeAreGivenToCommandGateway() throws ExecutionException, InterruptedException {
        CompletableFuture<String> resultOne = testSubject.send(TEST_COMMAND_PAYLOAD_ONE, String.class);
        CompletableFuture<String> resultTwo = testSubject.send(TEST_COMMAND_PAYLOAD_TWO, String.class);
        assertThat(resultOne).isNotCompleted();
        messageOneFuture.complete(new GenericCommandResultMessage(new MessageType("result"), "resultOne"));
        assertThat(resultOne).isCompleted();
        assertThat(resultOne.get()).isEqualTo("resultOne");

        assertThat(resultTwo).isNotCompleted();
        messageTwoFuture.complete(TEST_RESULT_MESSAGE_TWO);
        assertThat(resultTwo).isCompleted();
        assertThat(resultTwo.get()).isEqualTo("resultTwo");

        verify(commandGateway, times(2)).send(payloadCaptor.capture(), eq(context));

        List<Object> resultCommandPayloads = payloadCaptor.getAllValues();
        assertThat(resultCommandPayloads.size()).isEqualTo(2);
        Object resultPayloadOne = resultCommandPayloads.get(0);
        Object resultPayloadTwo = resultCommandPayloads.get(1);
        assertThat(TEST_COMMAND_PAYLOAD_ONE).isEqualTo(resultPayloadOne);
        assertThat(TEST_COMMAND_PAYLOAD_TWO).isEqualTo(resultPayloadTwo);
    }

    @Test
    void describeToDescribesCommandGatewayAndProcessingContext() {
        MockComponentDescriptor testDescriptor = new MockComponentDescriptor();

        testSubject.describeTo(testDescriptor);

        Map<String, Object> resultDescribedProperties = testDescriptor.getDescribedProperties();
        assertThat(resultDescribedProperties.size()).isEqualTo(2);
        assertThat(resultDescribedProperties.containsKey("processingContext")).isTrue();
        assertThat(resultDescribedProperties.get("processingContext")).isInstanceOf(ProcessingContext.class);
        assertThat(resultDescribedProperties.containsKey("commandGateway")).isTrue();
        assertThat(resultDescribedProperties.get("commandGateway")).isInstanceOf(CommandGateway.class);
    }
}