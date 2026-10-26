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

/**
 * Test entity overriding the package-private handler of {@link PackagePrivateHandlerWallet} as a public method without
 * the event handler annotation, so a subclass in yet another package can override it again.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public class PublicOverrideWallet extends PackagePrivateHandlerWallet {

    @Override
    public void onDeposit(Deposited event) {
        ran.add("PublicOverrideWallet.onDeposit");
        balance += event.amount();
    }
}
