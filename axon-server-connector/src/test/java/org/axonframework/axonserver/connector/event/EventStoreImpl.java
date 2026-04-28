/*
 * Copyright (c) 2010-2022. Axon Framework
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

package org.axonframework.axonserver.connector.event;

import io.axoniq.axonserver.grpc.event.Confirmation;
import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.EventStoreGrpc;
import io.axoniq.axonserver.grpc.event.EventWithToken;
import io.axoniq.axonserver.grpc.event.GetAggregateEventsRequest;
import io.axoniq.axonserver.grpc.event.GetAggregateSnapshotsRequest;
import io.axoniq.axonserver.grpc.event.GetEventsRequest;
import io.axoniq.axonserver.grpc.event.QueryEventsRequest;
import io.axoniq.axonserver.grpc.event.QueryEventsResponse;
import io.axoniq.axonserver.grpc.event.ReadHighestSequenceNrRequest;
import io.axoniq.axonserver.grpc.event.ReadHighestSequenceNrResponse;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * EventStore implementation used for testing purposes. It makes it easier to verify events, snapshots and requests
 * within EventStore.
 *
 * @author Marc Gathier
 */
public class EventStoreImpl extends EventStoreGrpc.EventStoreImplBase {

    private final List<Event> events = new LinkedList<>();
    private final Map<String, Event> snapshots = new HashMap<>();
    private final List<GetEventsRequest> getEventsRequests = new ArrayList<>();
    private final List<QueryEventsRequest> queryEventsRequests = new ArrayList<>();

    private Predicate<GetAggregateSnapshotsRequest> snapshotFailure = snapshotRequest -> false;
    private Supplier<Exception> snapshotFailureException = IllegalStateException::new;

    public List<GetEventsRequest> getEventsRequests() {
        return getEventsRequests;
    }

    public List<QueryEventsRequest> getQueryEventsRequests() {
        return queryEventsRequests;
    }

    /**
     * Sets a {@link Predicate} to return true if a snapshot {@link Event} should fail. Defaults with returning {@code
     * false}.
     *
     * @param snapshotFailure the {@link Predicate} that should return {@code true} to fail for reading a snapshot
     *                        event
     */
    public void setSnapshotFailure(Predicate<GetAggregateSnapshotsRequest> snapshotFailure) {
        this.snapshotFailure = snapshotFailure;
    }

    /**
     * Sets the {@link Exception} to throw if the {@code snapshotFailure} returns {@code true}. Defaults to throwing an
     * {@link IllegalStateException}.
     *
     * @param snapshotFailureException a supplier of an {@link Exception} to throw if the {@code snapshotFailure}
     *                                 returns {@code true}
     */
    public void setSnapshotFailureException(Supplier<Exception> snapshotFailureException) {
        this.snapshotFailureException = snapshotFailureException;
    }

    @Override
    public StreamObserver<Event> appendEvent(StreamObserver<Confirmation> responseObserver) {
        return new StreamObserver<Event>() {

            private final List<Event> eventsInTx = new LinkedList<>();

            @Override
            public void onNext(Event event) {
                eventsInTx.add(event);
            }

            @Override
            public void onError(Throwable throwable) {

            }

            @Override
            public void onCompleted() {
                events.addAll(eventsInTx);
                responseObserver.onNext(Confirmation.newBuilder().setSuccess(true).build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void appendSnapshot(Event request, StreamObserver<Confirmation> responseObserver) {
        snapshots.put(request.getAggregateIdentifier(), request.toBuilder().setSnapshot(true).build());
        responseObserver.onNext(Confirmation.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void listAggregateEvents(GetAggregateEventsRequest request, StreamObserver<Event> responseObserver) {
        Event snapshot = request.getAllowSnapshots() ? snapshots.get(request.getAggregateId()) : null;
        if (snapshot != null) {
            responseObserver.onNext(snapshot);
        }
        long maxSeq = request.getMaxSequence() > 0 ? request.getMaxSequence() : Long.MAX_VALUE;
        events.stream()
              .filter(e -> e.getAggregateIdentifier().equals(request.getAggregateId()))
              .filter(e -> e.getAggregateSequenceNumber() >= request.getInitialSequence()
                      && e.getAggregateSequenceNumber() <= maxSeq
                      && (snapshot == null || snapshot.getAggregateSequenceNumber() < e.getAggregateSequenceNumber()))
              .forEach(responseObserver::onNext);
        responseObserver.onCompleted();
    }

    @Override
    public void listAggregateSnapshots(GetAggregateSnapshotsRequest request, StreamObserver<Event> responseObserver) {
        if (snapshotFailure.test(request)) {
            responseObserver.onError(snapshotFailureException.get());
            return;
        }

        Event snapshot = snapshots.get(request.getAggregateId());
        if (snapshot != null) {
            responseObserver.onNext(snapshot);
        }
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<GetEventsRequest> listEvents(StreamObserver<EventWithToken> responseObserver) {
        return new StreamObserver<GetEventsRequest>() {

            private final AtomicLong permits = new AtomicLong();
            private Iterator<Event> eventsAtRead;
            private long token;

            @Override
            public void onNext(GetEventsRequest getEventsRequest) {
                getEventsRequests.add(getEventsRequest);
                long oldPermits = permits.getAndAdd(getEventsRequest.getNumberOfPermits());
                if (token == 0) {
                    token = getEventsRequest.getTrackingToken();
                }
                if (oldPermits == 0 && getEventsRequest.getNumberOfPermits() > 0) {
                    if (eventsAtRead == null) {
                        eventsAtRead = new ArrayList<>(events).iterator();
                        for (long i = 0; i < getEventsRequest.getTrackingToken(); i++) {
                            eventsAtRead.next();
                        }
                    }
                    do {
                        responseObserver.onNext(EventWithToken.newBuilder()
                                                              .setEvent(eventsAtRead.next())
                                                              .setToken(token++).build());
                    } while (eventsAtRead.hasNext() && permits.decrementAndGet() > 0);
                }
            }

            @Override
            public void onError(Throwable throwable) {

            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<QueryEventsRequest> queryEvents(StreamObserver<QueryEventsResponse> responseObserver) {
        return new StreamObserver<QueryEventsRequest>() {
            @Override
            public void onNext(QueryEventsRequest value) {
                queryEventsRequests.add(value);
            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void readHighestSequenceNr(ReadHighestSequenceNrRequest request,
                                      StreamObserver<ReadHighestSequenceNrResponse> responseObserver) {
        long highest = events.stream()
                             .filter(e -> e.getAggregateIdentifier().equals(request.getAggregateId()))
                             .mapToLong(Event::getAggregateSequenceNumber)
                             .max()
                             .orElse(-1L);
        responseObserver.onNext(ReadHighestSequenceNrResponse.newBuilder().setToSequenceNr(highest).build());
        responseObserver.onCompleted();
    }
}
