/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.network;

import org.elasticsearch.action.support.replication.ReplicationTask;
import org.elasticsearch.client.transport.TransportClientNodesService;
import org.elasticsearch.client.transport.support.TransportProxyClient;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.allocation.command.AllocateEmptyPrimaryAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.AllocateReplicaAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.AllocateStalePrimaryAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.AllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.AllocationCommandRegistry;
import org.elasticsearch.cluster.routing.allocation.command.CancelAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.MoveAllocationCommand;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.ExtensionPoint;
import org.elasticsearch.http.HttpServer;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.http.netty.NettyHttpServerTransport;
import org.elasticsearch.tasks.RawTaskStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.local.LocalTransport;
import org.elasticsearch.transport.netty.NettyTransport;

/**
 * A module to handle registering and binding all network related classes.
 */
public class NetworkModule extends AbstractModule {

    public static final String TRANSPORT_TYPE_KEY = "transport.type";
    public static final String TRANSPORT_SERVICE_TYPE_KEY = "transport.service.type";
    public static final String HTTP_TYPE_KEY = "http.type";
    public static final String LOCAL_TRANSPORT = "local";
    public static final String NETTY_TRANSPORT = "netty";

    public static final Setting<String> HTTP_TYPE_SETTING = Setting.simpleString(HTTP_TYPE_KEY, Property.NodeScope);
    public static final Setting<Boolean> HTTP_ENABLED = Setting.boolSetting("http.enabled", true, Property.NodeScope);
    public static final Setting<String> TRANSPORT_SERVICE_TYPE_SETTING =
        Setting.simpleString(TRANSPORT_SERVICE_TYPE_KEY, Property.NodeScope);
    public static final Setting<String> TRANSPORT_TYPE_SETTING = Setting.simpleString(TRANSPORT_TYPE_KEY, Property.NodeScope);

    private final NetworkService networkService;
    private final Settings settings;
    private final boolean transportClient;

    private final AllocationCommandRegistry allocationCommandRegistry = new AllocationCommandRegistry();
    private final ExtensionPoint.SelectedType<TransportService> transportServiceTypes = new ExtensionPoint.SelectedType<>("transport_service", TransportService.class);
    private final ExtensionPoint.SelectedType<Transport> transportTypes = new ExtensionPoint.SelectedType<>("transport", Transport.class);
    private final ExtensionPoint.SelectedType<HttpServerTransport> httpTransportTypes = new ExtensionPoint.SelectedType<>("http_transport", HttpServerTransport.class);
    private final NamedWriteableRegistry namedWriteableRegistry;

    /**
     * Creates a network module that custom networking classes can be plugged into.
     *
     * @param networkService A constructed network service object to bind.
     * @param settings The settings for the node
     * @param transportClient True if only transport classes should be allowed to be registered, false otherwise.
     * @param namedWriteableRegistry registry for named writeables for use during streaming
     */
    public NetworkModule(NetworkService networkService, Settings settings, boolean transportClient, NamedWriteableRegistry namedWriteableRegistry) {
        this.networkService = networkService;
        this.settings = settings;
        this.transportClient = transportClient;
        this.namedWriteableRegistry = namedWriteableRegistry;
        registerTransportService(NETTY_TRANSPORT, TransportService.class);
        registerTransport(LOCAL_TRANSPORT, LocalTransport.class);
        registerTransport(NETTY_TRANSPORT, NettyTransport.class);
        registerTaskStatus(ReplicationTask.Status.NAME, ReplicationTask.Status::new);
        registerTaskStatus(RawTaskStatus.NAME, RawTaskStatus::new);
        registerBuiltinAllocationCommands();

        if (transportClient == false) {
            registerHttpTransport(NETTY_TRANSPORT, NettyHttpServerTransport.class);
        }
    }

    public boolean isTransportClient() {
        return transportClient;
    }

    /** Adds a transport service implementation that can be selected by setting {@link #TRANSPORT_SERVICE_TYPE_KEY}. */
    public void registerTransportService(String name, Class<? extends TransportService> clazz) {
        transportServiceTypes.registerExtension(name, clazz);
    }

