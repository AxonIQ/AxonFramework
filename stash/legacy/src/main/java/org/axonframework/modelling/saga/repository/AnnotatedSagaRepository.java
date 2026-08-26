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

package org.axonframework.modelling.saga.repository;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.CollectionUtils;
import org.axonframework.common.lock.LockFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyUnitOfWork;
import org.axonframework.modelling.saga.AnnotatedSaga;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.ResourceInjector;
import org.axonframework.modelling.saga.Saga;
import org.axonframework.modelling.saga.SagaRepository;
import org.axonframework.modelling.saga.metamodel.AnnotationSagaMetaModelFactory;
import org.axonframework.modelling.saga.metamodel.SagaModel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * {@link SagaRepository} implementation extending from the {@link LockingSagaRepository} dealing with annotated Sagas.
 * Will take care of the uniqueness of {@link Saga} instances in the JVM. That means it will prevent multiple instances
 * of the same conceptual Saga (i.e. with same identifier) to exist within the JVM.
 *
 * @param <T> generic type specifying the Saga type stored by this {@link SagaRepository}
 * @author Allard Buijze
 * @since 0.7
 */
public class AnnotatedSagaRepository<T> extends LockingSagaRepository<T> {

    private final Class<T> sagaType;
    private final SagaStore<? super T> sagaStore;
    private final SagaModel<T> sagaModel;
    private final MessageHandlerInterceptorMemberChain<T> chainedInterceptor;
    private final ResourceInjector resourceInjector;

    private final Map<String, AnnotatedSaga<T>> managedSagas;
    private final String unsavedSagasResourceKey;

    /**
     * Instantiate a {@link AnnotatedSagaRepository} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@code sagaType}, {@link SagaStore} and {@link ResourceInjector} are not {@code null}, and
     * will throw an {@link AxonConfigurationException} if any of them is {@code null}. Additionally, the provided
     * builder's goal is to either build a {@link SagaModel} specifying generic {@code T} as the Saga type to be stored
     * or derive it based on the given {@code sagaType}. The same argument applies to the {@link MessageHandlerInterceptorMemberChain}. All
     * Sagas in this repository must be {@code instanceOf} this saga type.
     *
     * @param builder the {@link Builder} used to instantiate a {@link AnnotatedSagaRepository} instance
     */
    protected AnnotatedSagaRepository(Builder<T> builder) {
        super(builder);
        this.sagaType = builder.sagaType;
        this.sagaModel = builder.buildSagaModel();
        this.chainedInterceptor = builder.buildChainedInterceptor();
        this.sagaStore = builder.sagaStore;
        this.resourceInjector = builder.resourceInjector;
        this.managedSagas = new ConcurrentHashMap<>();
        this.unsavedSagasResourceKey = "Repository[" + sagaType.getSimpleName() + "]/UnsavedSagas";
    }

