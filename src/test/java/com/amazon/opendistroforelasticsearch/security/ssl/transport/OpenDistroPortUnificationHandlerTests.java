/*
 * Portions Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.opendistroforelasticsearch.security.ssl.transport;

import com.amazon.opendistroforelasticsearch.security.ssl.OpenDistroSecurityKeyStore;
import com.amazon.opendistroforelasticsearch.security.ssl.util.SSLUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class OpenDistroPortUnificationHandlerTests {

    private OpenDistroSecurityKeyStore openDistroSecurityKeyStore;
    private ChannelPipeline pipeline;
    private ChannelHandlerContext ctx;
    private SslHandler sslHandler;
    private SSLUtil sslUtil;

    @Before
    public void setup() {
        pipeline = Mockito.mock(ChannelPipeline.class);
        ctx = Mockito.mock(ChannelHandlerContext.class);
        Mockito.when(ctx.pipeline()).thenReturn(pipeline);

        openDistroSecurityKeyStore = Mockito.mock(OpenDistroSecurityKeyStore.class);
        sslHandler = Mockito.mock(SslHandler.class);
        sslUtil = Mockito.mock(SSLUtil.class);
    }

    @Test
    public void testInvalidMessage() throws Exception {
        OpenDistroPortUnificationHandler handler = new OpenDistroPortUnificationHandler(openDistroSecurityKeyStore, sslUtil);

        ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
        handler.decode(ctx, alloc.directBuffer(4), null);
        // ensure pipeline is not fetched and manipulated
        Mockito.verify(ctx, Mockito.times(0)).pipeline();
    }

    @Test
    public void testValidTLSMessage() throws Exception {
        OpenDistroPortUnificationHandler handler = new OpenDistroPortUnificationHandler(openDistroSecurityKeyStore, sslHandler, sslUtil);

        ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
        ByteBuf buffer = alloc.directBuffer(5);

        for (int i = 0; i < 5; i++) {
            buffer.writeByte(1);
        }

        Mockito.when(sslUtil.isTLS(buffer)).thenReturn(true);

        handler.decode(ctx, buffer, null);
        // ensure ssl handler is added
        Mockito.verify(ctx, Mockito.times(1)).pipeline();
        Mockito.verify(pipeline, Mockito.times(1))
                .addAfter("port_unification_handler", "ssl_server", sslHandler);
        Mockito.verify(pipeline,
                Mockito.times(1)).remove(handler);
    }

    @Test
    public void testNonTLSMessage() throws Exception {
        OpenDistroPortUnificationHandler handler = new OpenDistroPortUnificationHandler(openDistroSecurityKeyStore, sslHandler, sslUtil);

        ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
        ByteBuf buffer = alloc.directBuffer(5);

        for (int i = 0; i < 5; i++) {
            buffer.writeByte(1);
        }

        Mockito.when(sslUtil.isTLS(buffer)).thenReturn(false);

        handler.decode(ctx, buffer, null);
        // ensure ssl handler is added
        Mockito.verify(ctx, Mockito.times(1)).pipeline();
        Mockito.verify(pipeline, Mockito.times(0))
                .addAfter("port_unification_handler", "ssl_server", sslHandler);
        Mockito.verify(pipeline,
                Mockito.times(1)).remove(handler);
    }
}
