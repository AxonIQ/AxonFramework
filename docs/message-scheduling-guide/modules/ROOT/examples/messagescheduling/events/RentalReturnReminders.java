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
package messagescheduling.events;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// tag::rental-return-reminder-due[]
record RentalReturnReminderDue(String rentalId, Instant dueBack) {

}
// end::rental-return-reminder-due[]

// tag::schedule-event[]
public class RentalReturnReminders {

    private final ScheduledExecutorService trigger = Executors.newSingleThreadScheduledExecutor(); // <1>
    private final EventGateway eventGateway;

    public RentalReturnReminders(EventGateway eventGateway) {
        this.eventGateway = eventGateway;
    }

    public void scheduleReminder(String rentalId, Instant dueBack) {
        Duration delay = Duration.between(Instant.now(), dueBack.minus(Duration.ofHours(1)));
        trigger.schedule(
                () -> eventGateway.publish(null, new RentalReturnReminderDue(rentalId, dueBack)), // <2>
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }
}
// end::schedule-event[]

// tag::notify-renter[]
class ReturnReminderForRenter {

    @EventHandler
    void on(RentalReturnReminderDue event) {
        // Notify renter that he/she/they should return the rented item.
    }
}
// end::notify-renter[]

// tag::notify-rentee[]
class ReturnReminderForRentee {

    @EventHandler
    void on(RentalReturnReminderDue event) {
        // Notify rentee that an item is being returned late.
    }
}
// end::notify-rentee[]
