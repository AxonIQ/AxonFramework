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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.StringUtils;
import org.axonframework.messaging.core.MessageType;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Read-side resolver that constructs a {@link MessageType} from the stored qualified name and version of a stored
 * event.
 * <p>
 * The write-side counterpart, {@link org.axonframework.messaging.core.MessageTypeResolver}, derives a
 * {@link MessageType} from a payload class at dispatch time. This interface handles the opposite direction,
 * specifically for events that are reconstructed from a storage solution. Given the stored {@code qualifiedName} and
 * (possibly {@code null} or empty) {@code version}, it returns the {@link MessageType} to use when reading a stored
 * event.
 * <p>
 * The default resolver, obtained from {@link #DEFAULT}, substitutes {@value #MISSING_VERSION_DEFAULT} for any
 * missing or empty stored version. This ensures that events stored by Axon Framework 4 (which allowed a {@code null}
 * revision) can be read by Axon Framework 5 without any schema migration. Use
 * {@link #withDefaultVersion(String)} to override the substituted version when {@value #MISSING_VERSION_DEFAULT}
 * does not suit your needs.
 * <p>
 * Implementations are permitted to rewrite the {@code qualifiedName}, but doing so is discouraged: semantic event
 * evolution belongs in the transformation layer, not in version normalization. This interface is intentionally narrow;
 * keep implementations focused on resolving a missing version.
 *
 * @author Steven van Beelen
 * @since 5.1.2
 */
@FunctionalInterface
public interface EventTypeResolver {

    /**
     * The version substituted for {@code null} or empty versions by the {@link #DEFAULT} event type resolver.
     * <p>
     * Set to {@value}, deliberately distinct from {@link MessageType#DEFAULT_VERSION} ({@code 0.0.1}), so legacy AF4
     * events without a revision always sort as older than any explicitly versioned event.
     */
    String MISSING_VERSION_DEFAULT = "0.0.0";

    /**
     * A {@code EventTypeResolver} that substitutes {@value MISSING_VERSION_DEFAULT} for any {@code null} or empty
     * stored version, and passes the stored version through unchanged when present.
     */
    EventTypeResolver DEFAULT = withDefaultVersion(MISSING_VERSION_DEFAULT);

    /**
     * Returns a {@code EventTypeResolver} that substitutes the given {@code defaultVersion} for any {@code null} or
     * empty stored version, and passes the stored version through unchanged when present.
     *
     * @param defaultVersion the version to use when the stored version is absent; must not be {@code null} or empty
     * @return a {@code EventTypeResolver} that substitutes the given {@code defaultVersion} for any {@code null} or
     * empty stored version, and passes the stored version through unchanged when present
     * @throws NullPointerException if {@code defaultVersion} is {@code null}
     */
    static EventTypeResolver withDefaultVersion(String defaultVersion) {
        requireNonNull(defaultVersion, "The defaultVersion may not be null.");
        //noinspection DataFlowIssue
        return (qualifiedName, version) -> new MessageType(
                qualifiedName,
                StringUtils.nonEmptyOrNull(version) ? version : defaultVersion
        );
    }

    /**
     * Resolves the {@link MessageType} for an event read from the event store.
     *
     * @param qualifiedName the qualified name of the event as stored; never {@code null}
     * @param version       the version of the event as stored; may be {@code null} or empty for events written by Axon
     *                      Framework 4, which allowed a {@code null} revision
     * @return the {@link MessageType} to use for the event; never {@code null}
     */
    MessageType resolve(String qualifiedName, @Nullable String version);
}