    /**
     * Instantiate a Builder to be able to create an {@link AnnotatedSagaRepository}.
     * <p>
     * The {@link ResourceInjector} is defaulted to a {@link NoResourceInjector}. This Builder either allows directly
     * setting a {@link SagaModel} of generic type {@code T}, or it will generate it based of the required
     * {@code sagaType} field of type {@link Class}. Same for the {@link MessageHandlerInterceptorMemberChain} Thus,
     * either the SagaModel <b>or</b> the {@code sagaType} should be provided. All Saga in this repository must be
     * {@code instanceOf} this saga type. Additionally, the {@code sagaType} and {@link SagaStore} are <b>hard
     * requirements</b> and as such should be provided.
     *
     * @param <T> a generic specifying the Saga type contained in this {@link SagaRepository} implementation
     * @return a Builder to be able to create a {@link AnnotatedSagaRepository}
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    @Override
    public AnnotatedSaga<T> doLoad(String sagaIdentifier) {
        LegacyUnitOfWork<?> unitOfWork = CurrentUnitOfWork.get();
        LegacyUnitOfWork<?> processRoot = unitOfWork.root();

        AnnotatedSaga<T> loadedSaga = managedSagas.computeIfAbsent(sagaIdentifier, id -> {
            AnnotatedSaga<T> result = doLoadSaga(sagaIdentifier);
            if (result != null) {
                processRoot.onCleanup(u -> managedSagas.remove(id));
            }
            return result;
        });

        if (loadedSaga != null && unsavedSagaResource(processRoot).add(sagaIdentifier)) {
            unitOfWork.onPrepareCommit(u -> {
                unsavedSagaResource(processRoot).remove(sagaIdentifier);
                commit(loadedSaga);
            });
        }
        return loadedSaga;
    }

    @Override
    public AnnotatedSaga<T> doCreateInstance(String sagaIdentifier, Supplier<T> sagaFactory) {
        try {
            LegacyUnitOfWork<?> unitOfWork = CurrentUnitOfWork.get(), processRoot = unitOfWork.root();
            T sagaRoot = sagaFactory.get();
            resourceInjector.injectResources(sagaRoot);
            AnnotatedSaga<T> saga =
                    new AnnotatedSaga<>(sagaIdentifier,
                                        Collections.emptySet(),
                                        sagaRoot,
                                        sagaModel,
                                        chainedInterceptor);

            unsavedSagaResource(processRoot).add(sagaIdentifier);
            unitOfWork.onPrepareCommit(u -> {
                if (saga.isActive()) {
                    storeSaga(saga);
                    saga.getAssociationValues().commit();
                    unsavedSagaResource(processRoot).remove(sagaIdentifier);
                }
            });

            managedSagas.put(sagaIdentifier, saga);
            processRoot.onCleanup(u -> managedSagas.remove(sagaIdentifier));
            return saga;
        } catch (Exception e) {
            throw new SagaCreationException("An error occurred while attempting to create a new managed instance", e);
        }
    }

    /**
     * Returns a set of identifiers of sagas that may have changed in the context of the given {@code unitOfWork} and
     * have not been saved yet.
     *
     * @param unitOfWork the unit of work to inspect for unsaved sagas
     * @return set of saga identifiers of unsaved sagas
     */
    protected Set<String> unsavedSagaResource(LegacyUnitOfWork<?> unitOfWork) {
        return unitOfWork.getOrComputeResource(unsavedSagasResourceKey, i -> new HashSet<>());
    }

    /**
     * Commits the given modified {@code saga} to the underlying saga store. If the saga is not active anymore it will
     * be deleted. Otherwise the stored saga and its associations will be updated.
     *
     * @param saga the saga to commit to the store
     */
    protected void commit(AnnotatedSaga<T> saga) {
        if (!saga.isActive()) {
            deleteSaga(saga);
        } else {
            updateSaga(saga);
            saga.getAssociationValues().commit();
        }
    }

    @Override
    public Set<String> find(AssociationValue associationValue) {
        Set<String> sagasFound = new TreeSet<>();
        sagasFound.addAll(managedSagas.values().stream()
                                      .filter(saga -> saga.getAssociationValues().contains(associationValue))
                                      .map(Saga::getSagaIdentifier).collect(Collectors.toList()));
        sagasFound.addAll(sagaStore.findSagas(sagaType, associationValue));
        return sagasFound;
    }

    /**
     * Remove the given saga as well as all known association values pointing to it from the repository. If no such saga
     * exists, nothing happens.
     *
     * @param saga The saga instance to remove from the repository
     */
    protected void deleteSaga(AnnotatedSaga<T> saga) {
        Set<AssociationValue> associationValues = CollectionUtils.merge(
                saga.getAssociationValues().asSet(),
                saga.getAssociationValues().removedAssociations(),
                HashSet::new
        );
        sagaStore.deleteSaga(sagaType, saga.getSagaIdentifier(), associationValues);
    }

    /**
     * Update a stored Saga, by replacing it with the given {@code saga} instance.
     *
     * @param saga The saga that has been modified and needs to be updated in the storage
     */
    protected void updateSaga(AnnotatedSaga<T> saga) {
        sagaStore.updateSaga(sagaType, saga.getSagaIdentifier(), saga.root(), saga.getAssociationValues());
    }

    /**
     * Stores a newly created Saga instance.
     *
     * @param saga The newly created Saga instance to store.
     */
    protected void storeSaga(AnnotatedSaga<T> saga) {
        sagaStore.insertSaga(sagaType, saga.getSagaIdentifier(), saga.root(), saga.getAssociationValues().asSet());
    }

