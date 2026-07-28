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

package org.axonframework.hunt.scenario;

/**
 * The delivery guarantee a run's deployment can actually provide.
 * <p>
 * This is not a preference and not a target: the guarantee genuinely differs with how the deployment is put together,
 * and the framework says so. A processor writes the batch's handler effects and this cycle's token progress in one
 * unit of work, so when the token store and the read model are the same transactional resource, that unit of work is
 * one transaction and processing is exactly-once. When they are not -- a token in a database and a projection
 * somewhere else -- the effects can land while the token write is lost, the batch is retried, and the guarantee is
 * at-least-once with the handler expected to be idempotent.
 * <p>
 * A scenario declares which of the two its deployment is, because a checker that guessed would be inventing the
 * guarantee it is supposed to be verifying. Declaring exactly-once for a deployment that cannot provide it turns
 * every ordinary retry into a false finding; declaring at-least-once for one that can hides a real duplicate.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public enum DeliveryMode {

    /**
     * The token store and the read model share one transactional resource, so no event is ever handled twice.
     * <p>
     * Any repeated delivery at all is a violation under this mode, whatever else was happening at the time.
     */
    EXACTLY_ONCE,

    /**
     * The token store and the read model are separate resources, so a repeated delivery is expected where a claim
     * changed hands or a node recovered, and event loss is never expected anywhere.
     */
    AT_LEAST_ONCE_NO_LOSS
}
