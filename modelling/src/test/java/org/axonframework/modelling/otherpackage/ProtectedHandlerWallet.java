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

package org.axonframework.modelling.otherpackage;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Test entity declaring a protected event handler, to be overridden from a different package.
 * <p>
 * A protected method is visible to subclasses in other packages, so a method with the same signature declared there
 * overrides it in Java.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class ProtectedHandlerWallet {

    public int balance;
    public final List<String> ran = new ArrayList<>();

    @EventHandler
    protected void onDeposit(Deposited event) {
        ran.add("ProtectedHandlerWallet.onDeposit");
        balance += event.amount();
    }

    /**
     * Event accessible from both the declaring package and subclasses in other packages.
     *
     * @param amount the deposited amount
     */
    public record Deposited(int amount) {

    }
}
