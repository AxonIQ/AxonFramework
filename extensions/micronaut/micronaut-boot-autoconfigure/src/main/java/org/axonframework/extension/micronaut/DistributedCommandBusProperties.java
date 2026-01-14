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

package org.axonframework.extension.micronaut;


import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Defines the properties for the Distributed Command Bus, when automatically configured in the Application Context.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@ConfigurationProperties("axon.distributed")
public class DistributedCommandBusProperties {

    /**
     * Enables Distributed Command Bus configuration for this application.
     */
    private boolean enabled = false;

    /**
     * Sets the loadFactor for this node to join with. The loadFactor sets the relative load this node will receive
     * compared to other nodes in the cluster. Defaults to 100.
     */
    private int loadFactor = 100;

    private JGroupsProperties jgroups = new JGroupsProperties();

    private SpringCloudProperties springCloud = new SpringCloudProperties();

    /**
     * Indicates whether the (auto-configuration) of the Distributed Command Bus is enabled.
     *
     * @return whether the (auto-configuration) of the Distributed Command Bus is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables (if {@code true}) or disables (if {@code false}, default) the auto-configuration of a Distributed Command
     * Bus instance in the application context.
     *
     * @param enabled whether to enable Distributed Command Bus configuration.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the load factor for this instance of the Distributed Command Bus (default 100).
     *
     * @return the load factor for this instance of the Distributed Command Bus.
     */
    public int getLoadFactor() {
        return loadFactor;
    }

    /**
     * Sets the load factor for this instance of the Distributed Command Bus (default 100).
     *
     * @param loadFactor the load factor for this instance of the Distributed Command Bus.
     */
    public void setLoadFactor(int loadFactor) {
        this.loadFactor = loadFactor;
    }

    /**
     * Returns the JGroups configuration to use (if JGroups is on the classpath).
     *
     * @return the JGroups configuration to use.
     */
    public JGroupsProperties getJgroups() {
        return jgroups;
    }

    /**
     * Sets the JGroups configuration to use (if JGroups is on the classpath).
     *
     * @param jgroups the JGroups configuration to use.
     */
    public void setJgroups(JGroupsProperties jgroups) {
        this.jgroups = jgroups;
    }

    /**
     * Returns the Spring Cloud configuration to use (if Spring Cloud is on the classpath).
     *
     * @return the Spring Cloud configuration to use.
     */
    public SpringCloudProperties getSpringCloud() {
        return springCloud;
    }

    /**
     * Sets the Spring Cloud configuration to use (if Spring Cloud is on the classpath).
     *
     * @param springCloud the Spring Cloud configuration to use.
     */
    public void setSpringCloud(SpringCloudProperties springCloud) {
        this.springCloud = springCloud;
    }

    /**
     * The JGroups specific configuration for the Distributed Command Bus.
     */
    public static class JGroupsProperties {

        private Gossip gossip = new Gossip();

        /**
         * The name of the JGroups cluster to connect to. Defaults to "Axon".
         */
        private String clusterName = "Axon";

        /**
         * The JGroups configuration file to use. Defaults to a TCP Gossip based configuration.
         */
        private String configurationFile = "default_tcp_gossip.xml";

        /**
         * The address of the network interface to bind JGroups to. Defaults to a global IP address of this node.
         */
        private String bindAddr = "GLOBAL";

        /**
         * Sets the initial port to bind the JGroups connection to. If this port is taken, JGroups will find the next
         * available port.
         */
        private String bindPort = "7800";

        /**
         * Returns the {@link Gossip} configuration in case the Gossip protocol is configured for JGroups (default).
         *
         * @return the {@link Gossip} configuration.
         */
        public Gossip getGossip() {
            return gossip;
        }

        /**
         * Sets the {@link Gossip} configuration in case the Gossip protocol is configured for JGroups (default).
         *
         * @param gossip the {@link Gossip} configuration.
         */
        public void setGossip(Gossip gossip) {
            this.gossip = gossip;
        }

        /**
         * Returns the Cluster Name of the JGroups Cluster to connect with (defaults to "Axon").
         *
         * @return the Cluster Name of the JGroups Cluster to connect with.
         */
        public String getClusterName() {
            return clusterName;
        }

        /**
         * Sets the Cluster Name of the JGroups Cluster to connect with (defaults to "Axon").
         *
         * @param clusterName the Cluster Name of the JGroups Cluster to connect with.
         */
        public void setClusterName(String clusterName) {
            this.clusterName = clusterName;
        }

        /**
         * Returns the path to the configuration file to use to configure the Groups instance.
         *
         * @return the path to the configuration file to use to configure the Groups instance.
         */
        public String getConfigurationFile() {
            return configurationFile;
        }

        /**
         * Sets the path to the configuration file to use to configure the Groups instance. Default to a TCP_GOSSIP
         * based configuration.
         *
         * @param configurationFile the path to the configuration file to use to configure the Groups instance.
         */
        public void setConfigurationFile(String configurationFile) {
            this.configurationFile = configurationFile;
        }

