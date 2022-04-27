/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.connectors.ConnectorPortRegister;
import org.neo4j.configuration.connectors.ConnectorType;
import org.neo4j.configuration.helpers.PortBindException;
import org.neo4j.internal.helpers.NamedThreadFactory;
import org.neo4j.logging.internal.NullLogService;

class NettyServerTest {
    private NettyServer server;

    @AfterEach
    void afterEach() throws Exception {
        if (server != null) {
            server.stop();
            server.shutdown();
        }
    }

    @Test
    void shouldGivePortConflictErrorWithPortNumberInIt() throws Throwable {
        // Given an occupied port
        var port = 16000;
        try (ServerSocketChannel ignore = ServerSocketChannel.open().bind(new InetSocketAddress("localhost", port))) {
            var address = new InetSocketAddress("localhost", port);

            // When
            server = new NettyServer(
                    newThreadFactory(),
                    protocolOnAddress(address),
                    null,
                    new ConnectorPortRegister(),
                    NullLogService.getInstance(),
                    Config.defaults());

            // Then
            assertThrows(PortBindException.class, server::start);
        }
    }

    @Test
    void shouldGivePortConflictErrorWithInternalPortNumberInIt() {
        // Given a setup with matching port numbers for internal and external bolt
        var port = 16000;
        var address = new InetSocketAddress("localhost", port);

        // When
        server = new NettyServer(
                newThreadFactory(),
                protocolOnAddress(address),
                protocolOnAddress(address),
                null,
                new ConnectorPortRegister(),
                NullLogService.getInstance(),
                Config.defaults());

        // Then
        assertThrows(PortBindException.class, server::start);
    }

    @Test
    void shouldRegisterPortInPortRegister() throws Exception {
        var connector = ConnectorType.BOLT;
        var portRegister = new ConnectorPortRegister();

        var address = new InetSocketAddress("localhost", 0);
        server = new NettyServer(
                newThreadFactory(),
                protocolOnAddress(address),
                null,
                portRegister,
                NullLogService.getInstance(),
                Config.defaults());

        assertNull(portRegister.getLocalAddress(connector));

        server.init();
        server.start();
        var actualAddress = portRegister.getLocalAddress(connector);
        assertNotNull(actualAddress);
        assertThat(actualAddress.getPort()).isGreaterThan(0);

        server.stop();
        server.shutdown();
        assertNull(portRegister.getLocalAddress(connector));
    }

    @Test
    void shouldRegisterInternalAndExternalPortsInPortRegister() throws Exception {
        var portRegister = new ConnectorPortRegister();

        var external = new InetSocketAddress("localhost", 0);
        var internal = new InetSocketAddress("localhost", 0);
        server = new NettyServer(
                newThreadFactory(),
                protocolOnAddress(external),
                protocolOnAddress(internal),
                null,
                portRegister,
                NullLogService.getInstance(),
                Config.defaults());

        assertNull(portRegister.getLocalAddress(ConnectorType.BOLT));
        assertNull(portRegister.getLocalAddress(ConnectorType.INTRA_BOLT));

        server.init();
        server.start();
        var actualExternalAddress = portRegister.getLocalAddress(ConnectorType.BOLT);
        assertNotNull(actualExternalAddress);
        assertThat(actualExternalAddress.getPort()).isGreaterThan(0);

        var actualInternalAddress = portRegister.getLocalAddress(ConnectorType.INTRA_BOLT);
        assertNotNull(actualInternalAddress);
        assertThat(actualInternalAddress.getPort()).isGreaterThan(0);

        server.stop();
        server.shutdown();
        assertNull(portRegister.getLocalAddress(ConnectorType.BOLT));
        assertNull(portRegister.getLocalAddress(ConnectorType.INTRA_BOLT));
    }

    private static NettyServer.ProtocolInitializer protocolOnAddress(SocketAddress address) {
        return new NettyServer.ProtocolInitializer() {
            @Override
            public ChannelInitializer<Channel> channelInitializer() {
                return new ChannelInitializer<>() {
                    @Override
                    public void initChannel(Channel ch) {}
                };
            }

            @Override
            public SocketAddress address() {
                return address;
            }
        };
    }

    private static NamedThreadFactory newThreadFactory() {
        return new NamedThreadFactory("test-threads", true);
    }
}
