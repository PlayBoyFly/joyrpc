package io.joyrpc.transport.netty4.transport;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
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
 * #L%
 */

import io.joyrpc.constants.Constants;
import io.joyrpc.event.AsyncResult;
import io.joyrpc.exception.ConnectionException;
import io.joyrpc.exception.SslException;
import io.joyrpc.exception.TransportException;
import io.joyrpc.extension.URL;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.channel.ChannelManager.Connector;
import io.joyrpc.transport.heartbeat.HeartbeatStrategy.HeartbeatMode;
import io.joyrpc.transport.netty4.Plugin;
import io.joyrpc.transport.netty4.binder.HandlerBinder;
import io.joyrpc.transport.netty4.channel.NettyChannel;
import io.joyrpc.transport.netty4.handler.ConnectionChannelHandler;
import io.joyrpc.transport.netty4.handler.IdleHeartbeatHandler;
import io.joyrpc.transport.netty4.ssl.SslContextManager;
import io.joyrpc.transport.transport.AbstractClientTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.joyrpc.constants.Constants.*;

/**
 * @date: 2019/2/21
 */
public class NettyClientTransport extends AbstractClientTransport {
    /**
     * ?????????
     */
    protected EventLoopGroup ioGroup;

    /**
     * ????????????
     *
     * @param url
     */
    public NettyClientTransport(URL url) {
        super(url);
    }

    @Override
    public Connector getConnector() {
        return this::connect;
    }

    /**
     * ??????channel
     *
     * @param consumer
     */
    protected void connect(final Consumer<AsyncResult<Channel>> consumer) {
        //consumer????????????
        if (codec == null) {
            consumer.accept(new AsyncResult<>(error("codec can not be null!")));
        } else {
            try {
                ioGroup = EventLoopGroupFactory.getClientEventLoopGroup(url);
                //??????SSL?????????
                SslContext sslContext = SslContextManager.getClientSslContext(url);
                final Channel[] channels = new Channel[1];
                //TODO ???????????????????????????????????????????????????
                Bootstrap bootstrap = handler(configure(new Bootstrap()), channels, sslContext);
                // Bind and start to accept incoming connections.
                bootstrap.connect(url.getHost(), url.getPort()).addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        consumer.accept(new AsyncResult<>(channels[0]));
                    } else {
                        consumer.accept(new AsyncResult<>(error(f.cause())));
                    }
                });
            } catch (SslException e) {
                consumer.accept(new AsyncResult<>(e));
            } catch (ConnectionException e) {
                consumer.accept(new AsyncResult<>(e));
            } catch (Throwable e) {
                //??????Throwable?????????netty??????
                consumer.accept(new AsyncResult<>(error(e)));
            }
        }
    }

    /**
     * ???????????????
     *
     * @param bootstrap
     * @param channels
     * @param sslContext
     * @return
     */
    protected Bootstrap handler(final Bootstrap bootstrap, final Channel[] channels, final SslContext sslContext) {
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) {
                //???????????? ??? ????????????
                channels[0] = new NettyChannel(ch, false);
                //??????
                channels[0].setAttribute(Channel.PAYLOAD, url.getPositiveInt(Constants.PAYLOAD))
                        .setAttribute(Channel.BIZ_THREAD_POOL, bizThreadPool, (k, v) -> v != null);
                //????????????????????????
                ch.pipeline().addLast("connection", new ConnectionChannelHandler(channels[0], publisher));
                //???????????????????????????
                HandlerBinder binder = Plugin.HANDLER_BINDER.get(codec.binder());
                binder.bind(ch.pipeline(), codec, handlerChain, channels[0]);
                //?????????idle???????????????????????????handler
                if (heartbeatStrategy != null && heartbeatStrategy.getHeartbeatMode() == HeartbeatMode.IDLE) {
                    ch.pipeline().addLast("idleState",
                            new IdleStateHandler(0, heartbeatStrategy.getInterval(), 0, TimeUnit.MILLISECONDS))
                            .addLast("idleHeartbeat", new IdleHeartbeatHandler());
                }

                if (sslContext != null) {
                    ch.pipeline().addFirst("ssl", sslContext.newHandler(ch.alloc()));
                }
                //????????????ss5???????????????ss5
                if (url.getBoolean(SS5_ENABLE)) {
                    String host = url.getString(SS5_HOST);
                    if (host != null && !host.isEmpty()) {
                        InetSocketAddress ss5Address = new InetSocketAddress(host, url.getInteger(SS5_PORT));
                        ch.pipeline().addFirst("ss5",
                                new Socks5ProxyHandler(ss5Address, url.getString(SS5_USER), url.getString(SS5_PASSWORD)));
                    }
                }
            }
        });
        return bootstrap;
    }

    /**
     * ??????
     *
     * @param bootstrap
     */
    protected Bootstrap configure(final Bootstrap bootstrap) {
        //Unknown channel option 'SO_BACKLOG' for channel
        bootstrap.group(ioGroup).channel(Constants.isUseEpoll(url) ? EpollSocketChannel.class : NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, url.getPositiveInt(Constants.CONNECT_TIMEOUT_OPTION))
                //.option(ChannelOption.SO_TIMEOUT, url.getPositiveInt(Constants.SO_TIMEOUT_OPTION))
                .option(ChannelOption.SO_KEEPALIVE, url.getBoolean(Constants.SO_KEEPALIVE_OPTION))
                .option(ChannelOption.ALLOCATOR, BufAllocator.create(url))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(url.getPositiveInt(Constants.WRITE_BUFFER_LOW_WATERMARK_OPTION),
                        url.getPositiveInt(Constants.WRITE_BUFFER_HIGH_WATERMARK_OPTION)))
                .option(ChannelOption.RCVBUF_ALLOCATOR, AdaptiveRecvByteBufAllocator.DEFAULT);
        return bootstrap;
    }

    /**
     * ????????????
     *
     * @param message
     * @return
     */
    protected Throwable error(final String message) {
        return message == null || message.isEmpty() ? new ConnectionException("Unknown error.") : new ConnectionException(message);
    }

    /**
     * ????????????
     *
     * @param throwable
     * @return
     */
    protected Throwable error(final Throwable throwable) {
        return throwable == null ?
                new ConnectionException("Unknown error.") :
                new ConnectionException(throwable.getMessage(), throwable);
    }

    @Override
    public void close(final Consumer<AsyncResult<Channel>> consumer) {
        super.close(o -> {
            EventLoopGroup group = this.ioGroup;
            if (group != null && !url.getBoolean(EventLoopGroupFactory.NETTY_EVENTLOOP_SHARE, true)) {
                if (consumer == null) {
                    group.shutdownGracefully();
                } else {
                    group.shutdownGracefully().addListener(f -> {
                        if (!f.isSuccess()) {
                            Throwable throwable = f.cause() == null ? new TransportException("unknown exception.") : f.cause();
                            consumer.accept(new AsyncResult<>(o.getResult(), throwable));
                        } else {
                            consumer.accept(o);
                        }
                    });
                }
            } else if (consumer != null) {
                consumer.accept(o);
            }
        });
    }
}