        /**
         * Returns the Address to bind the JGroups client to. Defaults to "GLOBAL", which binds the client to the IP of
         * any network interface connecting to an external network (i.e. other than loopback).
         *
         * @return the Address to bind the JGroups client to.
         */
        public String getBindAddr() {
            return bindAddr;
        }

        /**
         * Sets the Address to bind the JGroups to. Defaults to "GLOBAL", which binds to the IP of any network interface
         * connecting to an external network (i.e. other than loopback).
         *
         * @param bindAddr The address to bind JGroups to.
         */
        public void setBindAddr(String bindAddr) {
            this.bindAddr = bindAddr;
        }

        /**
         * Returns the port to listen to JGroups connections (default 7800). Could be 0, to indicate any free port.
         *
         * @return the port to listen to JGroups connections (default 7800).
         */
        public String getBindPort() {
            return bindPort;
        }

        /**
         * Returns the port to listen to JGroups connections (default 7800). Could be 0, to indicate any free port.
         *
         * @param bindPort the port to listen to JGroups connections (default 7800).
         */
        public void setBindPort(String bindPort) {
            this.bindPort = bindPort;
        }

        public static class Gossip {

            /**
             * Whether to automatically attempt to start a Gossip Routers. The host and port of the Gossip server are
             * taken from the first define host in 'hosts'.
             */
            private boolean autoStart = false;

            /**
             * Defines the hosts of the Gossip Routers to connect to, in the form of host[port],...
             * <p>
             * If autoStart is set to {@code true}, the first host and port are used as bind address and bind port of
             * the Gossip server to start.
             * <p>
             * Defaults to localhost[12001].
             */
            private String hosts = "localhost[12001]";

            /**
             * Whether the embedded {@link Gossip} Server should be automatically started. Defaults to {@code false}.
             *
             * @return whether the embedded {@link Gossip} Server should be automatically started.
             */
            public boolean isAutoStart() {
                return autoStart;
            }

            /**
             * Sets whether the embedded {@link Gossip} Server should be automatically started. Defaults to
             * {@code false}.
             *
             * @param autoStart whether the embedded {@link Gossip} Server should be automatically started.
             */
            public void setAutoStart(boolean autoStart) {
                this.autoStart = autoStart;
            }

            /**
             * Returns the host names and ports of the {@link Gossip} Routers to connect to. Nodes are provided in the
             * format {@code HostA[5555],HostB[5555]}. Defaults to {@code localhost[12001]}.
             *
             * @return the host names and ports of the {@link Gossip} Routers to connect to.
             */
            public String getHosts() {
                return hosts;
            }

            /**
             * Sets the host names and ports of the {@link Gossip} Routers to connect to. Nodes are provided in the
             * format {@code HostA[5555],HostB[5555]}. Defaults to {@code localhost[12001]}.
             *
             * @param hosts the host names and ports of the {@link Gossip} Routers to connect to.
             */
            public void setHosts(String hosts) {
                this.hosts = hosts;
            }
        }
    }

    public static class SpringCloudProperties {

        /**
         * Enable a HTTP GET fallback strategy for retrieving the message routing information from other nodes in a
         * distributed Axon set up. Defaults to "true".
         */
        private boolean fallbackToHttpGet = true;

        /**
         * The URL used to perform HTTP GET requests on for retrieving another nodes message routing information in a
         * distributed Axon set up. Defaults to "/message-routing-information".
         */
        private String fallbackUrl = "/message-routing-information";

        /**
         * Indicates whether to fall back to HTTP GET when retrieving Instance Meta Data from the Discovery Server
         * fails.
         *
         * @return whether to fall back to HTTP GET when retrieving Instance Meta Data from the Discovery Server fails.
         */
        public boolean isFallbackToHttpGet() {
            return fallbackToHttpGet;
        }

        /**
         * Whether to fall back to HTTP GET when retrieving Instance Meta Data from the Discovery Server fails.
         *
         * @param fallbackToHttpGet whether to fall back to HTTP GET when retrieving Instance Meta Data from the
         *                          Discovery Server fails.
         */
        public void setFallbackToHttpGet(boolean fallbackToHttpGet) {
            this.fallbackToHttpGet = fallbackToHttpGet;
        }

        /**
         * Returns the URL relative to the host's root to retrieve Instance Meta Data from. This is also the address
         * where this node will expose its own Instance Meta Data.
         *
         * @return the URL relative to the host's root to retrieve Instance Meta Data from.
         */
        public String getFallbackUrl() {
            return fallbackUrl;
        }

        /**
         * Sets the URL relative to the host's root to retrieve Instance Meta Data from. This is also the address where
         * this node will expose its own Instance Meta Data.
         *
         * @param fallbackUrl the URL relative to the host's root to retrieve Instance Meta Data from.
         */
        public void setFallbackUrl(String fallbackUrl) {
            this.fallbackUrl = fallbackUrl;
        }
    }
}
