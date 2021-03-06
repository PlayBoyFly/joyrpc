package io.joyrpc.invoker;

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

import io.joyrpc.InvokerAware;
import io.joyrpc.cluster.Cluster;
import io.joyrpc.cluster.discovery.config.ConfigHandler;
import io.joyrpc.cluster.discovery.config.Configure;
import io.joyrpc.cluster.discovery.registry.Registry;
import io.joyrpc.cluster.distribution.LoadBalance;
import io.joyrpc.cluster.distribution.loadbalance.StickyLoadBalance;
import io.joyrpc.cluster.event.NodeEvent;
import io.joyrpc.codec.serialization.Registration;
import io.joyrpc.codec.serialization.Serialization;
import io.joyrpc.config.ConsumerConfig;
import io.joyrpc.config.ProviderConfig;
import io.joyrpc.constants.Constants;
import io.joyrpc.context.GlobalContext;
import io.joyrpc.event.Publisher;
import io.joyrpc.event.PublisherConfig;
import io.joyrpc.exception.IllegalConfigureException;
import io.joyrpc.extension.MapParametric;
import io.joyrpc.extension.Parametric;
import io.joyrpc.extension.URL;
import io.joyrpc.metric.DashboardAware;
import io.joyrpc.metric.DashboardFactory;
import io.joyrpc.protocol.ServerProtocol;
import io.joyrpc.protocol.handler.DefaultProtocolAdapter;
import io.joyrpc.thread.NamedThreadFactory;
import io.joyrpc.thread.ThreadPool;
import io.joyrpc.transport.Server;
import io.joyrpc.transport.ShareServer;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.event.InactiveEvent;
import io.joyrpc.transport.message.Message;
import io.joyrpc.transport.transport.ChannelTransport;
import io.joyrpc.util.Close;
import io.joyrpc.util.Futures;
import io.joyrpc.util.GenericChecker;
import io.joyrpc.util.Shutdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static io.joyrpc.Plugin.*;
import static io.joyrpc.cluster.Cluster.EVENT_PUBLISHER_CLUSTER;
import static io.joyrpc.cluster.Cluster.EVENT_PUBLISHER_CLUSTER_CONF;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.constants.ExceptionCode.CONSUMER_DUPLICATE_REFER;
import static io.joyrpc.constants.ExceptionCode.PROVIDER_DUPLICATE_EXPORT;

/**
 * @date: 9/1/2019
 */
public class InvokerManager {

    private static final Logger logger = LoggerFactory.getLogger(InvokerManager.class);

    public static final String HASH_CODE = "hashCode";

    /**
     * ????????????
     */
    public static final BiFunction<String, String, String> NAME = (className, alias) -> className + "/" + alias;
    protected static final String EVENT_PUBLISHER_GROUP = "event.invoker";
    protected static final String EVENT_PUBLISHER_NAME = "default";
    protected static final PublisherConfig EVENT_PUBLISHER_CONF = PublisherConfig.builder().timeout(1000).build();

    /**
     * ??????????????????
     */
    public static final InvokerManager INSTANCE = new InvokerManager();
    /**
     * ???????????????
     */
    protected Publisher<ExporterEvent> publisher;
    /**
     * ??????????????????
     */
    protected Map<String, Refer> refers = new ConcurrentHashMap<>();

    /**
     * ?????????????????????????????????????????????
     */
    protected Map<String, Refer> systems = new ConcurrentHashMap<>();

    /**
     * ??????????????????,key?????????+???????????????????????????????????????????????????
     */
    protected Map<String, Map<Integer, Exporter>> exports = new ConcurrentHashMap<>();

    /**
     * ?????????TCP??????
     */
    protected Map<Integer, Server> servers = new ConcurrentHashMap<>(10);
    /**
     * ???????????????
     */
    protected CallbackManager callbackManager = new CallbackManager();
    /**
     * ??????ID???????????????????????????????????????
     */
    protected Map<Long, String> interfaceIds = new ConcurrentHashMap<>();

    protected InvokerManager() {
        Shutdown.addHook(new Shutdown.HookAdapter((Shutdown.Hook) this::close, 0));
        this.publisher = EVENT_BUS.get().getPublisher(EVENT_PUBLISHER_GROUP, EVENT_PUBLISHER_NAME, EVENT_PUBLISHER_CONF);
        this.publisher.start();
    }

    /**
     * ????????????ID??????
     *
     * @param interfaceId
     * @param className
     */
    public static void putInterfaceId(final long interfaceId, final String className) {
        INSTANCE.interfaceIds.put(interfaceId, className);
    }

