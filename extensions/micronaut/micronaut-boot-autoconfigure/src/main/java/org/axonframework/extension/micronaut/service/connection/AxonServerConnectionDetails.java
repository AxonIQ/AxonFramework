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

package org.axonframework.extension.micronaut.service.connection;

import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;

/**
 * ConnectionDetails implementation carrying the connection details for an Axon Server instance.
 * <p>
 * Note that this is not a replacement for full connectivity configuration. ConnectionDetails are designed to only carry
 * the endpoint at which a node runs.
 *
 * @author Allard Buijze
 * @since 4.9.0
 */
public interface AxonServerConnectionDetails extends ConnectionDetails {

    /**
     * The addresses of the routing servers to use to connect to an Axon Server cluster. The string should contain a
     * comma separated list of AxonServer servers. Each element is hostname or hostname:grpcPort. When no grpcPort is
     * specified, default port 8124 is used.
     *
     * @return a string containing the addresses of the routing servers to connect with
     */
    String routingServers();
}
