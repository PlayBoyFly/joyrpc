package io.joyrpc.transport.channel;

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

import io.joyrpc.event.AsyncResult;
import io.joyrpc.event.Publisher;
import io.joyrpc.exception.ChannelClosedException;
import io.joyrpc.exception.ConnectionException;
import io.joyrpc.exception.LafException;
import io.joyrpc.exception.TransportException;
import io.joyrpc.extension.URL;
import io.joyrpc.transport.event.TransportEvent;
import io.joyrpc.transport.heartbeat.DefaultHeartbeatTrigger;
import io.joyrpc.transport.heartbeat.HeartbeatStrategy;
import io.joyrpc.transport.heartbeat.HeartbeatTrigger;
import io.joyrpc.transport.transport.ClientTransport;
import io.joyrpc.util.Status;
import io.joyrpc.util.SystemClock;
import io.joyrpc.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

import static io.joyrpc.util.Status.*;
import static io.joyrpc.util.Timer.timer;

/**
 * @date: 2019/3/7
 */
public abstract class AbstractChannelManager implements ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(AbstractChannelManager.class);

    /**
     * channel
     */
    protected Map<String, PoolChannel> channels = new ConcurrentHashMap<>();
    /**
     * ??????????????????
     */
    protected Consumer<PoolChannel> beforeClose;


    /**
     * ????????????
     *
     * @param url
     */
    protected AbstractChannelManager(URL url) {
        this.beforeClose = o -> channels.remove(o.name);
    }

    @Override
    public void getChannel(final ClientTransport transport,
                           final Consumer<AsyncResult<Channel>> consumer,
                           final Connector connector) {
        if (connector == null) {
            if (consumer != null) {
                consumer.accept(new AsyncResult<>(new ConnectionException("opener can not be null.")));
            }
            return;
        }
        //??????????????????
        channels.computeIfAbsent(transport.getChannelName(),
                o -> new PoolChannel(transport, connector, beforeClose)).connect(consumer);
    }

    @Override
    public abstract String getChannelKey(ClientTransport transport);

    /**
     * ???????????????
     */
    protected static class PoolChannel extends DecoratorChannel {
        protected static final AtomicReferenceFieldUpdater<PoolChannel, Status> STATE_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(PoolChannel.class, Status.class, "status");
        /**
         * ????????????
         */
        protected Publisher<TransportEvent> publisher;
        /**
         * URL
         */
        protected URL url;
        /**
         * ??????
         */
        protected String name;
        /**
         * ????????????
         */
        protected HeartbeatStrategy strategy;
        /**
         * ??????
         */
        protected HeartbeatTrigger trigger;
        /**
         * ??????
         */
        protected Connector connector;
        /**
         * ?????????
         */
        protected Queue<Consumer<AsyncResult<Channel>>> consumers = new ConcurrentLinkedQueue<>();
        /**
         * ??????
         */
        protected volatile Status status = CLOSED;
        /**
         * ?????????
         */
        protected AtomicLong counter = new AtomicLong(0);
        /**
         * ??????????????????
         */
        protected AtomicInteger heartbeatFails = new AtomicInteger(0);
        /**
         * ????????????
         */
        protected Consumer<PoolChannel> beforeClose;
        /**
         * ???????????????
         */
        protected Consumer<AsyncResult<Channel>> afterConnect;


        /**
         * ????????????
         *
         * @param transport
         * @param connector
         * @param beforeClose
         */
        protected PoolChannel(final ClientTransport transport,
                              final Connector connector,
                              final Consumer<PoolChannel> beforeClose) {
            super(null);
            this.publisher = transport.getPublisher();
            this.name = transport.getChannelName();
            this.connector = connector;
            this.beforeClose = beforeClose;
            this.strategy = transport.getHeartbeatStrategy();

            this.afterConnect = event -> {
                if (event.isSuccess()) {
                    addRef();
                }
            };
        }

        /**
         * ????????????
         *
         * @param consumer
         */
        protected void connect(final Consumer<AsyncResult<Channel>> consumer) {
            //?????????????????????
            final Consumer<AsyncResult<Channel>> c = consumer == null ? afterConnect : afterConnect.andThen(consumer);
            //????????????
            if (STATE_UPDATER.compareAndSet(this, CLOSED, OPENING)) {
                consumers.offer(c);
                connector.connect(r -> {
                            if (r.isSuccess()) {
                                channel = r.getResult();
                                channel.setAttribute(CHANNEL_KEY, name);
                                channel.setAttribute(EVENT_PUBLISHER, publisher);
                                channel.setAttribute(HEARTBEAT_FAILED_COUNT, heartbeatFails);
                                channel.getFutureManager().open();
                                STATE_UPDATER.set(this, OPENED);
                                //???????????????????????????????????????????????????
                                trigger = strategy == null || strategy.getHeartbeat() == null ? null :
                                        new DefaultHeartbeatTrigger(this, url, strategy, publisher);
                                if (trigger != null) {
                                    switch (strategy.getHeartbeatMode()) {
                                        case IDLE:
                                            //??????Channel???Idle????????????????????????
                                            channel.setAttribute(Channel.IDLE_HEARTBEAT_TRIGGER, trigger);
                                            break;
                                        case TIMING:
                                            //????????????
                                            timer().add(new HeartbeatTask(this));
                                    }
                                }

                                publisher.start();
                                publish(new AsyncResult<>(PoolChannel.this));
                            } else {
                                STATE_UPDATER.set(this, CLOSED);
                                publish(new AsyncResult<>(r.getThrowable()));
                            }
                        }
                );
            } else {
                switch (status) {
                    case OPENED:
                        c.accept(new AsyncResult(PoolChannel.this));
                        break;
                    case OPENING:
                        consumers.add(c);
                        //???????????????????????????
                        switch (status) {
                            case OPENING:
                                break;
                            case OPENED:
                                publish(new AsyncResult<>(PoolChannel.this));
                                break;
                            default:
                                publish(new AsyncResult<>(new ConnectionException()));
                                break;
                        }
                        break;
                    default:
                        c.accept(new AsyncResult<>(new ConnectionException()));

                }
            }
        }

        /**
         * ????????????
         *
         * @param result
         */
        protected void publish(final AsyncResult result) {
            Consumer<AsyncResult<Channel>> consumer;
            while ((consumer = consumers.poll()) != null) {
                consumer.accept(result);
            }
        }

        /**
         * ??????????????????
         *
         * @return
         */
        protected long addRef() {
            return counter.incrementAndGet();
        }

        @Override
        public void send(final Object object, final Consumer<SendResult> consumer) {
            switch (status) {
                case OPENED:
                    super.send(object, consumer);
                    break;
                default:
                    LafException throwable = new ChannelClosedException(
                            String.format("Send request exception, causing channel is not opened. at  %s : %s",
                                    Channel.toString(this), object.toString()));
                    if (consumer != null) {
                        consumer.accept(new SendResult(throwable, this));
                    } else {
                        throw throwable;
                    }
            }
        }

        @Override
        public boolean isActive() {
            return super.isActive() && status == OPENED;
        }

        @Override
        public boolean close() {
            CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] err = new Throwable[1];
            final boolean[] res = new boolean[]{false};
            try {
                close(r -> {
                    if (r.getThrowable() != null) {
                        err[0] = r.getThrowable();
                    } else if (!r.isSuccess()) {
                        res[0] = false;
                    }
                });
                latch.await();
            } catch (InterruptedException e) {
            }
            if (err[0] != null) {
                throw new TransportException(err[0]);
            }
            return res[0];
        }

        @Override
        public void close(final Consumer<AsyncResult<Channel>> consumer) {
            if (counter.decrementAndGet() == 0) {
                beforeClose.accept(this);
                if (STATE_UPDATER.compareAndSet(this, OPENED, CLOSING)) {
                    if (channel.getFutureManager().isEmpty() || !channel.isActive()) {
                        //?????????????????????channel??????????????????????????????
                        doClose(consumer);
                    } else {
                        //????????????
                        timer().add(new CloseChannelTask(this, consumer));
                    }
                } else {
                    switch (status) {
                        case OPENING:
                        case OPENED:
                            timer().add(new CloseChannelTask(this, consumer));
                            break;
                        default:
                            Optional.ofNullable(consumer).ifPresent(o -> o.accept(new AsyncResult<>(this)));
                    }
                }
            } else {
                Optional.ofNullable(consumer).ifPresent(o -> o.accept(new AsyncResult<>(this)));
            }
        }

        /**
         * ??????
         *
         * @param consumer
         */
        protected void doClose(final Consumer<AsyncResult<Channel>> consumer) {
            channel.close(r -> {
                STATE_UPDATER.set(this, CLOSED);
                publisher.close();
                consumer.accept(r);
            });
        }

    }

    /**
     * ????????????Channel??????
     */
    protected static class CloseChannelTask implements Timer.TimeTask {

        /**
         * Channel
         */
        protected PoolChannel channel;
        /**
         * ?????????
         */
        protected Consumer<AsyncResult<Channel>> consumer;

        /**
         * ????????????
         *
         * @param channel
         * @param consumer
         */
        public CloseChannelTask(PoolChannel channel, Consumer<AsyncResult<Channel>> consumer) {
            this.channel = channel;
            this.consumer = consumer;
        }

        @Override
        public String getName() {
            return "CloseChannelTask-" + channel.name;
        }

        @Override
        public long getTime() {
            return SystemClock.now() + 400L;
        }

        @Override
        public void run() {
            if (channel.getFutureManager().isEmpty() || !channel.isActive()) {
                //???????????????,???channel???????????????
                channel.doClose(consumer);
            } else {
                //??????
                timer().add(this);
            }
        }
    }

    /**
     * ????????????
     */
    protected static class HeartbeatTask implements Timer.TimeTask {

        /**
         * Channel
         */
        protected PoolChannel channel;
        /**
         * ??????
         */
        protected final HeartbeatStrategy strategy;
        /**
         * ???????????????
         */
        protected final HeartbeatTrigger trigger;
        /**
         * ??????
         */
        protected final String name;
        /**
         * ??????????????????
         */
        protected final int interval;
        /**
         * ????????????
         */
        protected long time;

        /**
         * ????????????
         *
         * @param channel
         */
        public HeartbeatTask(final PoolChannel channel) {
            this.channel = channel;
            this.trigger = channel.trigger;
            this.strategy = trigger.strategy();
            this.interval = strategy.getInterval() <= 0 ? HeartbeatStrategy.DEFAULT_INTERVAL : strategy.getInterval();
            this.time = SystemClock.now() + ThreadLocalRandom.current().nextInt(interval);
            this.name = this.getClass().getSimpleName() + "-" + channel.name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getTime() {
            return time;
        }

        @Override
        public void run() {
            if (channel.status == OPENED) {//????????????????????????????????????????????????????????????
                try {
                    trigger.run();
                } catch (Exception e) {
                    logger.error(String.format("Error occurs while trigger heartbeat to %s, caused by: %s",
                            Channel.toString(channel.getRemoteAddress()), e.getMessage()), e);
                }
                time = SystemClock.now() + interval;
                timer().add(this);
            }
            logger.debug(String.format("Heartbeat task was run, channel %s status is %s, next time is %d.",
                    Channel.toString(channel.getRemoteAddress()), channel.status.name(), time));
        }
    }

}
