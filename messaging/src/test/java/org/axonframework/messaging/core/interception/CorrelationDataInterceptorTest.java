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

package org.axonframework.messaging.core.interception;

import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.interception.CorrelationDataInterceptor.CORRELATION_DATA;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link CorrelationDataInterceptor}.
 *
 * @author Rene de Waele
 */
class CorrelationDataInterceptorTest {

    private static final GenericMessage TEST_MESSAGE = new GenericMessage(new MessageType("message"), "payload");

    private CorrelationDataProvider providerOne;
    private CorrelationDataProvider providerTwo;
    private MessageDispatchInterceptorChain<Message> dispatchInterceptorChain;
    private MessageHandlerInterceptorChain<Message> handlerInterceptorChain;

    private CorrelationDataInterceptor<Message> testSubject;

    @BeforeEach
    void setUp() {
        providerOne = mock(CorrelationDataProvider.class);
        providerTwo = mock(CorrelationDataProvider.class);
        dispatchInterceptorChain = mock();
        handlerInterceptorChain = mock();

        testSubject = new CorrelationDataInterceptor<>(Arrays.asList(providerOne, providerTwo));
    }

    @Test
    void interceptOnDispatchProvidesMessageAsIsForNullContext() {
        testSubject.interceptOnDispatch(TEST_MESSAGE, null, dispatchInterceptorChain);

        verify(dispatchInterceptorChain).proceed(TEST_MESSAGE, null);
    }

    @Test
    void interceptOnDispatchProvidesMessageAsWhenCorrelationDataResourceIsNotPresent() {
        ProcessingContext testContext = new StubProcessingContext();

        testSubject.interceptOnDispatch(TEST_MESSAGE, testContext, dispatchInterceptorChain);

        verify(dispatchInterceptorChain).proceed(TEST_MESSAGE, testContext);
    }

    @Test
    void interceptOnDispatchAttachesCorrelationDataFromContextToMessage() {
        Map<String, String> expectedCorrelationData = Map.of("key1", "value2", "key2", "value2");
        ProcessingContext testContext = new StubProcessingContext().withResource(CORRELATION_DATA,
                                                                                 expectedCorrelationData);

        testSubject.interceptOnDispatch(TEST_MESSAGE, testContext, dispatchInterceptorChain);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(dispatchInterceptorChain).proceed(messageCaptor.capture(), eq(testContext));
        assertThat(messageCaptor.getValue().metadata()).isEqualTo(expectedCorrelationData);
    }

    @Test
    void interceptOnHandleAttachesCorrelationDataProvidersToProcessingContext() {
        Map<String, String> expectedCorrelationData = Map.of("key1", "value2", "key2", "value2");
        ProcessingContext testContext = new StubProcessingContext();

        when(providerOne.correlationDataFor(any())).thenReturn(Map.of("key1", "value"));
        when(providerTwo.correlationDataFor(any())).thenReturn(Map.of("key1", "value2", "key2", "value2"));

        testSubject.interceptOnHandle(TEST_MESSAGE, testContext, handlerInterceptorChain);

        verify(providerOne).correlationDataFor(TEST_MESSAGE);
        verify(providerTwo).correlationDataFor(TEST_MESSAGE);
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(handlerInterceptorChain).proceed(eq(TEST_MESSAGE), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getResource(CORRELATION_DATA)).isEqualTo(expectedCorrelationData);
    }
}
