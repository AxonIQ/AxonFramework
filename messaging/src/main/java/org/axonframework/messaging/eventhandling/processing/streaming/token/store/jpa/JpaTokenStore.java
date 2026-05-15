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

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.ConfigToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToInitializeTokenException;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToRetrieveIdentifierException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.TemporalAmount;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.common.DateTimeUtils.formatInstant;
import static org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry.clock;

/**
 * Implementation of a token store that uses JPA to save and load tokens. This implementation uses {@link TokenEntry}
 * entities.
 *
 * @author Rene de Waele
 * @since 3.0.0
 */
public class JpaTokenStore implements TokenStore {

    private static final Logger logger = LoggerFactory.getLogger(JpaTokenStore.class);

    private static final String CONFIG_TOKEN_ID = "__config";
    private static final Segment CONFIG_SEGMENT = new Segment(0, 0);

    private static final String OWNER_PARAM = "owner";
    private static final String PROCESSOR_NAME_PARAM = "processorName";
    private static final String SEGMENT_PARAM = "segment";

    private final TransactionalExecutorProvider<EntityManager> transactionalExecutorProvider;
    private final Converter converter;
    private final TemporalAmount claimTimeout;
    private final String nodeId;
    private final LockModeType loadingLockMode;

    /**
     * Instantiate a {JpaTokenStore} based on the fields contained in the {@link JpaTokenStoreConfiguration}.
     * <p>
     * Will assert that the {@link TransactionalExecutorProvider}, {@link Converter} and
     * {@link JpaTokenStoreConfiguration} are not {@code null}, otherwise an {@link AxonConfigurationException} will be
     * thrown.
     *
     * @param transactionalExecutorProvider The {@link TransactionalExecutorProvider} used to obtain a
     *                                      {@link TransactionalExecutor} for.
     * @param converter                     The {@link Converter} used to serialize and deserialize token for storage.
     * @param configuration                 The configuration for JPA token store.
     */
    public JpaTokenStore(
            TransactionalExecutorProvider<EntityManager> transactionalExecutorProvider,
            Converter converter,
            JpaTokenStoreConfiguration configuration
    ) {
        assertNonNull(transactionalExecutorProvider,
                      "TransactionalExecutorProvider is a hard requirement and should be provided");
        assertNonNull(converter, "The Converter is a hard requirement and should be provided");
        assertNonNull(configuration, "The JpaTokenStoreConfiguration should be provided");
        this.transactionalExecutorProvider = transactionalExecutorProvider;
        this.converter = converter;
        this.claimTimeout = configuration.claimTimeout();
        this.nodeId = configuration.nodeId();
        this.loadingLockMode = configuration.loadingLockMode();
    }

    @Override
    public CompletableFuture<List<Segment>> initializeTokenSegments(
            String processorName,
            int segmentCount,
            @Nullable TrackingToken initialToken,
            @Nullable ProcessingContext context
    ) {
        return entityManagerExecutor(context).apply(em -> {
            boolean segmentsPresent = !fetchSegmentsInternal(processorName, em).isEmpty();
            if (segmentsPresent) {
                throw new UnableToClaimTokenException(
                        "Could not initialize segments. Some segments were already present.");
            }

            List<Segment> segments = Segment.splitBalanced(Segment.ROOT_SEGMENT, segmentCount - 1);

            for (Segment segment : segments) {
                em.persist(new TokenEntry(processorName, segment, initialToken, converter));
            }

            em.flush();
            return segments;
        });
    }

