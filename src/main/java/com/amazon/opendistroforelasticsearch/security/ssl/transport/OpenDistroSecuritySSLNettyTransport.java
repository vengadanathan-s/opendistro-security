/*
 * Copyright 2015-2017 floragunn GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.amazon.opendistroforelasticsearch.security.ssl.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.amazon.opendistroforelasticsearch.security.ssl.util.SSLUtil;
import io.netty.channel.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TcpChannel;
import org.elasticsearch.transport.netty4.Netty4Transport;

import com.amazon.opendistroforelasticsearch.security.ssl.OpenDistroSecurityKeyStore;
import com.amazon.opendistroforelasticsearch.security.ssl.SslExceptionHandler;
import com.amazon.opendistroforelasticsearch.security.ssl.util.SSLConfigConstants;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.SslHandler;

public class OpenDistroSecuritySSLNettyTransport extends Netty4Transport {

    private static final Logger logger = LogManager.getLogger(OpenDistroSecuritySSLNettyTransport.class);
    private final OpenDistroSecurityKeyStore odsks;
    private final SslExceptionHandler errorHandler;
    private final SSLUtil sslUtil;

    public OpenDistroSecuritySSLNettyTransport(final Settings settings, final Version version, final ThreadPool threadPool, final NetworkService networkService,
            final PageCacheRecycler pageCacheRecycler, final NamedWriteableRegistry namedWriteableRegistry,
            final CircuitBreakerService circuitBreakerService, final OpenDistroSecurityKeyStore odsks, final SslExceptionHandler errorHandler) {
        super(settings, version, threadPool, networkService, pageCacheRecycler, namedWriteableRegistry, circuitBreakerService);

        this.odsks = odsks;
        this.errorHandler = errorHandler;
        this.sslUtil = new SSLUtil();
    }

    @Override
    public void onException(TcpChannel channel, Exception e) {

        Throwable cause = e;

        if (e instanceof DecoderException && e != null) {
            cause = e.getCause();
        }

        errorHandler.logError(cause, false);
        logger.error("Exception during establishing a SSL connection: " + cause, cause);

        super.onException(channel, e);
    }

    @Override
    protected ChannelHandler getServerChannelInitializer(String name) {
        return new SSLServerChannelInitializer(name);
    }

    @Override
    protected ChannelHandler getClientChannelInitializer(DiscoveryNode node) {
        return new SSLClientChannelInitializer(node);
    }

    protected class SSLServerChannelInitializer extends Netty4Transport.ServerChannelInitializer {

        public SSLServerChannelInitializer(String name) {
            super(name);
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            super.initChannel(ch);

            boolean isDualModeEnabled = OpenDistroSSLDualModeConfig.getInstance().isIsDualModeEnabled();

            if (isDualModeEnabled) {
                logger.info("SSL Dual mode enabled, using port unification handler");
                final ChannelHandler portUnificationHandler = new OpenDistroPortUnificationHandler(odsks, sslUtil);
                ch.pipeline().addFirst("port_unification_handler", portUnificationHandler);
            } else {
                final SslHandler sslHandler = new SslHandler(odsks.createServerTransportSSLEngine());
                ch.pipeline().addFirst("ssl_server", sslHandler);
            }
        }

        @Override
        public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (cause instanceof DecoderException && cause != null) {
                cause = cause.getCause();
            }

            errorHandler.logError(cause, false);
            logger.error("Exception during establishing a SSL connection: " + cause, cause);

            super.exceptionCaught(ctx, cause);
        }
    }

    protected static class ClientSSLHandler extends ChannelOutboundHandlerAdapter {
        private final Logger log = LogManager.getLogger(this.getClass());
        private final OpenDistroSecurityKeyStore odsks;
        private final boolean hostnameVerificationEnabled;
        private final boolean hostnameVerificationResovleHostName;
        private final SslExceptionHandler errorHandler;


        private ClientSSLHandler(final OpenDistroSecurityKeyStore odsks, final boolean hostnameVerificationEnabled,
                final boolean hostnameVerificationResovleHostName, final SslExceptionHandler errorHandler) {
            this.odsks = odsks;
            this.hostnameVerificationEnabled = hostnameVerificationEnabled;
            this.hostnameVerificationResovleHostName = hostnameVerificationResovleHostName;
            this.errorHandler = errorHandler;
        }


        @Override
        public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (cause instanceof DecoderException && cause != null) {
                cause = cause.getCause();
            }

            errorHandler.logError(cause, false);
            logger.error("Exception during establishing a SSL connection: " + cause, cause);

            super.exceptionCaught(ctx, cause);
        }

        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
            SSLEngine engine = null;


            try {
                final InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteAddress;

                if (hostnameVerificationEnabled) {

                    String hostname = null;

                    if (hostnameVerificationResovleHostName) {
                        hostname = inetSocketAddress.getHostName();
                    } else {
                        hostname = inetSocketAddress.getHostString();
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("Hostname of peer is {} ({}/{}) with hostnameVerificationResovleHostName: {}", hostname, inetSocketAddress.getHostName(), inetSocketAddress.getHostString(), hostnameVerificationResovleHostName);
                    }

                    engine = odsks.createClientTransportSSLEngine(hostname, inetSocketAddress.getPort());
                } else {
                    engine = odsks.createClientTransportSSLEngine(null, -1);
                }
            } catch (final SSLException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
            final SslHandler sslHandler = new SslHandler(engine);
            ctx.pipeline().replace(this, "ssl_client", sslHandler);
            super.connect(ctx, remoteAddress, localAddress, promise);
        }
    }

    protected class SSLClientChannelInitializer extends Netty4Transport.ClientChannelInitializer {
        private final boolean hostnameVerificationEnabled;
        private final boolean hostnameVerificationResovleHostName;
        private DiscoveryNode node;

        public SSLClientChannelInitializer(DiscoveryNode node) {
            this.node = node;
            hostnameVerificationEnabled = settings.getAsBoolean(
                    SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENFORCE_HOSTNAME_VERIFICATION, true);
            hostnameVerificationResovleHostName = settings.getAsBoolean(
                    SSLConfigConstants.OPENDISTRO_SECURITY_SSL_TRANSPORT_ENFORCE_HOSTNAME_VERIFICATION_RESOLVE_HOST_NAME, true);
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            super.initChannel(ch);

            boolean isSSL = true;

            boolean isDualModeEnabled = OpenDistroSSLDualModeConfig.getInstance().isIsDualModeEnabled();

            if (isDualModeEnabled) {
                isSSL = AccessController.doPrivileged((PrivilegedAction<Boolean>) () -> {
                    try {
                        logger.debug("Connecting to address {} and port {}",
                                node.getAddress().getAddress(), node.getAddress().getPort());
                        SSLSocketFactory factory =
                                (SSLSocketFactory) SSLSocketFactory.getDefault();
                        SSLSocket socket =
                                (SSLSocket) factory.createSocket(node.getAddress().getAddress(), node.getAddress().getPort());
                        logger.info("trying Handshake");
                        socket.startHandshake();
                    } catch (SSLException e) {
                        logger.error("Unable to handshake", e);
                        // there is no custom exception thrown if the server doesn't speak SSL instead it is inferred from
                        // message
                        if (e.getMessage().equals("Unsupported or unrecognized SSL message") ||
                            e.getMessage().equals("Remote host terminated the handshake") ||
                            e.getMessage().equals("Connection closed by peer")) {
                            return false;
                        }
                    } catch (Exception e) {
                        logger.debug("We don't worry much any other SSL errors", e);
                    }
                    return true;
                });
            }
            if (isSSL) {
                logger.info("Connection to {} needs to be ssl, adding ssl handler to the client channel ", node.getHostName());
                ch.pipeline().addFirst("client_ssl_handler", new ClientSSLHandler(odsks, hostnameVerificationEnabled,
                        hostnameVerificationResovleHostName, errorHandler));
            } else {
                logger.info("Connection to {} needs to be non ssl ", node.getHostName());
            }
        }

        @Override
        public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (cause instanceof DecoderException && cause != null) {
                cause = cause.getCause();
            }


            errorHandler.logError(cause, false);
            logger.error("Exception during establishing a SSL connection: " + cause, cause);

            super.exceptionCaught(ctx, cause);
        }
    }
}
