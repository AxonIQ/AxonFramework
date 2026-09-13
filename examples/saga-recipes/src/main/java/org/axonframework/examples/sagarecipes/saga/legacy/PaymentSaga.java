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

package org.axonframework.examples.sagarecipes.saga.legacy;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.spring.stereotype.Saga;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The bike rental sample application's {@code PaymentSaga}, moved across as literally as {@code axon-legacy} allows.
 * <p>
 * <b>This is not a recipe to imitate.</b> The other implementations of this process under
 * {@link org.axonframework.examples.sagarecipes.saga} show how to model it in Axon Framework 5. This one exists so
 * the migration guide has running "before" code to put next to them, and so the claim that an Axon Framework 4 Saga
 * keeps working is something the build checks rather than something a document asserts.
 * <p>
 * What was kept, deliberately, even though it is no longer advisable:
 * <ul>
 *     <li>{@link StartSaga @StartSaga} is deprecated for removal. It is used here because the original used it, and
 *     showing Axon Framework 4 code as it was written is the whole point of this class.</li>
 *     <li>The handlers return {@code void} and never look at what the command did. Axon Framework 4's
 *     {@code commandGateway.send(..)} was fire-and-forget in exactly this way, and a failed command therefore left
 *     the process stuck. The Axon Framework 5 recipes return the {@code CompletableFuture} instead, which is what
 *     makes the event processor retry.</li>
 *     <li>{@code bikeId} and {@code renter} are mutable fields filled in by the {@code @StartSaga} handler.
 *     {@code PaymentConfirmed} and {@code PaymentRejected} carry neither, so the process has nowhere else to get
 *     them from. That is the state the recipes each find a different home for.</li>
 * </ul>
 * <p>
 * Two changes were unavoidable. Collaborators arrive as handler parameters rather than as {@code @Autowired
 * transient} fields, because Axon Framework 5 does not inject into a Saga's fields. And the class is annotated for
 * Jackson field visibility, because the Saga is written to its
 * {@link org.axonframework.modelling.saga.repository.SagaStore SagaStore} through a
 * {@link org.axonframework.conversion.Converter Converter}: this application converts with Jackson, which does not
 * see private fields by default, where Axon Framework 4 defaulted to XStream, which did.
 * <p>
 * Everything the original did with a {@code DeadlineManager} is parked as commented-out Axon Framework 4 code until
 * deadlines are ported into {@code axon-legacy}. Leaving it visible, rather than replacing it with an Axon Framework
 * 5 equivalent, keeps this a port: the recipe that does solve payment timeouts without a deadline manager is
 * {@code saga/deadline}.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Saga
@ConditionalOnProperty(name = "saga.recipe", havingValue = "legacy")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@SuppressWarnings("removal")
public class PaymentSaga {

    private BikeId bikeId;
    private String renter;

    /**
     * Asks for payment as soon as a bike is requested, associating the Saga with the payment it is about to order.
     *
     * @param event      the event that started this Saga
     * @param lifecycle  gives access to this Saga's association values
     * @param dispatcher dispatches the resulting command
     */
    @StartSaga
    @SagaEventHandler(associationProperty = "bikeId")
    public void on(BikeRequested event, SagaLifecycle lifecycle, CommandDispatcher dispatcher) {
        this.bikeId = event.bikeId();
        this.renter = event.renter();
        PaymentReference reference = RentalPaymentReference.forRental(event.rentalId());
        lifecycle.associateWith("paymentReference", reference.raw());
        dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE));
        // TODO #5006 - Axon Framework 4 retried a failed dispatch through a scheduled "retryPayment" deadline:
        // commandGateway.send(new PreparePayment(reference, RentalPricing.PRICE))
        //               .whenComplete((r, e) -> {
        //                   if (e != null) {
        //                       deadlineManager.schedule(Duration.ofSeconds(5), "retryPayment", reference, scope);
        //                   }
        //               });
    }

    /**
     * Confirms the rental request once the payment is in.
     *
     * @param event      the payment that came in
     * @param dispatcher dispatches the resulting command
     */
    @EndSaga
    @SagaEventHandler(associationProperty = "paymentReference")
    public void on(PaymentConfirmed event, CommandDispatcher dispatcher) {
        // we approve the bike request
        dispatcher.send(new ApproveRequest(bikeId, renter));
    }

    /**
     * Releases the bike when the payment is refused.
     *
     * @param event      the refusal
     * @param dispatcher dispatches the resulting command
     */
    @SagaEventHandler(associationProperty = "paymentReference")
    public void on(PaymentRejected event, CommandDispatcher dispatcher) {
        dispatcher.send(new RejectRequest(bikeId, renter));
    }

    /**
     * Ends the Saga when the request is turned down for reasons of the rental context's own.
     * <p>
     * The original cancelled the payment timeout here and did nothing else, which is why the body is empty: with no
     * deadline to cancel there is nothing left to do. Note what the original did <b>not</b> do, and what the Axon
     * Framework 5 recipes have to: call the payment off. Axon Framework 4 let it stand and relied on the timeout.
     *
     * @param event the rejection
     */
    @EndSaga
    @SagaEventHandler(associationProperty = "bikeId")
    public void on(RequestRejected event) {
        // TODO #5006 - Axon Framework 4 cancelled the payment timeout scheduled below:
        // deadlineManager.cancelAllWithinScope("cancelPayment");
    }

    /**
     * Starts the clock on a payment that has been set up and not yet paid.
     * <p>
     * Empty for now: scheduling the timeout is all the original did here.
     *
     * @param event the payment that is waiting to be paid
     */
    @SagaEventHandler(associationProperty = "paymentReference")
    public void on(PaymentPrepared event) {
        // TODO #5006 - Axon Framework 4 scheduled the payment timeout here:
        // deadlineManager.schedule(Duration.ofSeconds(30), "cancelPayment", event.paymentId());
    }

    // TODO #5006 - Axon Framework 4 gave up on a payment nobody paid in time:
    // @DeadlineHandler(deadlineName = "cancelPayment")
    // public void cancelPayment(String paymentId) {
    //     commandGateway.send(new RejectPayment(PaymentId.of(paymentId)));
    // }

    // TODO #5006 - Axon Framework 4 re-attempted a dispatch that had failed, and asked for the payment in the first
    // place through the very same method:
    // @DeadlineHandler(deadlineName = "retryPayment")
    // public void preparePayment(PaymentReference reference) {
    //     ScopeDescriptor scope = Scope.describeCurrentScope();
    //     commandGateway.send(new PreparePayment(reference, RentalPricing.PRICE))
    //                   .whenComplete((r, e) -> {
    //                       if (e != null) {
    //                           deadlineManager.schedule(Duration.ofSeconds(5), "retryPayment", reference, scope);
    //                       }
    //                   });
    // }
}