    @Override
    public CompletableFuture<Void> storeToken(@Nullable TrackingToken token,
                                              String processorName,
                                              int segment,
                                              @Nullable ProcessingContext context) {
        return entityManagerExecutor(context).accept(em -> {
            final byte[] tokenDataToStore;
            final String tokenTypeToStore;
            if (token != null) {
                tokenDataToStore = converter.convert(token, byte[].class);
                tokenTypeToStore = token.getClass().getName();
            } else {
                tokenDataToStore = null;
                tokenTypeToStore = TrackingToken.class.getName();
            }
            int updatedTokens = em.createQuery("""
                                                       UPDATE TokenEntry te SET
                                                       te.token = :token,
                                                       te.tokenType = :tokenType,
                                                       te.timestamp = :timestamp
                                                       WHERE te.owner = :owner
                                                       AND te.processorName = :processorName
                                                       AND te.segment = :segment""")
                                  .setParameter("token", tokenDataToStore)
                                  .setParameter("tokenType", tokenTypeToStore)
                                  .setParameter("timestamp", TokenEntry.computeTokenTimestamp())
                                  .setParameter(OWNER_PARAM, nodeId)
                                  .setParameter(PROCESSOR_NAME_PARAM, processorName)
                                  .setParameter(SEGMENT_PARAM, segment)
                                  .executeUpdate();

            if (updatedTokens == 0) {
                logger.debug(
                        "Could not update token [{}] for processor [{}] and segment [{}]. Trying load-then-save approach instead.",
                        token,
                        processorName,
                        segment);
                TokenEntry tokenEntry = loadToken(processorName, segment, em);
                tokenEntry.updateToken(token, converter);
            }
        });
    }

    @Override
    public CompletableFuture<Void> releaseClaim(String processorName,
                                                int segment,
                                                @Nullable ProcessingContext context) {
        return entityManagerExecutor(context)
                .accept(em ->
                                em.createQuery("""
                                                       UPDATE TokenEntry te SET te.owner = null \
                                                       WHERE te.owner = :owner AND te.processorName = :processorName \
                                                       AND te.segment = :segment""")
                                  .setParameter(PROCESSOR_NAME_PARAM, processorName)
                                  .setParameter(SEGMENT_PARAM, segment)
                                  .setParameter(OWNER_PARAM, nodeId)
                                  .executeUpdate()
                );
    }

