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

package org.axonframework.messaging.monitoring;

import org.axonframework.messaging.core.Message;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiMessageMonitorTest {

    @Mock private MessageMonitor<Message> messageMonitorMock;

    @Test
    void onMessageIngested_SingleMessageMonitor_failure() {
        MessageMonitor.MonitorCallback callback = mock(MessageMonitor.MonitorCallback.class);
        MultiMessageMonitor<Message> multiMessageMonitor = new MultiMessageMonitor<>(Arrays.asList(messageMonitorMock));
        Message messageMock = mock(Message.class);
        Mockito.when(messageMonitorMock.onMessageIngested(messageMock)).thenReturn(callback);

        MessageMonitor.MonitorCallback monitorCallback = multiMessageMonitor.onMessageIngested(messageMock);
        Throwable throwable = new Throwable();
        monitorCallback.reportFailure(throwable);

        Mockito.verify(messageMonitorMock).onMessageIngested(ArgumentMatchers.same(messageMock));
        Mockito.verify(callback).reportFailure(ArgumentMatchers.same(throwable));
    }

    @Test
    void onMessageIngested_SingleMessageMonitor_success() {
        MessageMonitor.MonitorCallback callback = mock(MessageMonitor.MonitorCallback.class);
        MultiMessageMonitor<Message> multiMessageMonitor = new MultiMessageMonitor<>(Arrays.asList(messageMonitorMock));
        Message messageMock = mock(Message.class);
        Mockito.when(messageMonitorMock.onMessageIngested(messageMock)).thenReturn(callback);

        MessageMonitor.MonitorCallback monitorCallback = multiMessageMonitor.onMessageIngested(messageMock);
        monitorCallback.reportSuccess();

        Mockito.verify(messageMonitorMock).onMessageIngested(ArgumentMatchers.same(messageMock));
        Mockito.verify(callback).reportSuccess();
    }

    @Test
    void onMessageIngested_MultipleMessageMonitors(
            @Mock MessageMonitor<Message> messageMonitorMock1,
            @Mock MessageMonitor<Message> messageMonitorMock2
    ) {
        MessageMonitor.MonitorCallback callback1 = mock(MessageMonitor.MonitorCallback.class);
        MessageMonitor.MonitorCallback callback2 = mock(MessageMonitor.MonitorCallback.class);
        MultiMessageMonitor<Message> multiMessageMonitor = new MultiMessageMonitor<>(Arrays.asList(messageMonitorMock1,
                                                                                                   messageMonitorMock2));
        Message messageMock = mock(Message.class);
        Mockito.when(messageMonitorMock1.onMessageIngested(messageMock)).thenReturn(callback1);
        Mockito.when(messageMonitorMock2.onMessageIngested(messageMock)).thenReturn(callback2);

        multiMessageMonitor.onMessageIngested(messageMock).reportSuccess();

        Mockito.verify(messageMonitorMock1).onMessageIngested(ArgumentMatchers.same(messageMock));
        Mockito.verify(callback1).reportSuccess();
        Mockito.verify(messageMonitorMock2).onMessageIngested(ArgumentMatchers.same(messageMock));
        Mockito.verify(callback2).reportSuccess();
    }
}
