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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc;

import org.axonframework.common.ClockUtils;
import org.jspecify.annotations.Nullable;
import org.axonframework.common.DateTimeUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.LegacyTokenTypes;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry;
import org.axonframework.conversion.Converter;

import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Objects;

import static org.axonframework.common.DateTimeUtils.formatInstant;

/**
 * Jdbc class of an entry containing a serialized tracking token belonging to a given process. For use with
 * Jakarta/Hibernate 6 use {@link TokenEntry} instead.
 *
 * @author Rene de Waele
 * @author Simon Zambrovski
 * @since 3.0.0
 */
public class JdbcTokenEntry {

    private byte[] token;
    private String tokenType;
    private String timestamp;
    private String owner;
    private String processorName;
    private Segment segment;

    /**
     * Initializes a new token entry for given {@code token}, {@code process} and {@code segment}. The given
     * {@code converter} can be used to serialize the token before it is stored.
     *
     * @param token     The tracking token to store.
     * @param converter The converter to use when storing a serialized token.
     */
    public JdbcTokenEntry(@Nullable TrackingToken token, Converter converter) {
        updateToken(token, converter);
    }

    /**
     * Initializes a token entry from existing data.
     *
     * @param token         The serialized token.
     * @param tokenType     The serialized type of the token.
     * @param timestamp     The timestamp of the token.
     * @param owner         The owner of the token.
     * @param processorName The processor name.
     * @param segment       The token segment.
     */
    public JdbcTokenEntry(byte[] token,
                          String tokenType,
                          String timestamp,
                          String owner,
                          String processorName,
                          Segment segment) {
        this.token = token;
        this.tokenType = tokenType;
        this.timestamp = timestamp;
        this.owner = owner;
        this.processorName = processorName;
        this.segment = segment;
    }

    /**
     * Default constructor required for JPA
     */
    @SuppressWarnings("unused")
    protected JdbcTokenEntry() {
    }

    /**
     * Returns the token, deserializing it with given {@code converter}.
     *
     * @param converter The converter to deserialize the token with.
     * @return The deserialized token stored in this entry.
     */
    public TrackingToken getToken(Converter converter) {
        if (token == null || tokenType == null) {
            return null;
        }
        return LegacyTokenTypes.deserialize(converter, token, tokenType);
    }

    /**
     * Returns the storage timestamp of this token entry.
     *
     * @return The storage timestamp.
     */
    public Instant timestamp() {
        return DateTimeUtils.parseInstant(timestamp);
    }

    /**
     * Returns the storage timestamp of this token entry as a String.
     *
     * @return The storage timestamp as string.
     */
    public String timestampAsString() {
        return timestamp;
    }

    /**
     * Returns the name of the {@link StreamingEventProcessor} to
     * which this token belongs.
     *
     * @return The name of the {@link StreamingEventProcessor} to
     * which this token belongs.
     */
    public String getProcessorName() {
        return processorName;
    }

    /**
     * Returns the segment of this token.
     *
     * @return The segment of this token.
     */
    public Segment getSegment() {
        return segment;
    }

    /**
     * Returns the identifier of the process (JVM) having a claim on this token, or {@code null} if the token isn't
     * claimed.
     *
     * @return The process (JVM) that claimed this token.
     */
    public String getOwner() {
        return owner;
    }

    /**
     * Retrieves serialized value of the token.
     *
     * @return Returns token value as bytes.
     */
    public byte[] getTokenData() {
        return token;
    }

    /**
     * Retrieves token type.
     *
     * @return Returns token type.
     */
    public String getTokenType() {
        return tokenType;
    }

    /**
     * Attempt to claim ownership of this token. When successful, this method returns {@code true}, otherwise
     * {@code false}. When a claim fails, this token should not be used, as it is already being used in another
     * process.
     * <p>
     * If a claim exists, but it is older than given {@code claimTimeout}, the claim may be 'stolen'.
     *
     * @param owner        The name of the current node, to register as owner. This name must be unique for multiple
     *                     instances of the same logical processor.
     * @param claimTimeout The time after which a claim may be 'stolen' from its current owner.
     * @return {@code true} if the claim succeeded, otherwise false.
     */
    public boolean claim(String owner, TemporalAmount claimTimeout) {
        if (!mayClaim(owner, claimTimeout)) {
            return false;
        }
        this.timestamp = formatInstant(ClockUtils.instant());
        this.owner = owner;
        return true;
    }

    /**
     * Check if given {@code owner} may claim this token.
     *
     * @param owner        The name of the current node, to register as owner. This name must be unique for multiple
     *                     instances of the same logical processor.
     * @param claimTimeout The time after which a claim may be 'stolen' from its current owner.
     * @return {@code true} if the claim may be made, {@code false} otherwise.
     */
    public boolean mayClaim(String owner, TemporalAmount claimTimeout) {
        return this.owner == null || owner.equals(this.owner) || expired(claimTimeout);
    }

    private boolean expired(TemporalAmount claimTimeout) {
        return timestamp().plus(claimTimeout).isBefore(ClockUtils.instant());
    }

    /**
     * Release any claim of ownership currently active on this Token, if owned by the given {@code owner}.
     *
     * @param owner The name of the current node, which was registered as the owner.
     * @return {@code true} of the claim was successfully released, or {@code false} if the token has been claimed by
     * another owner.
     */
    public boolean releaseClaim(String owner) {
        if (Objects.equals(this.owner, owner)) {
            this.owner = null;
            this.timestamp = formatInstant(ClockUtils.instant());
        }
        return this.owner == null;
    }


    /**
     * Update the token data to the given {@code token}, using given {@code converter} to serialize it to bytes[].
     *
     * @param token     The token representing the state to update to.
     * @param converter The converter to update token to.
     */
    public final void updateToken(@Nullable TrackingToken token, Converter converter) {
        this.timestamp = formatInstant(ClockUtils.instant());
        if (token != null) {
            this.token = converter.convert(token, byte[].class);
            this.tokenType = token.getClass().getName();
        } else {
            this.token = null;
            this.tokenType = null;
        }
    }
}