    @Override
    public CompletableFuture<Void> initializeSegment(
            @Nullable TrackingToken token,
            String processorName,
            Segment segment,
            @Nullable ProcessingContext context
    ) {
        return entityManagerExecutor(context).accept(em -> {
            try {
                em.persist(new TokenEntry(processorName, segment, token, converter));
                em.flush();
            } catch (Exception e) {
                throw new UnableToInitializeTokenException(
                        "Could not initialize processor %s segment %s".formatted(processorName, segment), e
                );
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteToken(
            String processorName,
            int segment,
            @Nullable ProcessingContext context
    ) {
        return entityManagerExecutor(context).accept(em -> {
            int updates = em.createQuery("""
                                                 DELETE FROM TokenEntry te \
                                                 WHERE te.owner = :owner AND te.processorName = :processorName \
                                                 AND te.segment = :segment""")
                            .setParameter(PROCESSOR_NAME_PARAM, processorName)
                            .setParameter(SEGMENT_PARAM, segment)
                            .setParameter(OWNER_PARAM, nodeId)
                            .executeUpdate();

            if (updates == 0) {
                throw new UnableToClaimTokenException("Unable to remove token. It is not owned by " + nodeId);
            }
        });
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                       int segment,
                                                       @Nullable ProcessingContext context) {
        return entityManagerExecutor(context).apply(em -> loadToken(processorName, segment, em).getToken(converter));
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(
            String processorName,
            Segment segment,
            @Nullable ProcessingContext context
    ) {
        return entityManagerExecutor(context).apply(em -> loadToken(processorName, segment, em).getToken(converter));
    }

    @Override
    public CompletableFuture<Void> extendClaim(
            String processorName,
            int segment,
            @Nullable ProcessingContext context
    ) {
        return entityManagerExecutor(context).accept(em -> {
            int updates = em.createQuery("""
                                                 UPDATE TokenEntry te SET te.timestamp = :timestamp \
                                                 WHERE te.processorName = :processorName \
                                                 AND te.segment = :segment \
                                                 AND te.owner = :owner""")
                            .setParameter(PROCESSOR_NAME_PARAM, processorName)
                            .setParameter(SEGMENT_PARAM, segment)
                            .setParameter(OWNER_PARAM, nodeId)
                            .setParameter("timestamp", formatInstant(clock.instant()))
                            .executeUpdate();

            if (updates == 0) {
                throw new UnableToClaimTokenException("Unable to extend the claim on token for processor '" +
                                                              processorName + "[" + segment
                                                              + "]'. It is either claimed " +
                                                              "by another process, or there is no such token.");
            }
        });
    }

    @Override
    public CompletableFuture<Segment> fetchSegment(String processorName,
                                                   int segmentId,
                                                   @Nullable ProcessingContext context) {
        return entityManagerExecutor(context).apply(em -> {
            final TokenEntry te = em.createQuery("""
                                                         SELECT te FROM TokenEntry te \
                                                         WHERE te.processorName = :processorName AND te.segment = :segment""",
                                                 TokenEntry.class)
                                    .setParameter(SEGMENT_PARAM, segmentId)
                                    .setParameter(PROCESSOR_NAME_PARAM, processorName)
                                    .getSingleResultOrNull();

            return te == null ? null : te.getSegment();
        });
    }

    @Override
    public CompletableFuture<List<Segment>> fetchSegments(String processorName,
                                                          @Nullable ProcessingContext context) {
        return entityManagerExecutor(context).apply(em -> fetchSegmentsInternal(processorName, em));
    }

    private static @NonNull List<Segment> fetchSegmentsInternal(@NonNull String processorName, EntityManager em) {
        final List<TokenEntry> resultList =
                em.createQuery("""
                                       SELECT te FROM TokenEntry te \
                                       WHERE te.processorName = :processorName ORDER BY te.segment ASC""",
                               TokenEntry.class)
                  .setParameter(PROCESSOR_NAME_PARAM, processorName)
                  .setLockMode(LockModeType.NONE)
                  .getResultList();

        return resultList.stream().map(TokenEntry::getSegment).toList();
    }

    @Override
    public CompletableFuture<List<Segment>> fetchAvailableSegments(String processorName,
                                                                   @Nullable ProcessingContext context) {
        return entityManagerExecutor(context).apply(em -> {
            final List<TokenEntry> resultList =
                    em.createQuery("""
                                           SELECT te FROM TokenEntry te \
                                           WHERE te.processorName = :processorName ORDER BY te.segment ASC""",
                                   TokenEntry.class)
                      .setParameter(PROCESSOR_NAME_PARAM, processorName)
                      .setLockMode(LockModeType.NONE)
                      .getResultList();

            return resultList.stream()
                             .filter(tokenEntry -> tokenEntry.mayClaim(nodeId, claimTimeout))
                             .map(TokenEntry::getSegment)
                             .collect(Collectors.toList());
        });
    }

    /**
     * Loads an existing {@link TokenEntry} or creates a new one using the given {@code entityManager} for given
     * {@code processorName} and {@code segment}.
     *
     * @param processorName The name of the event processor.
     * @param segment       The segment of the event processor.
     * @param entityManager The entity manager instance to use for the query.
     * @return The token entry for the given processor name and segment.
     * @throws UnableToClaimTokenException If there is a token for given {@code processorName} and {@code segment}, but
     *                                     it is claimed by another process.
     */
    protected TokenEntry loadToken(String processorName, int segment, EntityManager entityManager) {
        TokenEntry token = entityManager.find(TokenEntry.class,
                                              new TokenEntry.PK(processorName, segment),
                                              loadingLockMode);

        if (token == null) {
            throw new UnableToClaimTokenException(format(
                    "Unable to claim token '%s[%s]'. It has not been initialized yet", processorName, segment
            ));
        } else if (!token.claim(nodeId, claimTimeout)) {
            throw new UnableToClaimTokenException(format(
                    "Unable to claim token '%s[%s]'. It is owned by '%s'", processorName, segment, token.getOwner()
            ));
        }
        return token;
    }

    /**
     * Tries loading an existing token owned by a processor with given {@code processorName} and {@code segment}. If
     * such a token entry exists an attempt will be made to claim the token. If that succeeds the token will be
     * returned. If the token is already owned by another node an {@link UnableToClaimTokenException} will be thrown.
     * <p>
     * If no such token exists yet, a new token entry will be inserted with a {@code null} token, owned by this node,
     * and this method returns {@code null}.
     * <p>
     * If a token has been claimed, the {@code segment} will be validated by checking the database for the split and
     * merge candidate segments. If a concurrent split or merge operation has been detected, the calim will be released
     * and an {@link UnableToClaimTokenException} will be thrown.}
     *
     * @param processorName The name of the processor to load or insert a token entry for.
     * @param segment       The segment of the processor to load or insert a token entry for.
     * @param entityManager The entity manager instance to use for the query.
     * @return The tracking token of the fetched entry or {@code null} if a new entry was inserted.
     * @throws UnableToClaimTokenException If the token cannot be claimed because another node currently owns the token
     *                                     or if the segment has been split or merged concurrently.
     */
    protected TokenEntry loadToken(String processorName, Segment segment, EntityManager entityManager) {
        TokenEntry token = loadToken(processorName, segment.getSegmentId(), entityManager);
        try {
            validateSegment(processorName, segment, entityManager);
        } catch (UnableToClaimTokenException e) {
            token.releaseClaim(nodeId);
            throw e;
        }
        return token;
    }

    /**
     * Validate a {@code segment} by checking for the existence of a split or merge candidate segment.
     * <p>
     * If the segment has been split concurrently, the split segment candidate will be found, indicating that we have
     * claimed an incorrect {@code segment}. If the segment has been merged concurrently, the merge candidate segment
     * will no longer exist, also indicating that we have claimed an incorrect {@code segment}.
     *
     * @param processorName The name of the processor to load or insert a token entry for.
     * @param segment       The segment of the processor to load or insert a token entry for.
     */
    private void validateSegment(String processorName, Segment segment, EntityManager entityManager) {
        int mergeableSegmentId = segment.mergeableSegmentId();
        // if the ID of the mergeable segment is lower, it means we're trying to claim the "removable" entry in a merge,
        // meaning there is no risk for a "merge in progress".
        if (mergeableSegmentId > segment.getSegmentId()) {
            // we're just reading for the existence. No interest in claiming or locking
            //This segment should exist
            TokenEntry mergeableSegment = entityManager.find(
                    TokenEntry.class,
                    new TokenEntry.PK(processorName, mergeableSegmentId),
                    LockModeType.NONE
            );
            if (mergeableSegment == null) {
                throw new UnableToClaimTokenException(format(
                        "Unable to claim segment '%s[%s]'. It has been merged with another segment",
                        processorName, segment.getSegmentId()
                ));
            }
        }
        // we're just reading for the existence. No interest in claiming or locking
        // This segment should not exist
        TokenEntry splitSegment = entityManager.find(
                TokenEntry.class,
                new TokenEntry.PK(processorName, segment.splitSegmentId()),
                LockModeType.NONE
        );
        if (splitSegment != null) {
            throw new UnableToClaimTokenException(format(
                    "Unable to claim segment '%s[%s]'. It has been split into two segments",
                    processorName, segment.getSegmentId()
            ));
        }
    }

    @NonNull
    @Override
    public CompletableFuture<String> retrieveStorageIdentifier(@Nullable ProcessingContext context) {
        return entityManagerExecutor(context).apply(em -> {
            try {
                return getConfig(em).get("id");
            } catch (Exception e) {
                throw new UnableToRetrieveIdentifierException(
                        "Exception occurred while trying to retrieve storage identifier",
                        e
                );
            }
        });
    }

    private ConfigToken getConfig(EntityManager em) {
        TokenEntry token = em.find(TokenEntry.class,
                                   new TokenEntry.PK(CONFIG_TOKEN_ID, CONFIG_SEGMENT.getSegmentId()),
                                   LockModeType.NONE);
        if (token == null) {
            token = new TokenEntry(CONFIG_TOKEN_ID,
                                   CONFIG_SEGMENT,
                                   new ConfigToken(Collections.singletonMap("id", UUID.randomUUID().toString())),
                                   converter);
            em.persist(token);
            em.flush();
        }
        return (ConfigToken) token.getToken(converter);
    }

    private TransactionalExecutor<EntityManager> entityManagerExecutor(@Nullable ProcessingContext processingContext) {
        return transactionalExecutorProvider.getTransactionalExecutor(processingContext);
    }

    /**
     * Returns the {@code Converter} used by the {@code TokenStore} to serialize tokens.
     *
     * @return The {@code Converter} used by the {@code TokenStore} to serialize tokens.
     */
    @Internal
    public Converter converter() {
        return converter;
    }
}