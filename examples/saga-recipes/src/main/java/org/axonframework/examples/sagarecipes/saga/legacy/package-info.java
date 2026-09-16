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

/**
 * The rental payment process as the bike rental sample application wrote it, running unchanged on
 * {@code axon-legacy}.
 * <p>
 * The "before" picture the migration guide needs, and the only package in this module that is not an answer to
 * "how would you model this in Axon Framework 5". Nothing here is a pattern to copy: it is Axon Framework 4 code,
 * kept recognisable on purpose, so a reader can see what their own Saga will look like on the day they upgrade and
 * what it costs to leave it that way.
 * <p>
 * It is also deliberately outside the shared recipe contract. Four of those seven scenarios describe behaviour the
 * original Saga simply does not have, so making it pass them would mean rewriting it, which would destroy the one
 * thing this package is for. What it does share with the recipes is asserted in its own test instead.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
package org.axonframework.examples.sagarecipes.saga.legacy;
