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
package migration.paths.aggregates.index.eventsourcinghandlers;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

// tag::override-with-super[]
class Wallet {

    int balance;

    @EventSourcingHandler
    void onDeposit(Deposited event) {
        balance += event.amount();
    }
}

class BonusWallet extends Wallet {

    @Override
    @EventSourcingHandler
    void onDeposit(Deposited event) {
        super.onDeposit(event);
        balance += 2 * event.amount();
    }
}
// end::override-with-super[]
