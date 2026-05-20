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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa;

import org.axonframework.common.ClockUtils;
import org.jspecify.annotations.Nullable;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import org.axonframework.common.ClassUtils;
import org.axonframework.common.DateTimeUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.conversion.Converter;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Objects;

import static org.axonframework.common.DateTimeUtils.formatInstant;

/**
 * Implementation of a token entry compatible with JPA that stores its serialized token as a byte array.
 *
 * @author Rene de Waele
 * @author Allard Buijze
 * @author Simon Zambrovski
 * @since 3.0.0
 */
@Entity
@IdClass(TokenEntry.PK.class)
public class TokenEntry {

    /**
     * Computes a token timestamp.
     *
     * @return A token timestamp.
     */
    public static String computeTokenTimestamp() {
        return formatInstant(ClockUtils.instant());
    }

    @Lob
    @Column(length = 10000)
    private byte[] token;

    @Basic
    private String tokenType;

    @Basic(optional = false)
    private String timestamp;

    @Basic
    private String owner;

    @Id
    private String processorName;

    @Id
    private int segment;

    @Basic(optional = false)
    private int mask;

    /**
     * Initializes a new token entry for given {@code token}, {@code processorName} and {@code segment}. The given
     * {@code converter} is used to serialize the token before it is stored.
     *
     * @param processorName The name of the processor to store this token for.
     * @param segment       The segment of the processor.
     * @param token         The tracking token to store.
     * @param converter     The converter to use when storing a serialized token.
     */
    public TokenEntry(String processorName,
                      Segment segment,
                      @Nullable TrackingToken token,
                      Converter converter) {
        this.timestamp = formatInstant(ClockUtils.instant());
        if (token != null) {
            this.token = converter.convert(token, byte[].class);
            this.tokenType = token.getClass().getName();
        }
        this.processorName = processorName;
        this.segment = segment.getSegmentId();
        this.mask = segment.getMask();
    }

    /**
     * Default constructor for JPA.
     */
    @SuppressWarnings("unused")
    protected TokenEntry() {
    }

    /**
     * Returns the token, deserializing it with given {@code serializer}
     *
     * @param converter The converter to deserialize the token with.
     * @return The deserialized token stored in this entry.
     */
    public TrackingToken getToken(Converter converter) {
        return (token == null || tokenType == null)
                ? null
                : converter.convert(this.token, ClassUtils.loadClass(tokenType));
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
     * Returns the storage timestamp of this token entry as a String.
     *
     * @return The storage timestamp as string.
     */
    public String timestampAsString() {
        return timestamp;
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
     * Updates a token, using the provided token and serializer to update the serialized token and token type. It will
     * also update the timestamp to the current time, using the inherited static {@link java.time.Clock}.
     *
     * @param token     The new token that needs to be persisted.
     * @param converter The converter that will be used to serialize the token.
     */
    public void updateToken(@Nullable TrackingToken token, Converter converter) {
        this.timestamp = formatInstant(ClockUtils.instant());
        if (token != null) {
            this.token = converter.convert(token, byte[].class);
            this.tokenType = token.getClass().getName();
        } else {
            this.token = null;
            this.tokenType = null;
        }
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
        return new Segment(segment, mask);
    }

    /**
     * Primary key for token entries used by JPA.
     */
    @SuppressWarnings("UnusedDeclaration")
    public static class PK {

        private String processorName;
        private int segment;

        /**
         * Constructor for JPA.
         */
        public PK() {
        }

        /**
         * Constructs a primary key for a {@code TokenEntry}.
         *
         * @param processorName The name of the processor.
         * @param segment       The index of the processing segment.
         */
        public PK(String processorName, int segment) {
            this.processorName = processorName;
            this.segment = segment;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PK pk = (PK) o;
            return segment == pk.segment && Objects.equals(processorName, pk.processorName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(processorName, segment);
        }
    }
}
