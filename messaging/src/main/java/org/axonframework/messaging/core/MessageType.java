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

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Record combining a {@link QualifiedName qualified name} and {@code version}.
 * <p>
 * The {@code QualifiedName} is useful to provide clear names to {@link Message Messages},
 * {@link MessageHandler MessageHandlers}, and other components that require naming.
 * <p>
 * Implements {@link VersionedType} to allow non-message components (such as snapshot stores) to reference
 * versioned type information without implying the component is a message.
 * <p>
 * When you do not require a version for typing, consider using the {@code QualifiedName} directly instead.
 *
 * @param qualifiedName The {@code QualifiedName} of this {@code MessageType}.
 * @param version       the version of this {@code MessageType}. Must not be blank, or contain a hash ({@code #}),
 *                      since that character is reserved as the separator in this record's String representation.
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
public record MessageType(QualifiedName qualifiedName, String version) implements VersionedType {

    /**
     * The default version of a {@code MessageType} when none is given. Set to {@code 0.0.1}.
     */
    public static final String DEFAULT_VERSION = "0.0.1";

    /**
     * The separator between a {@link #qualifiedName()} and {@link #version()} in the String representation produced
     * by {@link #toString()} and parsed by {@link #fromString(String)}. Also enforced as forbidden in
     * {@link QualifiedName#QualifiedName(String) QualifiedName's name} and in {@link #version}, since either
     * containing this separator would make that representation ambiguous to parse back.
     */
    public static final String VERSION_DELIMITER = "#";

    /**
     * Pattern identifying a single valid segment of a {@code MessageType}'s String representation - either the
     * {@link #qualifiedName()} or the {@link #version()} - requiring at least one non-whitespace character and no
     * {@value #VERSION_DELIMITER}. Shared by this record's and {@link QualifiedName}'s constructors, and
     * combined with itself (separated by {@value #VERSION_DELIMITER}) to build
     * {@code VALID_STRING_REPRESENTATION_PATTERN}, so a single definition governs what is considered valid
     * everywhere.
     */
    static final Pattern VALID_SEGMENT = Pattern.compile("(?!\\s*$)[^#]+");

    private static final Pattern VALID_STRING_REPRESENTATION = Pattern.compile(
            "^(?<qualifiedName>" + VALID_SEGMENT.pattern() + ")" + VERSION_DELIMITER
                    + "(?<version>" + VALID_SEGMENT.pattern() + ")$"
    );

    /**
     * Constructor validating that the given {@code qualifiedName} is non-null, and that {@code version} is
     * non-null, not blank, and does not contain the hash ({@code #}) used as version delimiter.
     */
    public MessageType {
        requireNonNull(qualifiedName, "The qualifiedName cannot be null.");
        requireNonNull(version, "The version is unsupported because it is null.");

        if (!VALID_SEGMENT.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "The version [" + version + "] is unsupported because it is blank, or contains \""
                            + VERSION_DELIMITER + "\", which is reserved as the separator in MessageType's "
                            + "String representation."
            );
        }
    }

    /**
     * A {@code MessageType} constructor setting the {@code version} to {@code null}.
     *
     * @param name The {@code QualifiedName} of this {@code MessageType}.
     */
    public MessageType(QualifiedName name) {
        this(name, DEFAULT_VERSION);
    }

    /**
     * A {@code MessageType} constructor using the given {@code qualifiedName} invoking the
     * {@link QualifiedName#QualifiedName(String)} constructor. The {@code version} is fixed to {@code null}.
     *
     * @param qualifiedName The qualifiedName for the {@link QualifiedName#QualifiedName(String) QualifiedName} for the
     *                      {@code MessageType} under construction.
     */
    public MessageType(String qualifiedName) {
        this(new QualifiedName(qualifiedName), DEFAULT_VERSION);
    }

    /**
     * A {@code MessageType} constructor using the given {@code qualifiedName} to invoke the
     * {@link QualifiedName#QualifiedName(String)} constructor to derive the {@code qualifiedName} for this
     * {@code MessageType}.
     *
     * @param qualifiedName The qualified qualifiedName used to invoke {@link QualifiedName#QualifiedName(String)}
     *                      constructor for the {@code MessageType} under construction.
     * @param version       The version for the {@code MessageType} under construction.
     */
    public MessageType(String qualifiedName,
                       String version) {
        this(new QualifiedName(qualifiedName), version);
    }

    /**
     * A {@code MessageType} constructor using the given {@code namespace} and {@code localName} to invoke the
     * {@link QualifiedName#QualifiedName(String, String)} constructor to derive the {@code qualifiedName} for this
     * {@code MessageType}.
     *
     * @param namespace The namespace used to invoke {@link QualifiedName#QualifiedName(String, String)} constructor for
     *                  the {@code MessageType} under construction.
     * @param localName The local qualifiedName used to invoke {@link QualifiedName#QualifiedName(String, String)}
     *                  constructor for the {@code MessageType} under construction.
     * @param version   The version for the {@code MessageType} under construction.
     */
    public MessageType(String namespace,
                       String localName,
                       String version) {
        this(new QualifiedName(namespace, localName), version);
    }

    /**
     * A {@code MessageType} constructor using the given {@code clazz} to invoke the
     * {@link QualifiedName#QualifiedName(Class)} constructor  to derive the {@code qualifiedName} for this
     * {@code MessageType}.
     *
     * @param clazz   The {@code Class} used to invoke {@link QualifiedName#QualifiedName(Class)} constructor for the
     *                {@code MessageType} under construction.
     * @param version The version for the {@code MessageType} under construction.
     */
    public MessageType(Class<?> clazz,
                       String version) {
        this(new QualifiedName(clazz), version);
    }

    /**
     * A {@code MessageType} constructor using the given {@code clazz} to invoke the
     * {@link QualifiedName#QualifiedName(Class)} constructor  to derive the {@code qualifiedName} for this
     * {@code MessageType}. The {@code version} is fixed to {@code null}.
     *
     * @param clazz The {@code Class} used to invoke {@link QualifiedName#QualifiedName(Class)} constructor for the
     *              {@code MessageType} under construction.
     */
    public MessageType(Class<?> clazz) {
        this(clazz, DEFAULT_VERSION);
    }

    /**
     * Returns the outcome {@link QualifiedName#name()} of this {@code MessageType's} {@link QualifiedName}.
     *
     * @return The outcome {@link QualifiedName#name()} of this {@code MessageType's} {@link QualifiedName}.
     */
    public String name() {
        return qualifiedName.toString();
    }

    /**
     * Reconstruct a {@code MessageType} based on the output of {@link MessageType#toString()}.
     * <p>
     * The output of {@code MessageType#toString()} is a concatenation of the {@link #qualifiedName()} and
     * {@link #version()}, split by means of a hash ({@code #}).
     * <p>
     * Thus, if the given {@code String} equals {@code "my.context.BusinessName#1.0.5"}, the {@code #qualifiedName()} is
     * set to a {@link QualifiedName} of {@code "my.context.BusinessName"} and the {@link #version()} is set to
     * {@code "1.0.5"}.
     *
     * @param messageTypeString The output of {@link MessageType#toString()}, given to reconstruct it into a
     *                          {@code MessageType}.
     * @return A reconstructed {@code MessageType} based on the expected output of {@link MessageType#toString()}.
     */
    public static MessageType fromString(String messageTypeString) {
        Matcher matcher = VALID_STRING_REPRESENTATION.matcher(Objects.requireNonNull(
                messageTypeString,
                "Cannot construct a MessageType based on a null or empty String."
        ));

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "The given simple String [" + messageTypeString + "] does not match the expected pattern."
            );
        }

        return new MessageType(new QualifiedName(matcher.group("qualifiedName")), matcher.group("version"));
    }

    /**
     * The output of {@code MessageType#toString()} is a concatenation of the {@link #qualifiedName()} and
     * {@link #version()}, split by means of a hash ({@code #}).
     * <p>
     * Thus, if {@code #qualifiedName()} returns {@code "my.context.BusinessName"} and the {@code #version()} returns
     * {@code "1.0.5"}, the result of <b>this</b> operation would be {@code "my.context.BusinessName#1.0.5"}.
     *
     * @return A combination of the {@link #qualifiedName()} and {@link #version()}, separated by a hash.
     */
    @Override
    public String toString() {
        return qualifiedName + VERSION_DELIMITER + version;
    }
}
