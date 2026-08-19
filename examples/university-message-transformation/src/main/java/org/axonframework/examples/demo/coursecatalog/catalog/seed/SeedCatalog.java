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

package org.axonframework.examples.demo.coursecatalog.catalog.seed;

import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Triggers the {@link LegacyEventSeeder} to write the historic events that pretend
 * to have been in the store for years. Idempotent: the seeder checks for its own
 * marker event before publishing anything.
 *
 * @param catalogId the catalog to seed
 */
public record SeedCatalog(@TargetEntityId CatalogId catalogId) {
}
