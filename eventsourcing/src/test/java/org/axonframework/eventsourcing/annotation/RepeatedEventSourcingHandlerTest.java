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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating that a method repeating the {@link EventSourcingHandler @EventSourcingHandler} annotation
 * evolves the entity for every declared event name.
 *
 * @author Allard Buijze
 */
class RepeatedEventSourcingHandlerTest {

    private static final EntityEvolver<Enrollment> EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
            Enrollment.class,
            new DelegatingEventConverter(PassThroughConverter.INSTANCE),
            new AnnotationMessageTypeResolver(),
            ClasspathParameterResolverFactory.forClass(Enrollment.class),
            ClasspathHandlerDefinition.forClass(Enrollment.class)
    );

    @Test
    void evolvesTheEntityForEveryRepeatedEventSourcingHandler() {
        // given
        var enrollment = new Enrollment();
        var paid = paymentEvent("billing.EnrollmentPaid", "payment-1");
        var settled = paymentEvent("billing.EnrollmentSettled", "payment-2");

        // when
        enrollment = EVOLVER.evolve(enrollment, paid, StubProcessingContext.forMessage(paid));
        enrollment = EVOLVER.evolve(enrollment, settled, StubProcessingContext.forMessage(settled));

        // then
        assertThat(enrollment.payments).containsExactly("payment-1", "payment-2");
    }

    @Test
    void derivesAnUndefinedNamespaceFromThePayloadType() {
        // given
        var enrollment = new Enrollment();
        var refunded = paymentEvent("billing.EnrollmentRefunded", "payment-1");

        // when
        enrollment = EVOLVER.evolve(enrollment, refunded, StubProcessingContext.forMessage(refunded));

        // then
        assertThat(enrollment.refunds).containsExactly("payment-1");
    }

    private static EventMessage paymentEvent(String name, String paymentId) {
        return asEventMessage(new GenericMessage(new MessageType(name), new PaymentReceived(paymentId)));
    }

    @Event(namespace = "billing", name = "PaymentReceived")
    record PaymentReceived(String paymentId) {

    }

    static class Enrollment {

        private final List<String> payments = new ArrayList<>();
        private final List<String> refunds = new ArrayList<>();

        @EventSourcingHandler(namespace = "billing", name = "EnrollmentPaid")
        @EventSourcingHandler(namespace = "billing", name = "EnrollmentSettled")
        void onPayment(PaymentReceived event) {
            payments.add(event.paymentId());
        }

        @EventSourcingHandler(name = "EnrollmentRefunded")
        void onRefund(PaymentReceived event) {
            refunds.add(event.paymentId());
        }
    }
}