    /**
     * Loads the saga with given {@code sagaIdentifier} from the underlying saga store and returns it as a
     * {@link AnnotatedSaga}. Resources of the saga will be injected using the {@link ResourceInjector} configured with
     * the repository.
     *
     * @param sagaIdentifier the identifier of the saga to load
     * @return AnnotatedSaga instance with the loaded saga
     */
    protected AnnotatedSaga<T> doLoadSaga(String sagaIdentifier) {
        SagaStore.Entry<T> entry = sagaStore.loadSaga(sagaType, sagaIdentifier);
        if (entry != null) {
            T saga = entry.saga();
            resourceInjector.injectResources(saga);
            return new AnnotatedSaga<>(sagaIdentifier, entry.associationValues(), saga, sagaModel, chainedInterceptor);
        }
        return null;
    }

    /**
     * Builder class to instantiate a {@link AnnotatedSagaRepository}.
     * <p>
     * The {@link ResourceInjector} is defaulted to a {@link NoResourceInjector}. This Builder either allows directly
     * setting a {@link SagaModel} of generic type {@code T}, or it will generate one based of the required
     * {@code sagaType} field of type {@link Class}. Thus, either the SagaModel <b>or</b> the {@code sagaType} should be
     * provided. All Sagas in this repository must be {@code instanceOf} this saga type. Additionally, the
     * {@code sagaType} and {@link SagaStore} are <b>hard requirements</b> and as such should be provided.
     *
     * @param <T> a generic specifying the Saga type contained in this {@link SagaRepository} implementation
     */
    public static class Builder<T> extends LockingSagaRepository.Builder<T> {

        private Class<T> sagaType;
        private ParameterResolverFactory parameterResolverFactory;
        private HandlerDefinition handlerDefinition;
        private SagaModel<T> sagaModel;
        private MessageHandlerInterceptorMemberChain<T> interceptorMemberChain;
        private SagaStore<? super T> sagaStore;
        private ResourceInjector resourceInjector = NoResourceInjector.INSTANCE;

        @Override
        public Builder<T> lockFactory(LockFactory lockFactory) {
            super.lockFactory(lockFactory);
            return this;
        }

        /**
         * Sets the {@code sagaType} as a {@link Class}, specifying the type of Saga this {@link SagaRepository} will
         * store. If no {@link SagaModel} is specified directly, a model will be derived from this type.
         *
         * @param sagaType the {@link Class} specifying the type of Saga this {@link SagaRepository} will store
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaType(Class<T> sagaType) {
            assertNonNull(sagaType, "The sagaType may not be null");
            this.sagaType = sagaType;
            return this;
        }

        /**
         * Sets the {@link ParameterResolverFactory} used to resolve parameters for annotated handlers for the given
         * {@code sagaType}. Only used to instantiate a {@link SagaModel} if no SagaModel has been provided directly.
         *
         * @param parameterResolverFactory a {@link ParameterResolverFactory} used to resolve parameters for annotated
         *                                 handlers for the given {@code sagaType}
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> parameterResolverFactory(ParameterResolverFactory parameterResolverFactory) {
            assertNonNull(parameterResolverFactory, "ParameterResolverFactory may not be null");
            this.parameterResolverFactory = parameterResolverFactory;
            return this;
        }

        /**
         * Sets the {@link HandlerDefinition} used to create concrete handlers for the given {@code sagaType}. Only used
         * to instantiate a {@link SagaModel} if no SagaModel has been provided directly.
         *
         * @param handlerDefinition a {@link HandlerDefinition} used to create concrete handlers for the given
         *                          {@code sagaType}.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> handlerDefinition(HandlerDefinition handlerDefinition) {
            assertNonNull(handlerDefinition, "HandlerDefinition may not be null");
            this.handlerDefinition = handlerDefinition;
            return this;
        }

        /**
         * Sets the {@link SagaModel} of generic type {@code T}, describing the structure of the Saga this
         * {@link SagaRepository} implementation will store. If this is not provided directly, the {@code sagaType} will
         * be used to instantiate a SagaModel.
         *
         * @param sagaModel the {@link SagaModel} of generic type {@code T} of the Saga this {@link SagaRepository}
         *                  implementation will store
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaModel(SagaModel<T> sagaModel) {
            assertNonNull(sagaModel, "SagaModel may not be null");
            this.sagaModel = sagaModel;
            return this;
        }

        /**
         * Sets the {@link SagaStore} used to save and load saga instances.
         *
         * @param sagaStore the {@link SagaStore} used to save and load saga instances
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaStore(SagaStore<? super T> sagaStore) {
            assertNonNull(sagaStore, "SagaStore may not be null");
            this.sagaStore = sagaStore;
            return this;
        }

        /**
         * Sets the {@link ResourceInjector} used to initialize {@link Saga} instances after a target instance is
         * created or loaded from the store. Defaults to a {@link NoResourceInjector}.
         *
         * @param resourceInjector a {@link ResourceInjector} used to initialize {@link Saga} instances after a target
         *                         instance is created or loaded from the store
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> resourceInjector(ResourceInjector resourceInjector) {
            assertNonNull(resourceInjector, "ResourceInjector may not be null");
            this.resourceInjector = resourceInjector;
            return this;
        }

        /**
         * Sets the {@link MessageHandlerInterceptorMemberChain} of generic type {@code T}, describing the structure of the Saga this
         * {@link SagaRepository} implementation will store. To be used in handling the event messages.
         *
         * @param interceptorMemberChain the {@link MessageHandlerInterceptorMemberChain} of generic type {@code T} of the Saga this {@link SagaRepository}
         *                  implementation will store
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> interceptorMemberChain(MessageHandlerInterceptorMemberChain<T> interceptorMemberChain) {
            assertNonNull(interceptorMemberChain, "InterceptorMemberChain may not be null");
            this.interceptorMemberChain = interceptorMemberChain;
            return this;
        }

        /**
         * Initializes a {@link AnnotatedSagaRepository} as specified through this Builder.
         *
         * @return a {@link AnnotatedSagaRepository} as specified through this Builder
         */
        public AnnotatedSagaRepository<T> build() {
            return new AnnotatedSagaRepository<>(this);
        }

