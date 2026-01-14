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

package org.axonframework.extension.micronaut.util;

import io.axoniq.axonserver.grpc.control.ClientIdentification;
import io.axoniq.axonserver.grpc.control.NodeInfo;
import io.axoniq.axonserver.grpc.control.PlatformInboundInstruction;
import io.axoniq.axonserver.grpc.control.PlatformInfo;
import io.axoniq.axonserver.grpc.control.PlatformOutboundInstruction;
import io.axoniq.axonserver.grpc.control.PlatformServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

import static org.axonframework.extension.micronaut.util.GrpcServerStub.DEFAULT_HOST;

/**
 * Stub {@link io.axoniq.axonserver.grpc.control.PlatformServiceGrpc.PlatformServiceImplBase} implementation used to
 * replace basic connection management.
 * <p>
 * Can be used to spoof existences of an Axon Server instance for tests, without actually spinning up Axon Sever.
 *
 * @author Marc Gathier
 * @author Steven van Beelen
 * @since 4.0
 */
public class ControlChannelStub extends PlatformServiceGrpc.PlatformServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());



    private final int grpcPort;
    private final String localhost;

    /**
     * Construct a Control channel stub using the {@link GrpcServerStub#DEFAULT_HOST} and the given {@code port} to
     * connect.
     *
     * @param grpcPort The gRPC port to connect with.
     */
    public ControlChannelStub(int grpcPort) {
        this(DEFAULT_HOST, grpcPort);
    }

    /**
     * Construct a Control channel stub using the given {@code host} and {@code port} to connect.
     *
     * @param host     The host to connect with.
     * @param grpcPort The gRPC to connect with.
     */
    public ControlChannelStub(String host, int grpcPort) {
        this.grpcPort = grpcPort;
        this.localhost = host;
    }

    @Override
    public void getPlatformServer(ClientIdentification request, StreamObserver<PlatformInfo> responseObserver) {
        responseObserver.onNext(PlatformInfo.newBuilder()
                                            .setPrimary(NodeInfo.newBuilder()
                                                                .setGrpcPort(grpcPort)
                                                                .setHostName(localhost)
                                                                .setNodeName("stub-node-name")
                                                                .setVersion(0)
                                                                .build())
                                            .build());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<PlatformInboundInstruction> openStream(
            StreamObserver<PlatformOutboundInstruction> responseObserver
    ) {
        return new StreamObserver<PlatformInboundInstruction>() {
            @Override
            public void onNext(PlatformInboundInstruction platformInboundInstruction) {
                if (platformInboundInstruction.hasRegister()) {
                    ClientIdentification register = platformInboundInstruction.getRegister();
                    logger.debug("The following client is registering: [{}]", register);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.debug("Closing channel as result of on error invocation.", throwable);
            }

            @Override
            public void onCompleted() {
                logger.debug("Closing channel as result of on completed invocation.");
                responseObserver.onCompleted();
            }
        };
    }
}
