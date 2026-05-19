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

package org.axonframework.extension.tracing.opentelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ClockUtils;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenTelemetrySpanFactoryTest {

    private final static OpenTelemetry otelMock = mock(OpenTelemetry.class);
    private final Tracer tracer = mock(Tracer.class);
    private final TextMapPropagator textMapPropagator = mock(TextMapPropagator.class);
    private final SpanAttributesProvider spanAttributesProvider = mock(SpanAttributesProvider.class);
    private final SpanBuilder spanBuilder = mock(SpanBuilder.class);
    private final ContextKey<Object> parentKey = ContextKey.named("opentelemetry-trace-span-key");

    private OpenTelemetrySpanFactory factory;

    @BeforeEach
    void setUp() {
        // We need to mock out a lot of the OTEL functionality. Without java agent instrumentation, it only uses a NOOP
        // variant, which is useless for the scope of these tests.
        when(otelMock.getTracer(any())).thenReturn(tracer);
        when(otelMock.getPropagators()).thenReturn(() -> textMapPropagator);
        doAnswer(invocationOnMock -> {
            invocationOnMock.getArgument(2, MetadataContextSetter.class)
                            .set(invocationOnMock.getArgument(1), "traceparent", "MY_TRACE_PARENT");
            return null;
        }).when(textMapPropagator).inject(any(Context.class), any(), eq(MetadataContextSetter.INSTANCE));

        doAnswer(invocationOnMock -> {
            MetadataContextGetter getter = invocationOnMock.getArgument(2, MetadataContextGetter.class);
            Message message = invocationOnMock.getArgument(1, Message.class);
            Context context = invocationOnMock.getArgument(0, Context.class);
            return context.with(parentKey, getter.get(message, "traceparent"));
        }).when(textMapPropagator).extract(any(Context.class), any(Message.class), eq(MetadataContextGetter.INSTANCE));

        // Mock out span builder methods
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.addLink(any())).thenReturn(spanBuilder);
        when(spanBuilder.setNoParent()).thenReturn(spanBuilder);
        when(spanBuilder.setParent(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), any())).thenReturn(spanBuilder);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);

        factory = OpenTelemetrySpanFactory.builder()
                                          .tracer(tracer)
                                          .addSpanAttributeProviders(Collections.singletonList(spanAttributesProvider))
                                          .textMapGetter(MetadataContextGetter.INSTANCE)
                                          .textMapSetter(MetadataContextSetter.INSTANCE)
                                          .build();
    }

    @BeforeAll
    static void beforeAll() {
        GlobalOpenTelemetry.set(otelMock);
    }

    private static <P> EventMessage asEventMessage(P event) {
        return new GenericEventMessage(
                new GenericMessage(new MessageType(event.getClass()), event),
                ClockUtils::instant
        );
    }

    @Test
    void createRootTracesCreatesSpanWithNoParentLinkedToCurrent() {
        SpanContext spanContext = Span.current().getSpanContext();
        org.axonframework.messaging.tracing.Span span = factory.createRootTrace(() -> "MyRootTrace");

        verify(spanBuilder).setNoParent();
        verify(spanBuilder).addLink(spanContext);
        verify(spanBuilder).setSpanKind(SpanKind.INTERNAL);
    }

    @Test
    void createHandlerSpanExtractsParentContext() {
        Message message = generateMessageWithTraceId("1");
        factory.createChildHandlerSpan(() -> "MyRootTrace", message);

        ArgumentCaptor<Context> parentCaptor = ArgumentCaptor.forClass(Context.class);
        verify(spanBuilder).setParent(parentCaptor.capture());
        verify(spanBuilder).setSpanKind(SpanKind.CONSUMER);
        assertEquals("1", parentCaptor.getValue().get(parentKey));
    }

    @Test
    void createHandlerSpanAddsLinks() {
        Message message = generateMessageWithTraceId("1");
        factory.createChildHandlerSpan(() -> "MyRootTrace",
                                       message,
                                       generateMessageWithTraceId("2"),
                                       generateMessageWithTraceId("3"));

        verify(spanBuilder).setParent(any());
        verify(spanBuilder).setSpanKind(SpanKind.CONSUMER);
        verify(spanBuilder, times(2)).addLink(any());
    }

    @Test
    void createLinkedHandlerSpanExtractsLinkedContext() {
        Message message = generateMessageWithTraceId("1");
        factory.createLinkedHandlerSpan(() -> "MyRootTrace", message);

        verify(spanBuilder, times(1)).addLink(any());
        verify(spanBuilder).setSpanKind(SpanKind.CONSUMER);
        verify(spanBuilder).setNoParent();
    }

    @Test
    void createHandlerSpanAddsAttributes() {
        Message message = generateMessageWithTraceId("1");
        when(spanAttributesProvider.provideForMessage(any())).thenReturn(Collections.singletonMap("myKey", "myValue"));
        factory.createLinkedHandlerSpan(() -> "MyRootTrace", message);

        verify(spanBuilder).setAttribute("myKey", "myValue");
    }

    @Test
    void createDispatchSpanAddsLinks() {
        Message message = generateMessageWithTraceId("1");
        factory.createDispatchSpan(() -> "MyRootTrace",
                                   message,
                                   generateMessageWithTraceId("2"),
                                   generateMessageWithTraceId("3"));

        verify(spanBuilder).setSpanKind(SpanKind.PRODUCER);
        verify(spanBuilder, times(2)).addLink(any());
    }

    @Test
    void createDispatchSpanSetsCurrentContextAsParent() {
        Message message = generateMessageWithTraceId("1");
        factory.createDispatchSpan(() -> "MyRootTrace",
                                   message,
                                   generateMessageWithTraceId("2"),
                                   generateMessageWithTraceId("3"));

        verify(spanBuilder).setParent(Context.current());
    }

    @Test
    void createDispatchSpanAddsAttributes() {
        Message message = generateMessageWithTraceId("1");
        when(spanAttributesProvider.provideForMessage(any())).thenReturn(Collections.singletonMap("myKey", "myValue"));
        factory.createDispatchSpan(() -> "MyRootTrace", message);

        verify(spanBuilder).setAttribute("myKey", "myValue");
    }

    @Test
    void createInternalSpanWithoutMessage() {
        factory.createInternalSpan(() -> "MyRootTrace");

        verify(spanBuilder).setSpanKind(SpanKind.INTERNAL);
    }

    @Test
    void createInternalSpanSetsCurrentContextAsParent() {
        factory.createInternalSpan(() -> "MyRootTrace");

        verify(spanBuilder).setParent(Context.current());
    }

    @Test
    void createInternalSpanAddsAttributes() {
        Message message = generateMessageWithTraceId("1");
        when(spanAttributesProvider.provideForMessage(any())).thenReturn(Collections.singletonMap("myKey", "myValue"));
        factory.createInternalSpan(() -> "MyRootTrace", message);

        verify(spanBuilder).setSpanKind(SpanKind.INTERNAL);
        verify(spanBuilder).setAttribute("myKey", "myValue");
    }

    @Test
    void propagatesContextInjectsMetadata() {
        EventMessage originalMessage = asEventMessage("MyEvent");
        EventMessage modifiedMessage = factory.propagateContext(originalMessage);

        assertNotNull(modifiedMessage.metadata().get("traceparent"));
    }

    @Test
    void builderRejectsNullTracer() {
        OpenTelemetrySpanFactory.Builder builder = OpenTelemetrySpanFactory.builder();
        assertThrows(AxonConfigurationException.class, () -> builder.tracer(null));
    }

    @Test
    void builderRejectsNullSpanAttributeProviders() {
        OpenTelemetrySpanFactory.Builder builder = OpenTelemetrySpanFactory.builder();
        assertThrows(AxonConfigurationException.class, () -> builder.addSpanAttributeProviders(null));
    }

    @Test
    void builderRejectsNullTextMapGetter() {
        OpenTelemetrySpanFactory.Builder builder = OpenTelemetrySpanFactory.builder();
        assertThrows(AxonConfigurationException.class, () -> builder.textMapGetter(null));
    }

    @Test
    void builderRejectsNullTextMapSetter() {
        OpenTelemetrySpanFactory.Builder builder = OpenTelemetrySpanFactory.builder();
        assertThrows(AxonConfigurationException.class, () -> builder.textMapSetter(null));
    }

    private Message generateMessageWithTraceId(String traceId) {
        return asEventMessage("MyEvent").andMetadata(Collections.singletonMap("traceparent", traceId));
    }
}
