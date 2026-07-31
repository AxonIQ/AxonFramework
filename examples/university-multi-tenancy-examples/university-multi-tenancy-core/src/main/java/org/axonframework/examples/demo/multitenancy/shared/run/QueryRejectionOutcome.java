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

package org.axonframework.examples.demo.multitenancy.shared.run;

/**
 * What the query-side tenant guardrails observed. A query is served for one tenant, so the framework refuses
 * one it cannot resolve a served tenant for, whichever way that tenant is missing.
 * <p>
 * These hold identically in memory and against Axon Server, since the refusal happens before a query reaches
 * either a local handler or a tenant's own connection.
 *
 * @param rejectedForUnknownTenant whether a query naming a tenant the application never registered was refused
 * @param rejectedForMissingTenant whether a query carrying no tenant metadata at all was refused
 * @param rejectedForRemovedTenant whether a query naming a tenant that has been removed was refused
 * @author Laura Devriendt
 * @since 5.3.0
 */
public record QueryRejectionOutcome(boolean rejectedForUnknownTenant,
                                    boolean rejectedForMissingTenant,
                                    boolean rejectedForRemovedTenant) {

}
