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

package sagas.shared;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * The messages the rental payment process reacts to and sends, shared by every sample on the saga pages.
 */
public class RentalPaymentApi {

    // tag::tags[]
    public static final String BIKE_ID = "bikeId";
    public static final String RENTAL_ID = "rentalId";
    public static final String PAYMENT_REFERENCE = "paymentReference";
    // end::tags[]

    // tag::rental-events[]
    public record BikeRequested(
            @EventTag(key = BIKE_ID) String bikeId,
            String renter,
            @EventTag(key = RENTAL_ID) String rentalId
    ) {

    }

    public record BikeInUse(
            @EventTag(key = BIKE_ID) String bikeId,
            String renter,
            @EventTag(key = RENTAL_ID) String rentalId
    ) {

    }

    public record RequestRejected(
            @EventTag(key = BIKE_ID) String bikeId,
            String renter,
            @EventTag(key = RENTAL_ID) String rentalId
    ) {

    }
    // end::rental-events[]

    // tag::payment-events[]
    public record PaymentPrepared(
            @EventTag(key = "paymentId") String paymentId,
            int amount,
            @EventTag(key = PAYMENT_REFERENCE) String paymentReference // <1>
    ) {

    }

    public record PaymentConfirmed(
            @EventTag(key = "paymentId") String paymentId,
            @EventTag(key = PAYMENT_REFERENCE) String paymentReference
    ) {

    }

    public record PaymentRejected(
            @EventTag(key = "paymentId") String paymentId,
            @EventTag(key = PAYMENT_REFERENCE) String paymentReference
    ) {

    }

    public record PaymentCancelled(
            @EventTag(key = "paymentId") String paymentId,
            @EventTag(key = PAYMENT_REFERENCE) String paymentReference
    ) {

    }
    // end::payment-events[]

    // tag::commands[]
    public record PreparePayment(@TargetEntityId String paymentReference, int amount) {

    }

    public record CancelPayment(@TargetEntityId String paymentReference) {

    }

    public record ApproveRequest(@TargetEntityId String bikeId, String renter) {

    }

    public record RejectRequest(@TargetEntityId String bikeId, String renter) {

    }
    // end::commands[]

    // tag::cancel-rental-payment[]
    public record CancelRentalPayment(@TargetEntityId String rentalId) {

    }
    // end::cancel-rental-payment[]

    // tag::correlation[]
    /**
     * The only place in the application that knows a rental identifier and a payment reference are the same value.
     */
    public static String paymentReferenceFor(String rentalId) {
        return rentalId;
    }

    public static String rentalIdFor(String paymentReference) {
        return paymentReference;
    }
    // end::correlation[]

    public static final int PRICE = 10;
}