    /** Adds a transport implementation that can be selected by setting {@link #TRANSPORT_TYPE_KEY}. */
    public void registerTransport(String name, Class<? extends Transport> clazz) {
        transportTypes.registerExtension(name, clazz);
    }

    /** Adds an http transport implementation that can be selected by setting {@link #HTTP_TYPE_KEY}. */
    // TODO: we need another name than "http transport"....so confusing with transportClient...
    public void registerHttpTransport(String name, Class<? extends HttpServerTransport> clazz) {
        if (transportClient) {
            throw new IllegalArgumentException("Cannot register http transport " + clazz.getName() + " for transport client");
        }
        httpTransportTypes.registerExtension(name, clazz);
    }

    public void registerTaskStatus(String name, Writeable.Reader<? extends Task.Status> reader) {
        namedWriteableRegistry.register(Task.Status.class, name, reader);
    }

    /**
     * Register an allocation command.
     * <p>
     * This lives here instead of the more aptly named ClusterModule because the Transport client needs these to be registered.
     * </p>
     * @param reader the reader to read it from a stream
     * @param parser the parser to read it from XContent
     * @param commandName the names under which the command should be parsed. The {@link ParseField#getPreferredName()} is special because
     *        it is the name under which the command's reader is registered.
     */
    private <T extends AllocationCommand> void registerAllocationCommand(Writeable.Reader<T> reader, AllocationCommand.Parser<T> parser,
            ParseField commandName) {
        allocationCommandRegistry.register(parser, commandName);
        namedWriteableRegistry.register(AllocationCommand.class, commandName.getPreferredName(), reader);
    }

    /**
     * The registry of allocation command parsers.
     */
    public AllocationCommandRegistry getAllocationCommandRegistry() {
        return allocationCommandRegistry;
    }

    @Override
    protected void configure() {
        bind(NetworkService.class).toInstance(networkService);
        bind(NamedWriteableRegistry.class).toInstance(namedWriteableRegistry);

        transportServiceTypes.bindType(binder(), settings, TRANSPORT_SERVICE_TYPE_KEY, NETTY_TRANSPORT);
        String defaultTransport = DiscoveryNode.isLocalNode(settings) ? LOCAL_TRANSPORT : NETTY_TRANSPORT;
        transportTypes.bindType(binder(), settings, TRANSPORT_TYPE_KEY, defaultTransport);

        if (transportClient) {
            bind(TransportProxyClient.class).asEagerSingleton();
            bind(TransportClientNodesService.class).asEagerSingleton();
        } else {
            if (HTTP_ENABLED.get(settings)) {
                bind(HttpServer.class).asEagerSingleton();
                httpTransportTypes.bindType(binder(), settings, HTTP_TYPE_SETTING.getKey(), NETTY_TRANSPORT);
            }
            // Bind the AllocationCommandRegistry so RestClusterRerouteAction can get it.
            bind(AllocationCommandRegistry.class).toInstance(allocationCommandRegistry);
        }
    }

    private void registerBuiltinAllocationCommands() {
        registerAllocationCommand(CancelAllocationCommand::new, CancelAllocationCommand::fromXContent,
                CancelAllocationCommand.COMMAND_NAME_FIELD);
        registerAllocationCommand(MoveAllocationCommand::new, MoveAllocationCommand::fromXContent,
                MoveAllocationCommand.COMMAND_NAME_FIELD);
        registerAllocationCommand(AllocateReplicaAllocationCommand::new, AllocateReplicaAllocationCommand::fromXContent,
                AllocateReplicaAllocationCommand.COMMAND_NAME_FIELD);
        registerAllocationCommand(AllocateEmptyPrimaryAllocationCommand::new, AllocateEmptyPrimaryAllocationCommand::fromXContent,
                AllocateEmptyPrimaryAllocationCommand.COMMAND_NAME_FIELD);
        registerAllocationCommand(AllocateStalePrimaryAllocationCommand::new, AllocateStalePrimaryAllocationCommand::fromXContent,
                AllocateStalePrimaryAllocationCommand.COMMAND_NAME_FIELD);

    }
}
