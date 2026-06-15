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

package org.axonframework.examples.demo.coursecatalog.catalog;

import org.axonframework.examples.demo.coursecatalog.shared.ids.CatalogId;

/**
 * Constants for the single catalog instance used throughout the demo. Mirrors the
 * {@code Ids.FACULTY_ID} pattern in the university-demo: one catalog per running
 * application keeps the wiring trivial.
 */
public final class Ids {

    /** The catalog every demo command, projection, and seeded event refers to. */
    public static final CatalogId CATALOG_ID = CatalogId.of("axoniq-university");

    private Ids() {
    }
}
