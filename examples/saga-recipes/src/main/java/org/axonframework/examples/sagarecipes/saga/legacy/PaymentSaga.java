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
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.annotation.DeadlineHandler;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.payment.write.rejectpayment.RejectPayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.messaging.Scope;
import org.axonframework.messaging.ScopeDescriptor;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.spring.stereotype.Saga;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Duration;

/**
 * The bike rental sample application's {@code PaymentSaga}, moved across as literally as {@code axoniq-legacy} allows.
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
 *     <li>The handlers return {@code void} and, apart from the request for payment, never look at what the command
 *     did. Axon Framework 4's {@code commandGateway.send(..)} was fire-and-forget in exactly this way, and a failed
 *     approval or rejection therefore left the process stuck. The Axon Framework 5 recipes return the
 *     {@code CompletableFuture} instead, which is what makes the event processor retry.</li>
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
 * Deadlines are ported with {@code axoniq-legacy}'s {@code DeadlineManager}, and they behave as they did. A prepared
 * payment nobody confirms within 30 seconds is rejected, a rejected rental request calls that timeout off, and a
 * request for payment the payment context could not take is made again five seconds later, through the same method
 * that made the first attempt. Besides the {@code DeadlineManager} arriving as a parameter, three things differ.
 * <ul>
 *     <li>The deadline payloads are the raw {@link String}s of the payment's identifier and reference, as the
 *     original's were, rather than {@code PaymentId} and {@code PaymentReference}, so they do not depend on how a
 *     {@code DeadlineManager} stores or converts its payload.</li>
 *     <li>A failed dispatch is noticed with {@code CommandResult.onError(..)}, since {@code send(..)} on a
 *     {@code CommandDispatcher} returns a {@code CommandResult} rather than a {@code CompletableFuture}.</li>
 *     <li>The class suppresses deprecation warnings, because {@code axoniq-legacy} deprecates scheduling new deadlines
 *     through a {@code DeadlineManager}. It keeps Axon Framework 4 code running while migrating; it is not how to
 *     schedule in new code. The recipe that does solve payment timeouts without a deadline manager is
 *     {@code saga/deadline}.</li>
 * </ul>
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Saga
@ConditionalOnProperty(name = "saga.recipe", havingValue = "legacy")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@SuppressWarnings({"removal", "deprecation"})
public class PaymentSaga {

    private BikeId bikeId;
    private String renter;

    /**
     * Asks for payment as soon as a bike is requested, associating the Saga with the payment it is about to order.
     *
     * @param event           the event that started this Saga
     * @param lifecycle       gives access to this Saga's association values
     * @param dispatcher      dispatches the resulting command
     * @param deadlineManager schedules and cancels this Saga's deadlines
     */
    @StartSaga
    @SagaEventHandler(associationProperty = "bikeId")
    public void on(BikeRequested event, SagaLifecycle lifecycle, CommandDispatcher dispatcher,
                   DeadlineManager deadlineManager) {
        this.bikeId = event.bikeId();
        this.renter = event.renter();
        PaymentReference reference = RentalPaymentReference.forRental(event.rentalId());
        lifecycle.associateWith("paymentReference", reference.raw());
        preparePayment(reference.raw(), dispatcher, deadlineManager);
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
     * Ends the Saga when the request is turned down for reasons of the rental context's own, and calls off the payment
     * timeout that is still running.
     * <p>
     * Note what the original did <b>not</b> do, and what the Axon Framework 5 recipes have to: call the payment off.
     * Axon Framework 4 cancelled the timeout and let the payment stand.
     *
     * @param event           the rejection
     * @param deadlineManager schedules and cancels this Saga's deadlines
     */
    @EndSaga
    @SagaEventHandler(associationProperty = "bikeId")
    public void on(RequestRejected event, DeadlineManager deadlineManager) {
        deadlineManager.cancelAllWithinScope("cancelPayment");
    }

    /**
     * Starts the clock on a payment that has been set up and not yet paid.
     *
     * @param event           the payment that is waiting to be paid
     * @param deadlineManager schedules and cancels this Saga's deadlines
     */
    @SagaEventHandler(associationProperty = "paymentReference")
    public void on(PaymentPrepared event, DeadlineManager deadlineManager) {
        deadlineManager.schedule(Duration.ofSeconds(30), "cancelPayment", event.paymentId().raw());
    }

    /**
     * Gives up on a payment nobody paid in time.
     * <p>
     * The deadline carries the payment's raw identifier, a plain {@link String} as it was in the original, so that the
     * deadline does not depend on how a {@code DeadlineManager} stores its payload.
     *
     * @param paymentId  the raw identifier of the payment that went unpaid
     * @param dispatcher dispatches the resulting command
     */
    @DeadlineHandler(deadlineName = "cancelPayment")
    public void cancelPayment(String paymentId, CommandDispatcher dispatcher) {
        dispatcher.send(new RejectPayment(PaymentId.of(paymentId)));
    }

    /**
     * Asks for the payment, and asks again five seconds later whenever the command could not be dispatched.
     * <p>
     * The first attempt, made when the Saga starts, and every retry, made by the {@code retryPayment} deadline, go
     * through this one method, as in the original. The Saga's scope is described before the command is dispatched,
     * because the dispatch can fail after this handler has returned, when no scope is current any more.
     *
     * @param rentalReference the raw payment reference of the rental being paid for
     * @param dispatcher      dispatches the resulting command
     * @param deadlineManager schedules and cancels this Saga's deadlines
     */
    @DeadlineHandler(deadlineName = "retryPayment")
    public void preparePayment(String rentalReference, CommandDispatcher dispatcher,
                               DeadlineManager deadlineManager) {
        ScopeDescriptor scope = Scope.describeCurrentScope();
        dispatcher.send(new PreparePayment(PaymentReference.of(rentalReference), RentalPricing.PRICE))
                  .onError(e -> deadlineManager.schedule(Duration.ofSeconds(5),
                                                         "retryPayment",
                                                         rentalReference,
                                                         scope));
    }
}