        /**
         * Instantiate the {@link SagaModel} of generic type {@code T} describing the structure of the Saga this
         * {@link SagaRepository} will store.
         *
         * @return a {@link SagaModel} of generic type {@code T} describing the Saga this {@link SagaRepository}
         * implementation will store
         */
        protected SagaModel<T> buildSagaModel() {
            if (sagaModel == null) {
                return inspectSagaModel();
            } else {
                return sagaModel;
            }
        }

        @SuppressWarnings("Duplicates")
        private SagaModel<T> inspectSagaModel() {
            if (parameterResolverFactory == null && handlerDefinition == null) {
                return new AnnotationSagaMetaModelFactory().modelOf(sagaType);
            } else if (parameterResolverFactory != null && handlerDefinition == null) {
                return new AnnotationSagaMetaModelFactory(parameterResolverFactory).modelOf(sagaType);
            } else {
                return new AnnotationSagaMetaModelFactory(
                        parameterResolverFactory, handlerDefinition
                ).modelOf(sagaType);
            }
        }

        /**
         * Instantiate the {@link MessageHandlerInterceptorMemberChain} of generic type {@code T} as the Saga type to
         * be stored. To be used in handling the event messages.
         *
         * @return a {@link MessageHandlerInterceptorMemberChain} of generic type {@code T}. To be used in handling the
         * event messages.
         */
        protected MessageHandlerInterceptorMemberChain<T> buildChainedInterceptor() {
            if (interceptorMemberChain == null) {
                interceptorMemberChain = inspectChainedInterceptor();
            }
            return interceptorMemberChain;
        }

        private MessageHandlerInterceptorMemberChain<T> inspectChainedInterceptor() {
            if (parameterResolverFactory == null && handlerDefinition == null) {
                return new AnnotationSagaMetaModelFactory().chainedInterceptor(sagaType);
            } else if (parameterResolverFactory != null && handlerDefinition == null) {
                return new AnnotationSagaMetaModelFactory(parameterResolverFactory).chainedInterceptor(sagaType);
            } else {
                return new AnnotationSagaMetaModelFactory(
                        parameterResolverFactory, handlerDefinition
                ).chainedInterceptor(sagaType);
            }
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        @Override
        protected void validate() throws AxonConfigurationException {
            super.validate();
            assertNonNull(sagaType, "The sagaType is a hard requirement and should be provided");
            assertNonNull(sagaStore, "The SagaStore is a hard requirement and should be provided");
        }
    }
}
