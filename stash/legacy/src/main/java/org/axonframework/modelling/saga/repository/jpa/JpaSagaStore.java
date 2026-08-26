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

package org.axonframework.modelling.saga.repository.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * JPA implementation of the {@link SagaStore}. It uses an {@link EntityManager} to persist the actual saga in a backing
 * store in serialized form.
 * <p/>
 * After each operation that modified the backing store, {@link EntityManager#flush()} is invoked to ensure the store
 * contains the last modifications. To override this behavior, see {@link #setUseExplicitFlush(boolean)}
 *
 * @author Allard Buijze
 * @since 3.0
 */
public class JpaSagaStore implements SagaStore<Object> {

    private static final Logger logger = LoggerFactory.getLogger(JpaSagaStore.class);

    private static final String SAGA_ID_PARAM = "sagaId";
    private static final String SAGA_TYPE_PARAM = "sagaType";

    // Saga Queries, non-final to inject the return type and table name.
    private final String LOAD_SAGA_QUERY =
            "SELECT new " + serializedObjectType().getName() + "(" +
                    "se.serializedSaga, se.sagaType, se.revision) " + "FROM " + sagaEntryEntityName() + " se " +
                    "WHERE se.sagaId = :sagaId";


    private final String DELETE_SAGA_QUERY = "DELETE FROM " + sagaEntryEntityName() + " se WHERE se.sagaId = :id";

    private final String UPDATE_SAGA_QUERY =
            "UPDATE " + sagaEntryEntityName() + " s SET s.serializedSaga = :serializedSaga, s.revision = :revision " +
                    "WHERE s.sagaId = :sagaId";

    // Association Queries
    private static final String DELETE_ASSOCIATION_QUERY =
            "DELETE FROM AssociationValueEntry ae WHERE ae.associationKey = :associationKey " +
                    "AND ae.associationValue = :associationValue AND ae.sagaType = :sagaType " +
                    "AND ae.sagaId = :sagaId";

    private static final String FIND_ASSOCIATION_IDS_QUERY =
            "SELECT ae.sagaId FROM AssociationValueEntry ae WHERE ae.associationKey = :associationKey " +
                    "AND ae.associationValue = :associationValue AND ae.sagaType = :sagaType";

    private static final String FIND_ASSOCIATIONS_QUERY =
            "SELECT ae FROM AssociationValueEntry ae WHERE ae.sagaType = :sagaType AND ae.sagaId = :sagaId";

    private static final String DELETE_ASSOCIATIONS_QUERY =
            "DELETE FROM AssociationValueEntry ae WHERE ae.sagaId = :sagaId";

    private static final String LOAD_SAGA_NAMED_QUERY = "LOAD_SAGA_NAMED_QUERY";
    private static final String DELETE_ASSOCIATION_NAMED_QUERY = "DELETE_ASSOCIATION_NAMED_QUERY";
    private static final String FIND_ASSOCIATION_IDS_NAMED_QUERY = "FIND_ASSOCIATION_IDS_NAMED_QUERY";
    private static final String FIND_ASSOCIATIONS_NAMED_QUERY = "FIND_ASSOCIATIONS_NAMED_QUERY";
    private static final String DELETE_ASSOCIATIONS_NAMED_QUERY = "DELETE_ASSOCIATIONS_NAMED_QUERY";
    private static final String DELETE_SAGA_NAMED_QUERY = "DELETE_SAGA_NAMED_QUERY";
    private static final String UPDATE_SAGA_NAMED_QUERY = "UPDATE_SAGA_NAMED_QUERY";

    private final EntityManagerProvider entityManagerProvider;
    private final Serializer serializer;

    private volatile boolean useExplicitFlush = true;

    /**
     * Instantiate a {@link JpaSagaStore} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link EntityManagerProvider} and {@link Serializer} are not {@code null}, and will throw an
     * {@link AxonConfigurationException} if any of them is {@code null}.
     *
     * @param builder the {@link Builder} used to instantiate a {@link JpaSagaStore} instance
     */
    protected JpaSagaStore(Builder builder) {
        builder.validate();
        this.entityManagerProvider = builder.entityManagerProvider;
        this.serializer = builder.serializer.get();
        addNamedQueriesTo(this.entityManagerProvider.getEntityManager());
    }

    /**
     * Instantiate a Builder to be able to create a {@link JpaSagaStore}.
     * <p>
     * The {@link EntityManagerProvider} and {@link Serializer} are <b>hard requirements</b> and as such should be
     * provided.
     *
     * @return a Builder to be able to create a {@link JpaSagaStore}
     */
    public static Builder builder() {
        return new Builder();
    }

    private void addNamedQueriesTo(EntityManager entityManager) {
        EntityManagerFactory entityManagerFactory = entityManager.getEntityManagerFactory();
        entityManagerFactory.addNamedQuery(LOAD_SAGA_NAMED_QUERY, entityManager.createQuery(LOAD_SAGA_QUERY));
        entityManagerFactory.addNamedQuery(
                DELETE_ASSOCIATION_NAMED_QUERY, entityManager.createQuery(DELETE_ASSOCIATION_QUERY)
        );
        entityManagerFactory.addNamedQuery(
                FIND_ASSOCIATION_IDS_NAMED_QUERY, entityManager.createQuery(FIND_ASSOCIATION_IDS_QUERY));
        entityManagerFactory.addNamedQuery(
                DELETE_ASSOCIATIONS_NAMED_QUERY, entityManager.createQuery(DELETE_ASSOCIATIONS_QUERY)
        );
        entityManagerFactory.addNamedQuery(
                FIND_ASSOCIATIONS_NAMED_QUERY, entityManager.createQuery(FIND_ASSOCIATIONS_QUERY)
        );
        entityManagerFactory.addNamedQuery(DELETE_SAGA_NAMED_QUERY, entityManager.createQuery(DELETE_SAGA_QUERY));
        entityManagerFactory.addNamedQuery(UPDATE_SAGA_NAMED_QUERY, entityManager.createQuery(UPDATE_SAGA_QUERY));
    }

    @Override
    public <S> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();

        final Class<? extends SimpleSerializedObject<?>> serializedObjectType = serializedObjectType();

        List<? extends SimpleSerializedObject<?>> serializedSagaList =
                entityManager.createNamedQuery(LOAD_SAGA_NAMED_QUERY, serializedObjectType)
                             .setParameter(SAGA_ID_PARAM, sagaIdentifier)
                             .setMaxResults(1)
                             .getResultList();
        if (serializedSagaList == null || serializedSagaList.isEmpty()) {
            return null;
        }

        final SimpleSerializedObject<?> serializedSaga = serializedSagaList.get(0);
        S loadedSaga = serializer.deserialize(serializedSaga);
        Set<AssociationValue> associationValues = loadAssociationValues(entityManager, sagaType, sagaIdentifier);
        if (logger.isDebugEnabled()) {
            logger.debug("Loaded saga id [{}] of type [{}]", sagaIdentifier, serializedSaga.getType().getName());
        }
        return new EntryImpl<>(associationValues, loadedSaga);
    }

    /**
     * Loads the {@link AssociationValue association values} of the saga with given {@code sagaIdentifier} and
     * {@code sagaType}.
     *
     * @param entityManager  the entity manager instance to use for the query
     * @param sagaType       the saga instance class
     * @param sagaIdentifier the saga identifier
     * @return the associations of the given saga
     */
    protected Set<AssociationValue> loadAssociationValues(EntityManager entityManager, Class<?> sagaType,
                                                          String sagaIdentifier) {
        List<AssociationValueEntry> associationValueEntries =
                entityManager.createNamedQuery(FIND_ASSOCIATIONS_NAMED_QUERY, AssociationValueEntry.class)
                             .setParameter(SAGA_TYPE_PARAM, getSagaTypeName(sagaType))
                             .setParameter(SAGA_ID_PARAM, sagaIdentifier)
                             .getResultList();

        return associationValueEntries.stream().map(AssociationValueEntry::getAssociationValue)
                                      .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Removes the given {@code associationValue} of the saga with given {@code sagaIdentifier} and {@code sagaType}.
     *
     * @param entityManager    the entity manager instance to use for the query
     * @param sagaType         the saga instance class
     * @param sagaIdentifier   the saga identifier
     * @param associationValue the association value to remove
     */
    protected void removeAssociationValue(EntityManager entityManager, Class<?> sagaType, String sagaIdentifier,
                                          AssociationValue associationValue) {
        int updateCount = entityManager.createNamedQuery(DELETE_ASSOCIATION_NAMED_QUERY)
                                       .setParameter("associationKey", associationValue.getKey())
                                       .setParameter("associationValue", associationValue.getValue())
                                       .setParameter(SAGA_TYPE_PARAM, getSagaTypeName(sagaType))
                                       .setParameter(SAGA_ID_PARAM, sagaIdentifier)
                                       .executeUpdate();
        if (updateCount == 0 && logger.isWarnEnabled()) {
            logger.warn("Wanted to remove association value, but it was already gone: sagaId= {}, key={}, value={}",
                        sagaIdentifier, associationValue.getKey(), associationValue.getValue());
        }
    }

    /**
     * Stores the given {@code associationValue} of the saga with given {@code sagaIdentifier} and {@code sagaType}.
     *
     * @param entityManager    the entity manager instance to use for the query
     * @param sagaType         the saga instance class
     * @param sagaIdentifier   the saga identifier
     * @param associationValue the association value to add
     */
    protected void storeAssociationValue(EntityManager entityManager, Class<?> sagaType, String sagaIdentifier,
                                         AssociationValue associationValue) {
        entityManager.persist(new AssociationValueEntry(getSagaTypeName(sagaType), sagaIdentifier, associationValue));
    }

    private String getSagaTypeName(Class<?> sagaType) {
        return serializer.typeForClass(sagaType).getName();
    }

    @Override
    public Set<String> findSagas(Class<?> sagaType, AssociationValue associationValue) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        List<String> entries = entityManager.createNamedQuery(FIND_ASSOCIATION_IDS_NAMED_QUERY, String.class)
                                            .setParameter("associationKey", associationValue.getKey())
                                            .setParameter("associationValue", associationValue.getValue())
                                            .setParameter(SAGA_TYPE_PARAM, getSagaTypeName(sagaType)).getResultList();
        return new TreeSet<>(entries);
    }

    @Override
    public void deleteSaga(Class<?> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        try {
            entityManager.createNamedQuery(DELETE_ASSOCIATIONS_NAMED_QUERY)
                         .setParameter(SAGA_ID_PARAM, sagaIdentifier)
                         .executeUpdate();
            entityManager.createNamedQuery(DELETE_SAGA_NAMED_QUERY)
                         .setParameter("id", sagaIdentifier)
                         .executeUpdate();
        } catch (EntityNotFoundException e) {
            logger.info("Could not delete {} {}, it appears to have already been deleted.",
                        sagaEntryEntityName(),
                        sagaIdentifier);
        }
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    @Override
    public void updateSaga(Class<?> sagaType, String sagaIdentifier, Object saga, AssociationValues associationValues) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        SagaEntry<?> entry = createSagaEntry(saga, sagaIdentifier, serializer);

        if (logger.isDebugEnabled()) {
            logger.debug("Updating saga id {} as {}", sagaIdentifier, serializedSagaAsString(entry));
        }
        int updateCount = entityManager.createNamedQuery(UPDATE_SAGA_NAMED_QUERY)
                                       .setParameter("serializedSaga", entry.getSerializedSaga())
                                       .setParameter("revision", entry.getRevision())
                                       .setParameter(SAGA_ID_PARAM, entry.getSagaId())
                                       .executeUpdate();
        for (AssociationValue associationValue : associationValues.addedAssociations()) {
            storeAssociationValue(entityManager, sagaType, sagaIdentifier, associationValue);
        }
        for (AssociationValue associationValue : associationValues.removedAssociations()) {
            removeAssociationValue(entityManager, sagaType, sagaIdentifier, associationValue);
        }
        if (updateCount == 0) {
            logger.warn("Expected to be able to update a Saga instance, but no rows were found.");
        }
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    private String serializedSagaAsString(SagaEntry<?> entry) {
        if (entry != null) {
            return new String(entry.getSerializedSaga(), StandardCharsets.UTF_8);
        } else {
            return "[Custom conversion format (not visible)]";
        }
    }

    @Override
    public void insertSaga(Class<?> sagaType, String sagaIdentifier, Object saga,
                           Set<AssociationValue> associationValues) {
        EntityManager entityManager = entityManagerProvider.getEntityManager();
        SagaEntry<?> entry = createSagaEntry(saga, sagaIdentifier, serializer);
        entityManager.persist(entry);
        for (AssociationValue associationValue : associationValues) {
            storeAssociationValue(entityManager, sagaType, sagaIdentifier, associationValue);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Storing saga id {} as {}", sagaIdentifier, serializedSagaAsString(entry));
        }
        if (useExplicitFlush) {
            entityManager.flush();
        }
    }

    /**
     * Sets whether or not to do an explicit {@link EntityManager#flush()} after each data modifying operation on the
     * backing storage. Default to {@code true}
     *
     * @param useExplicitFlush {@code true} to force flush, {@code false} otherwise.
     */
    public void setUseExplicitFlush(boolean useExplicitFlush) {
        this.useExplicitFlush = useExplicitFlush;
    }

    /**
     * Intended for clients to override. Defaults to {@link SagaEntry}.
     *
     * @param sagaIdentifier The identifier of the Saga
     * @param saga           The Saga instance
     * @param serializer     The serializer to serialize to the {@link SagaEntry#getSerializedSaga()}
     * @return An instanceof @{@link SagaEntry}
     */
    protected SagaEntry<?> createSagaEntry(Object saga, String sagaIdentifier, Serializer serializer) {
        return new SagaEntry<>(saga, sagaIdentifier, serializer);
    }

    /**
     * Intended for clients to override. Defaults to 'SagaEntry'.
     *
     * @return the name of the Jpa event entity
     */
    protected String sagaEntryEntityName() {
        return SagaEntry.class.getSimpleName();
    }

    /**
     * Intended for clients to override. Defaults to {@link SerializedSaga#getClass() SerialzedSaga.class}
     *
     * @return the serialized object type of the Saga this {@link SagaStore} stores
     */
    protected Class<? extends SimpleSerializedObject<?>> serializedObjectType() {
        return SerializedSaga.class;
    }

    /**
     * Builder class to instantiate a {@link JpaSagaStore}.
     * <p>
     * The {@link EntityManagerProvider} and {@link Serializer} are <b>hard requirements</b> and as such should be
     * provided.
     */
    public static class Builder {

        private EntityManagerProvider entityManagerProvider;
        private Supplier<Serializer> serializer;

        /**
         * Sets the {@link EntityManagerProvider} which provides the {@link EntityManager} used to access the underlying
         * database.
         *
         * @param entityManagerProvider a {@link EntityManagerProvider} which provides the {@link EntityManager} used to
         *                              access the underlying database
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder entityManagerProvider(EntityManagerProvider entityManagerProvider) {
            assertNonNull(entityManagerProvider, "EntityManagerProvider may not be null");
            this.entityManagerProvider = entityManagerProvider;
            return this;
        }

        /**
         * Sets the {@link Serializer} used to de-/serialize a Saga instance.
         *
         * @param serializer a {@link Serializer} used to de-/serialize a Saga instance
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder serializer(Serializer serializer) {
            assertNonNull(serializer, "Serializer may not be null");
            this.serializer = () -> serializer;
            return this;
        }

        /**
         * Initializes a {@link JpaSagaStore} as specified through this Builder.
         *
         * @return a {@link JpaSagaStore} as specified through this Builder
         */
        public JpaSagaStore build() {
            return new JpaSagaStore(this);
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() throws AxonConfigurationException {
            assertNonNull(entityManagerProvider,
                          "The EntityManagerProvider is a hard requirement and should be provided");
            assertNonNull(serializer, "The Serializer is a hard requirement and should be provided");
        }
    }

    private static class EntryImpl<S> implements Entry<S> {

        private final Set<AssociationValue> associationValues;
        private final S loadedSaga;

        public EntryImpl(Set<AssociationValue> associationValues, S loadedSaga) {
            this.associationValues = associationValues;
            this.loadedSaga = loadedSaga;
        }

        @Override
        public Set<AssociationValue> associationValues() {
            return associationValues;
        }

        @Override
        public S saga() {
            return loadedSaga;
        }
    }
}