    /**
     * ????????????ID??????????????????
     *
     * @param interfaceId long
     * @return
     */
    public static String getClassName(final long interfaceId) {
        return INSTANCE.interfaceIds.get(interfaceId);
    }

    /**
     * ????????????ID??????????????????
     *
     * @param interfaceId String
     * @return
     */
    public static String getClassName(final String interfaceId) {
        try {
            return INSTANCE.interfaceIds.get(Long.parseLong(interfaceId));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ????????????ID??????
     *
     * @return
     */
    public static Map<Long, String> getInterfaceIds() {
        return new HashMap<>(INSTANCE.interfaceIds);
    }

    /**
     * ??????????????????
     *
     * @param config
     * @param url
     * @param registry
     * @param registerUrl
     * @param configure
     * @param subscribeUrl
     * @param configHandler
     * @return
     */
    public static Refer refer(final URL url,
                              final ConsumerConfig<?> config,
                              final Registry registry,
                              final URL registerUrl,
                              final Configure configure,
                              final URL subscribeUrl,
                              final ConfigHandler configHandler) {
        return INSTANCE.doRefer(url, config, registry, registerUrl, configure, subscribeUrl, configHandler);
    }


    /**
     * ??????????????????
     *
     * @param url           ??????URL
     * @param config        ????????????
     * @param registries    ????????????
     * @param registerUrls  ??????URL
     * @param configure     ????????????
     * @param subscribeUrl  ????????????URL
     * @param configHandler ?????????????????????
     * @return
     */
    public static Exporter export(final URL url,
                                  final ProviderConfig<?> config,
                                  final List<Registry> registries,
                                  final List<URL> registerUrls,
                                  final Configure configure,
                                  final URL subscribeUrl,
                                  final ConfigHandler configHandler) {
        return INSTANCE.doExport(url, config, registries, registerUrls, configure, subscribeUrl, configHandler);
    }

    /**
     * ????????????????????????
     *
     * @return
     */
    public static List<Refer> getRefers() {
        return new ArrayList<>(INSTANCE.refers.values());
    }


    /**
     * ??????????????????
     *
     * @param name
     * @return
     */
    public static Map<Integer, Exporter> getExporter(final String name) {
        return INSTANCE.exports.get(name);
    }

    /**
     * ??????????????????
     *
     * @param className
     * @param alias
     * @return
     */
    public static void getExporter(final String className, final String alias, BiConsumer<Integer, Exporter> consumer) {
        Map<Integer, Exporter> exporters = getExporter(NAME.apply(className, alias));
        if (null != exporters) {
            exporters.forEach(consumer);
        }
    }


    /**
     * ??????????????????
     *
     * @param name
     * @param port
     * @return
     */
    public static Exporter getExporter(final String name, final int port) {
        Map<Integer, Exporter> ports = INSTANCE.exports.get(name);
        return ports == null ? null : ports.get(port);
    }

    /**
     * ??????????????????
     *
     * @param className
     * @param alias
     * @param port
     * @return
     */
    public static Exporter getExporter(final String className, final String alias, final int port) {
        return getExporter(NAME.apply(className, alias), port);
    }

    /**
     * ??????????????????
     *
     * @param name
     * @return
     */
    public static Exporter getFirstExporter(final String name) {
        Map<Integer, Exporter> ports = INSTANCE.exports.get(name);
        return ports == null || ports.size() < 1 ? null : ports.values().iterator().next();
    }

    /**
     * ????????????????????????????????????
     *
     * @param className
     * @return
     */
    public static Exporter getFirstExporterByInterface(final String className) {
        if (className != null && !className.isEmpty()) {
            String prefix = className + "/";
            for (Map.Entry<String, Map<Integer, Exporter>> entry : INSTANCE.exports.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    return entry.getValue().values().iterator().next();
                }
            }
        }
        return null;
    }

    /**
     * ??????????????????
     *
     * @param className
     * @param alias
     * @return
     */
    public static Exporter getFirstExporter(final String className, final String alias) {
        return getFirstExporter(NAME.apply(className, alias));
    }

    /**
     * ????????????
     *
     * @param consumer
     */
    public static void exports(final Consumer<Exporter> consumer) {
        if (consumer != null) {
            INSTANCE.exports.forEach((k, v) -> v.forEach((t, o) -> consumer.accept(o)));
        }
    }

    public static Server getServer(final int port) {
        return INSTANCE.servers.get(port);
    }

    /**
     * ????????????
     *
     * @return
     */
    public static List<Server> getServers() {
        return new ArrayList<>(INSTANCE.servers.values());
    }

    /**
     * ?????????????????????
     *
     * @return
     */
    public static ThreadPoolExecutor getCallbackThreadPool() {
        return INSTANCE.callbackManager.getThreadPool();
    }

    /**
     * ???????????????????????????
     *
     * @return
     */
    public static CallbackContainer getConsumerCallback() {
        return INSTANCE.callbackManager.getConsumer();
    }

    /**
     * ?????????????????????????????????
     *
     * @return
     */
    public static CallbackContainer getProducerCallback() {
        return INSTANCE.callbackManager.getProducer();
    }

    /**
     * ???????????????
     *
     * @param executor
     * @param name
     * @param parametric
     * @param coreKey
     * @param maxKey
     */
    public static void updateThreadPool(final ThreadPoolExecutor executor, final String name, final Parametric parametric,
                                        final String coreKey, final String maxKey) {
        if (executor == null) {
            return;
        }
        Integer core = parametric.getInteger(coreKey);
        if (core != null && core > 0 && core != executor.getCorePoolSize()) {
            logger.info(String.format("Core pool size of %s is changed from %d to %d",
                    name, executor.getCorePoolSize(), core));
            executor.setCorePoolSize(core);
        }
        Integer max = parametric.getInteger(maxKey);
        if (max != null && max > 0 && max != executor.getMaximumPoolSize()) {
            logger.info(String.format("Maximum pool size of %s is changed from %d to %d",
                    name, executor.getMaximumPoolSize(), max));
            executor.setMaximumPoolSize(max);
        }
    }

    /**
     * ??????????????????
     *
     * @param url
     * @param config
     * @param registry
     * @param registerUrl
     * @param configure
     * @param subscribeUrl
     * @param configHandler
     * @param <T>
     * @return
     */
    protected <T> Refer doRefer(final URL url,
                                final ConsumerConfig<T> config,
                                final Registry registry,
                                final URL registerUrl,
                                final Configure configure,
                                final URL subscribeUrl,
                                final ConfigHandler configHandler) {
        return doRefer(url, config, registry, registerUrl, configure, subscribeUrl, configHandler,
                url.getBoolean(Constants.SYSTEM_REFER_OPTION) ? systems : refers);
    }

    /**
     * ??????????????????
     *
     * @param url           ??????URL
     * @param config        ??????
     * @param registry      ????????????
     * @param registerUrl   ??????URL
     * @param configure     ????????????
     * @param subscribeUrl  ????????????URL
     * @param configHandler ?????????????????????
     * @param refers
     * @return
     */
    protected Refer doRefer(final URL url,
                            final ConsumerConfig<?> config,
                            final Registry registry,
                            final URL registerUrl,
                            final Configure configure,
                            final URL subscribeUrl,
                            final ConfigHandler configHandler,
                            final Map<String, Refer> refers) {
        //???????????????????????????????????????????????????????????????
        String key = url.toString(false, true);
        //??????hashCode???????????????HOST???????????????
        String clusterName = url.add(HASH_CODE, key.hashCode()).setHost(null).
                toString(false, true, Constants.ALIAS_OPTION.getName(), Constants.COUNTER, HASH_CODE);

        if (refers.containsKey(clusterName)) {
            throw new IllegalConfigureException(
                    String.format("Duplicate consumer config with key %s has been referred.", clusterName),
                    CONSUMER_DUPLICATE_REFER);
        }

        return refers.computeIfAbsent(clusterName, o -> {
            //????????????
            LoadBalance loadBalance = buildLoadbalance(config, url);
            //????????????
            DashboardFactory dashboardFactory = buildDashboardFactory(loadBalance);
            //??????????????????????????????+??????+????????????????????????????????????????????????????????????????????????
            final Publisher<NodeEvent> publisher = EVENT_BUS.get().getPublisher(EVENT_PUBLISHER_CLUSTER, clusterName, EVENT_PUBLISHER_CLUSTER_CONF);
            final Cluster cluster = new Cluster(clusterName, url, registry, null, null, null, dashboardFactory, METRIC_HANDLER.extensions(), publisher);
            //??????????????????????????????????????????????????????????????????????????????Cluster???????????????????????????????????????
            serializationRegister(config.getProxyClass());
            //refer????????????key???????????????????????????
            return new Refer(clusterName, url, config, registry, registerUrl, configure, subscribeUrl, configHandler,
                    cluster, loadBalance, callbackManager.getConsumer(), this.publisher,
                    InvokerManager::getFirstExporter,
                    (v, t) -> refers.remove(v.getName()));
        });
    }

    /**
     * ??????????????????
     *
     * @param loadBalance
     * @return
     */
    protected DashboardFactory buildDashboardFactory(final LoadBalance loadBalance) {
        return loadBalance instanceof DashboardAware ? DASHBOARD_FACTORY.get() : null;
    }

    /**
     * ???????????????
     *
     * @param config ??????
     * @param url    url
     * @return ????????????
     */
    protected <T> LoadBalance buildLoadbalance(final ConsumerConfig<T> config, final URL url) {
        //????????????
        boolean sticky = url.getBoolean(STICKY_OPTION);
        LoadBalance loadBalance = LOADBALANCE.get(url.getString(Constants.LOADBALANCE_OPTION));
        loadBalance.setUrl(url);
        if (loadBalance instanceof InvokerAware) {
            InvokerAware aware = (InvokerAware) loadBalance;
            //????????????????????????
            aware.setClassName(url.getPath());
        }
        loadBalance.setup();
        loadBalance = sticky ? new StickyLoadBalance(loadBalance) : loadBalance;

        return loadBalance;
    }

    /**
     * ???????????????
     *
     * @param clazz ???
     */
    protected void serializationRegister(final Class<?> clazz) {

        List<Registration> registrations = new LinkedList<>();
        Iterable<Serialization> itr = SERIALIZATION.extensions();
        for (Serialization ser : itr) {
            if (Registration.class.isAssignableFrom(ser.getClass())) {
                registrations.add((Registration) ser);
            }
        }
        if (registrations.isEmpty()) {
            return;
        }

        //??????????????????????????????
        Set<Class<?>> registerClass = new LinkedHashSet<>();
        GenericChecker checker = new GenericChecker();
        checker.checkMethods(clazz, GenericChecker.NONE_STATIC_METHOD, (cls, scope) -> {
            if (!cls.equals(void.class) && !CompletionStage.class.isAssignableFrom(cls)) {
                registerClass.add(cls);
            }
        });
        registrations.forEach(r -> r.register(registerClass));
    }

    /**
     * ??????????????????
     *
     * @param url           ??????URL
     * @param config        ?????????????????????
     * @param registries    ????????????
     * @param registerUrls  ?????????URL
     * @param configure     ????????????
     * @param subscribeUrl  ???????????????URL
     * @param configHandler ???????????????
     * @return
     */
    protected Exporter doExport(final URL url,
                                final ProviderConfig<?> config,
                                final List<Registry> registries,
                                final List<URL> registerUrls,
                                final Configure configure,
                                final URL subscribeUrl,
                                final ConfigHandler configHandler) {
        //??????url.getPath????????????????????????
        final String name = NAME.apply(url.getPath(), config.getAlias());
        Map<Integer, Exporter> ports = exports.get(name);
        if (ports != null && ports.containsKey(url.getPort())) {
            throw new IllegalConfigureException(
                    String.format("Duplicate provider config with key %s has been exported.", name),
                    PROVIDER_DUPLICATE_EXPORT);
        }
        return exports.computeIfAbsent(name, o -> new ConcurrentHashMap<>()).computeIfAbsent(url.getPort(),
                o -> {
                    serializationRegister(config.getProxyClass());
                    return new Exporter(name, url, config, registries, registerUrls, configure, subscribeUrl,
                            configHandler, getServer(url), callbackManager.getProducer(), publisher,
                            c -> {
                                Map<Integer, Exporter> map = exports.get(c.getName());
                                if (map != null) {
                                    map.remove(c.getPort());
                                }
                            });
                });
    }

    /**
     * ??????Server
     *
     * @param url
     * @return
     */
    protected Server getServer(final URL url) {
        return servers.computeIfAbsent(url.getPort(), port -> {
            Server server = ENDPOINT_FACTORY.getOrDefault(url.getString(ENDPOINT_FACTORY_OPTION)).createServer(url);
            server.setAdapter(new DefaultProtocolAdapter());
            server.addEventHandler(event -> {
                if (event instanceof InactiveEvent) {
                    Channel channel = ((InactiveEvent) event).getChannel();
                    ChannelTransport transport = channel.getAttribute(Channel.CHANNEL_TRANSPORT);
                    callbackManager.getProducer().removeCallback(transport);
                }
            });
            //??????server??????????????????
            server.setBizThreadPool(getBizThreadPool(url));
            return new ShareServer(server, v -> servers.remove(v.getUrl().getPort()));
        });
    }

    /**
     * ?????????????????????
     *
     * @param url
     * @return
     */
    protected ThreadPoolExecutor getBizThreadPool(final URL url) {
        ThreadPool pool = THREAD_POOL.getOrDefault(url.getString(THREADPOOL_OPTION));
        NamedThreadFactory threadFactory = new NamedThreadFactory("RPC-BZ-" + url.getPort(), true);
        return pool.get(url, threadFactory);
    }

    /**
     * ?????????????????????
     *
     * @return
     */
    public CallbackManager getCallbackManager() {
        return callbackManager;
    }

    /**
     * ??????
     *
     * @return
     */
    public CompletableFuture<Void> close() {
        Parametric parametric = new MapParametric(GlobalContext.getContext());
        return close(parametric.getBoolean(Constants.GRACEFULLY_SHUTDOWN_OPTION));
    }

    /**
     * ??????
     *
     * @param gracefully ??????????????????
     * @return
     */
    public CompletableFuture<Void> close(final boolean gracefully) {
        publisher.close();
        CompletableFuture<Void> result = new CompletableFuture<>();
        //???????????????????????????
        Set<Registry> registries = new HashSet<>(5);
        Parametric parametric = new MapParametric(GlobalContext.getContext());
        long timeout = parametric.getPositiveLong(OFFLINE_TIMEOUT_OPTION);
        //??????????????????
        offline(timeout, gracefully).whenComplete((k, s) ->
                //????????????
                closeInvoker(registries, gracefully).whenComplete((v, t) -> {
                    //??????????????????????????????
                    exports = new ConcurrentHashMap<>();
                    refers = new ConcurrentHashMap<>();
                    callbackManager.close();
                    //????????????
                    servers.forEach((o, r) -> r.close(x -> {
                        //????????????????????????????????????
                        Close.close(r.getBizThreadPool(), 0);
                    }));
                    servers = new ConcurrentHashMap<>();
                    //????????????????????????????????????????????????????????????
                    systems.forEach((o, r) -> r.close());
                    //??????????????????
                    closeRegistry(registries, gracefully).whenComplete((o, r) -> result.complete(null));
                }));
        return result;
    }

    /**
     * ?????????????????????????????????5???
     *
     * @param timeout    ?????????????????????????????????
     * @param gracefully ??????????????????
     * @return
     */
    protected CompletableFuture<Void> offline(final long timeout, final boolean gracefully) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!gracefully) {
            //?????????????????????
            result.complete(null);
        } else {
            List<CompletableFuture<Void>> futures = new LinkedList<>();
            //????????????????????????????????????
            servers.forEach((k, server) -> server.forEach(t -> {
                ServerProtocol protocol = t.getChannel().getAttribute(Channel.PROTOCOL);
                if (protocol != null) {
                    Message message = protocol.offline(server.getUrl());
                    if (message != null) {
                        futures.add(t.oneway(message));
                    }
                }
            }));
            if (timeout > 0) {
                //?????????????????????????????????????????????
                CompletableFuture<Void> future = Futures.allOf(futures);
                //????????????????????????
                future = Futures.timeout(future, timeout);
                future.whenComplete((v, t) -> result.complete(null));
            }
        }
        return result;
    }

    /**
     * ?????????????????????????????????
     *
     * @param registries
     * @param gracefully
     * @return
     */
    protected CompletableFuture<Void> closeInvoker(final Set<Registry> registries, final boolean gracefully) {
        List<CompletableFuture<Void>> futures = new LinkedList<>();
        //?????????????????????????????????
        exports.forEach((k, v) -> v.forEach((t, o) -> {
            futures.add(o.getConfig().unexport(gracefully));
            registries.addAll(o.getRegistries());
        }));
        //????????????????????????
        refers.forEach((k, v) -> {
            futures.add(v.getConfig().unrefer(gracefully));
            registries.add(v.getRegistry());
        });
        return gracefully ? Futures.allOf(futures) : CompletableFuture.completedFuture(null);
    }

    /**
     * ?????????????????????
     *
     * @param registries
     * @param gracefully
     * @return
     */
    protected CompletableFuture<Void> closeRegistry(final Set<Registry> registries, final boolean gracefully) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(registries.size());
        registries.forEach(o -> futures.add(o.close()));
        return gracefully ? Futures.allOf(futures) : CompletableFuture.completedFuture(null);
    }
}
