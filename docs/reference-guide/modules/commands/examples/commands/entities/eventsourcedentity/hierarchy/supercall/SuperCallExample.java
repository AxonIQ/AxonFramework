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
package commands.entities.eventsourcedentity.hierarchy.supercall;

import commands.entities.eventsourcedentity.Deposited;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

class Account {

    int balance;

    @EventSourcingHandler
    void on(Deposited event) {
        balance += event.amount();
    }
}

// tag::super-call[]
class BonusAccount extends Account {

    @Override
    @EventSourcingHandler
    void on(Deposited event) {
        super.on(event);
        balance += event.amount();
    }
}
// end::super-call[]
