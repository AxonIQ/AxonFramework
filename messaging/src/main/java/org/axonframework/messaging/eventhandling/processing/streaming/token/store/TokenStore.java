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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store;

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Describes a component capable of storing and retrieving event {@link TrackingToken tracking tokens}.
 * <p>
 * A {@link StreamingEventProcessor} that is tracking an event
 * stream can use the store to keep track of its position in the event stream. Tokens are stored by process name and
 * segment index, enabling the same processor to be distributed over multiple processes or machines.
 * <p>
 * A token store is always part of a larger unit of work: every operation takes the {@link ProcessingContext} it is
 * part of, which must not be {@code null}. Operations that consist of several statements, or that are combined by a
 * caller (such as splitting or merging segments), rely on that context for atomicity.
 * <p>
 * Implementations that persist tokens in a database require the context to carry a transaction. The
 * {@code JpaTokenStore} fails with a {@code TokenStoreTransactionRequiredException} when its entity manager is not part
 * of a transaction.
 *
 * @author Allard Buijze
 * @author Rene de Waele
 * @since 3.0.0
 */
public interface TokenStore {

    /**
     * Initializes a given number of segments for the given {@code processorName} to track its tokens.
     * <p>
     * Returns a {@link CompletableFuture} that completes when the segments were successfully initialized.
     * The returned future will complete exceptionally with an {@link UnableToClaimTokenException} when
     * a segment to be initialized already exists.
     * <p>
     * This method should only be invoked when <b>no</b> tokens have been stored for the given processor, yet.
     * <p>
     * This method will store the given {@code initialToken} for all segments as the starting point for the processor
     * with the given {@code processorName}, but not claim them. It will create the segments ranging from {@code 0}
     * until {@code segmentCount - 1}.
     * <p>
     * The exact behavior when this method is called while tokens were already present is undefined in case the token
     * already present is not owned by the initializing process.
     *
     * @param processorName the name of the processor to initialize segments for
     * @param segmentCount  the number of segments to initialize
     * @param initialToken  the initial token which is used as a starting point for the processor
     * @param context       the processing context to use when initializing the segments, cannot be {@code null}
     * @return a {@code CompletableFuture} that completes when the segments have been initialized
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<List<Segment>> initializeTokenSegments(
            String processorName,
            int segmentCount,
            @Nullable TrackingToken initialToken,
            ProcessingContext context
    );

    /**
     * Stores the given {@code token} in the store.
     * <p>
     * Returns a {@link CompletableFuture} that completes when the token was successfully stored.
     * The returned future will complete exceptionally with an {@link UnableToClaimTokenException} if
     * the token was not initialized, or was claimed by another process.
     * <p>
     * The token marks the current position of the process with given {@code processorName} and {@code segment}. The
     * given {@code token} may be {@code null}. Any claims made by the current process have their timestamp updated.
     *
     * @param token         the token to store for a given process and segment, may be {@code null}
     * @param processorName the name of the process for which to store the token
     * @param segmentId     the index of the segment for which to store the token
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@code CompletableFuture} that completes when the token has been stored
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<Void> storeToken(@Nullable TrackingToken token,
                                       String processorName,
                                       int segmentId,
                                       ProcessingContext context);

    /**
     * Fetches the last stored {@link TrackingToken token} for the given {@code processorName} and {@code segmentId}.
     * <p>
     * Returns a {@link CompletableFuture} that completes with the fetched token, or {@code null} if the token was
     * {@code null}.
     * The returned future will complete exceptionally with an {@link UnableToClaimTokenException} if
     * the token did not exist or was claimed by another process.
     * <p>
     * The token will be claimed by the current process (JVM instance), preventing access by other instances. To release
     * the claim, use {@link #releaseClaim(String, int, ProcessingContext)}
     *
     * @param processorName the process name for which to fetch the token
     * @param segmentId     the segment index for which to fetch the token
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@code CompletableFuture} with the last stored TrackingToken or {@code null} if the store holds no
     * token for the given process and segment
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                int segmentId,
                                                ProcessingContext context);

    /**
     * Fetches the last stored {@link TrackingToken token} for the given {@code processorName} and {@code segment}.
     * <p>
     * Returns a {@link CompletableFuture} that completes with the fetched token, or {@code null} if the token was
     * {@code null}.
     * The returned future will complete exceptionally with an {@link UnableToClaimTokenException} if
     * the token did not exist or was claimed by another process.
     * <p>
     * The token will be claimed by the current process (JVM instance), preventing access by other instances. To release
     * the claim, use {@link #releaseClaim(String, int, ProcessingContext)}
     *
     * @param processorName the process name for which to fetch the token
     * @param segment       the segment for which to fetch the token
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@code CompletableFuture} with the last stored {@link TrackingToken token} or {@code null} if the store
     * holds no token for the given process and segment
     * @throws NullPointerException if {@code context} is {@code null}
     */
    default CompletableFuture<TrackingToken> fetchToken(
            String processorName,
            Segment segment,
            ProcessingContext context
    ) {
        return fetchToken(processorName, segment.getSegmentId(), context);
    }

