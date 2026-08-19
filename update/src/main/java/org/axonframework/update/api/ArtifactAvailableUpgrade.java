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

package org.axonframework.update.api;

import org.axonframework.common.annotation.Internal;

/**
 * Represents an upgrade suggestion for a specific artifact version by the Axoniq UpdateChecker API.
 *
 * @param groupId         The group ID of the library.
 * @param artifactId      The artifact ID of the library.
 * @param latestVersion   The latest version of the library available for upgrade.
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Internal
public record ArtifactAvailableUpgrade(
        String groupId,
        String artifactId,
        String latestVersion
) {

}
