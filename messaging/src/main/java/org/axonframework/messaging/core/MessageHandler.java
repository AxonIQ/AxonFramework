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

package org.axonframework.messaging.core;

/**
 * Marker interface for a handlers or components of {@link Message messages}.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 3.0.0
 */
public interface MessageHandler {

    /**
     * Returns the {@link VersionSpecifier} indicating which message versions this handler supports.
     * <p>
     * During dispatch, the framework uses this specifier to determine whether this handler is eligible to handle an
     * incoming message based on its {@link VersionedType#version() version}.
     * <p>
     * Defaults to {@link VersionSpecifier#any()}, meaning the handler accepts all versions. Override this method to
     * restrict handling to a specific version or version range.
     *
     * @return The {@link VersionSpecifier} for this handler.
     */
    default VersionSpecifier supportedVersions() {
        return VersionSpecifier.any();
    }
}