    /**
     * Extends the claim on the current token held by this node for the given {@code processorName} and
     * {@code segment}.
     * <p>
     * Returns a {@link CompletableFuture} that completes when the claim has been successfully extended.
     * The returned future will complete exceptionally with an {@link UnableToClaimTokenException} if
     * the token did not exist or was claimed by another process.
     *
     * @param processorName the process name for which to fetch the token
     * @param segmentId     the segment index for which to fetch the token
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@code CompletableFuture} that completes when the claim-extension has been completed
     * @implSpec By default, this method invokes {@link #fetchToken(String, int, ProcessingContext)}, which also extends
     * the claim if the token is held. Token store implementations may choose to implement this method if they can
     * provide a more efficient way of extending this claim.
     * @throws NullPointerException if {@code context} is {@code null}
     */
    default CompletableFuture<Void> extendClaim(
            String processorName,
            int segmentId,
            ProcessingContext context
    ) {
        return fetchToken(processorName, segmentId, context).thenRun(() -> {
        });
    }

    /**
     * Release a claim of the token for given {@code processorName} and {@code segment}. If no such claim existed,
     * nothing happens.
     * <p>
     * The caller must ensure not to use any streams opened based on the token for which the claim is released.
     *
     * @param processorName the name of the process owning the token (e.g. a PooledStreamingEventProcessor name)
     * @param segmentId     the segment for which a token was obtained
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@code CompletableFuture} that completes when the claim-release has been completed
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<Void> releaseClaim(String processorName,
                                         int segmentId,
                                         ProcessingContext context);

    /**
     * Initializes a segment with given {@code segment} for the processor with given {@code processorName} to contain
     * the given {@code token}.
     * <p>
     * Returns a {@link CompletableFuture} that completes when the token segment has been successfully initialized.
     * The returned future will complete exceptionally with an {@link UnableToInitializeTokenException} if
     * the token already exists.
     * <p>
     * This method fails if a token already exists for the given processor and segment, even if that token has been
     * claimed by the active instance. This method will not claim the initialized segment. Use
     * {@link #fetchToken(String, int, ProcessingContext)} to retrieve and claim the token.
     *
     * @param token         the token to initialize the segment with
     * @param processorName the name of the processor to create the segment for
     * @param segment       the segment to initialize
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@link CompletableFuture} that completes when the segment has been initialized
     * @throws UnsupportedOperationException if this implementation does not support explicit initialization
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<Void> initializeSegment(
            @Nullable TrackingToken token,
            String processorName,
            Segment segment,
            ProcessingContext context
    );

    /**
     * Deletes the token associated with the specified {@code processorName} and {@code segmentId}.
     * <p>
     * Returns a {@link CompletableFuture} that completes when the token has been successfully deleted.
     * The returned future will complete exceptionally with an {@link UnableToClaimTokenException} if
     * the token is not currently claimed by this node.
     * <p>
     * The token <b>must</b> be owned by the current process (JVM instance) to be able to delete it.
     *
     * @param processorName the name of the processor to remove the token for
     * @param segmentId     the segment to delete
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@link CompletableFuture} that completes when the token deletion is complete
     * @throws UnsupportedOperationException if this operation is not supported by this implementation
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<Void> deleteToken(String processorName,
                                        int segmentId,
                                        ProcessingContext context);

    /**
     * Returns a {@link CompletableFuture} that supplies the specified {@link Segment}, or {@code null}
     * if there was no such segment.
     *
     * @param processorName the process name for which to fetch the segment
     * @param segmentId     the segment index to fetch
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@link CompletableFuture} with the segment, or {@code null} on completion
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<Segment> fetchSegment(String processorName, int segmentId, ProcessingContext context);

    /**
     * Returns a {@link CompletableFuture} that supplies a list of known {@link Segment segments} for a
     * given {@code processorName} on completion.
     * <p>
     * The segments returned are segments for which a token has been stored previously. When the {@code TokenStore} is
     * empty, the {@link CompletableFuture} will return an empty array.
     *
     * @param processorName the process name for which to fetch the segments
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@link CompletableFuture} with a list of segments on completion
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<List<Segment>> fetchSegments(String processorName, ProcessingContext context);

    /**
     * Returns a {@link CompletableFuture} supplying a {@code List} of known <b>available</b> {@link Segment Segments}
     * for a given {@code processorName}.
     * <p>
     * A segment is considered available if it is not claimed by any other event processor.
     * <p>
     * The segments returned are segments for which a token has been stored previously and have not been claimed by
     * another processor. When the {@code TokenStore} is empty, an empty list is returned.
     *
     * @param processorName the processor's name for which to fetch the segments
     * @param context       the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@code List} of available {@link Segment Segments} for the specified {@code processorName}
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<List<Segment>> fetchAvailableSegments(String processorName,
                                                            ProcessingContext context);

    /**
     * Retrieves the storage identifier associated with this store. The returned identifier uniquely identifies
     * the storage location for the tokens in this store.
     * <p>
     * Returns a {@link CompletableFuture} that completes with the storage identifier associated with this store.
     * The returned future will complete exceptionally with an {@link UnableToRetrieveIdentifierException}
     * if the identifier could not be retrieved.
     * <p>
     * Two token store implementations that share state <b>must</b> return the same identifier. Two token store
     * implementations that do not share a location must return a different identifier (or an empty optional if
     * identifiers are not supported).
     * <p>
     * Note that this method may require the implementation to consult its underlying storage. Therefore, the given
     * {@code context} must carry a transaction, similarly to invocations like
     * {@link #fetchToken(String, int, ProcessingContext)}, {@link #fetchSegments(String, ProcessingContext)}, etc.
     *
     * @param context the current {@link ProcessingContext}, cannot be {@code null}
     * @return a {@link CompletableFuture} that provides an identifier to uniquely identify the storage location of
     * tokens in this {@code TokenStore} on completion
     * @throws NullPointerException if {@code context} is {@code null}
     */
    CompletableFuture<String> retrieveStorageIdentifier(ProcessingContext context);
}