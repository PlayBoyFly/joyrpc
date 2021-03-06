package io.joyrpc.config;

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

import io.joyrpc.annotation.Alias;
import io.joyrpc.cache.CacheFactory;
import io.joyrpc.cache.CacheKeyGenerator;
import io.joyrpc.cluster.Region;
import io.joyrpc.cluster.discovery.config.ConfigHandler;
import io.joyrpc.cluster.discovery.config.Configure;
import io.joyrpc.cluster.discovery.registry.Registry;
import io.joyrpc.cluster.event.ConfigEvent;
import io.joyrpc.codec.compression.Compression;
import io.joyrpc.config.validator.*;
import io.joyrpc.constants.Constants;
import io.joyrpc.context.Configurator;
import io.joyrpc.context.GlobalContext;
import io.joyrpc.exception.InitializationException;
import io.joyrpc.extension.URL;
import io.joyrpc.proxy.ProxyFactory;
import io.joyrpc.util.Shutdown;
import io.joyrpc.util.SystemClock;
import io.joyrpc.util.network.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.joyrpc.Plugin.CONFIGURATOR;
import static io.joyrpc.Plugin.PROXY;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.context.Configurator.CONFIG_ALLOWED;
import static io.joyrpc.context.Configurator.GLOBAL_ALLOWED;
import static io.joyrpc.util.StringUtils.isNotEmpty;
import static io.joyrpc.util.Timer.timer;

/**
 * ??????????????????
 */
@ValidateAlias
@ValidateInterface
@ValidateFilter
public abstract class AbstractInterfaceConfig extends AbstractIdConfig {
    private final static Logger logger = LoggerFactory.getLogger(AbstractInterfaceConfig.class);

    /**
     * ????????????????????????????????????????????????????????????????????????
     */
    protected String interfaceClazz;
    /**
     * ????????????
     */
    protected String alias;
    /**
     * ???????????????????????????????????????
     */
    protected String filter;
    /**
     * ??????????????????????????????
     */
    @Valid
    protected Map<String, MethodConfig> methods;
    /**
     * ????????????????????????false??????????????????????????????true
     */
    protected boolean register = true;
    /**
     * ??????????????????????????????true
     */
    protected boolean subscribe = true;
    /**
     * ????????????????????????(??????)
     */
    protected Integer timeout;
    /**
     * ????????????
     */
    @ValidatePlugin(extensible = ProxyFactory.class, name = "PROXY", defaultValue = DEFAULT_PROXY)
    protected String proxy;
    /**
     * ???????????????
     */
    @ValidateParameter
    protected Map<String, String> parameters;
    /**
     * ????????????????????????????????????????????????????????????-1??????????????????????????????0?????????????????????????????????
     */
    protected Integer concurrency = -1;
    /**
     * ????????????????????????(jsr303)
     */
    protected Boolean validation;
    /**
     * ?????????????????????????????????
     */
    @ValidatePlugin(extensible = Compression.class, name = "COMPRESSION")
    protected String compress;
    /**
     * ????????????????????????
     */
    protected Boolean cache;
    /**
     * ????????????????????????
     */
    @ValidatePlugin(extensible = CacheFactory.class, name = "CACHE")
    protected String cacheProvider;
    /**
     * cache key ?????????
     */
    @ValidatePlugin(extensible = CacheKeyGenerator.class, name = "CACHE_KEY_GENERATOR")
    protected String cacheKeyGenerator;
    /**
     * cache????????????
     */
    protected Long cacheExpireTime;
    /**
     * cache????????????
     */
    protected Integer cacheCapacity;
    /**
     * ?????????????????????
     */
    protected Boolean cacheNullable;
    /**
     * ???????????????????????????
     */
    protected transient Configure configure;
    /**
     * ?????????????????????T?????????????????????????????????
     */
    protected transient volatile Class interfaceClass;
    /**
     * ??????
     */
    protected transient volatile String name;
    /**
     * ?????????????????????
     */
    protected transient List<ConfigHandler> configHandlers = new CopyOnWriteArrayList<>();

    public AbstractInterfaceConfig() {
    }

    public AbstractInterfaceConfig(AbstractInterfaceConfig config) {
        super(config);
        this.interfaceClazz = config.interfaceClazz;
        this.alias = config.alias;
        this.filter = config.filter;
        this.methods = config.methods;
        this.register = config.register;
        this.subscribe = config.subscribe;
        this.timeout = config.timeout;
        this.proxy = config.proxy;
        this.parameters = config.parameters;
        this.concurrency = config.concurrency;
        this.validation = config.validation;
        this.compress = config.compress;
        this.cacheProvider = config.cacheProvider;
        this.cacheKeyGenerator = config.cacheKeyGenerator;
        this.cache = config.cache;
        this.cacheExpireTime = config.cacheExpireTime;
        this.cacheCapacity = config.cacheCapacity;
        this.cacheNullable = config.cacheNullable;
        this.name = config.name;
        this.interfaceClass = config.interfaceClass;
        this.configure = config.configure;
    }

    /**
     * ???????????????
     *
     * @return
     */
    public Class getInterfaceClass() {
        return interfaceClass;
    }

    public void setInterfaceClass(Class interfaceClass) {
        this.interfaceClass = interfaceClass;
        if (interfaceClass != null) {
            if (interfaceClazz == null || interfaceClazz.isEmpty()) {
                interfaceClazz = interfaceClass.getName();
            }
        }
    }

    /**
     * ???????????????
     *
     * @return
     */
    public Class<?> getProxyClass() {
        return getInterfaceClass();
    }

    /**
     * ????????????????????????
     *
     * @return ???????????? string
     */
    public abstract String name();

    public String getInterfaceClazz() {
        return interfaceClazz;
    }

    @Alias("interface")
    public void setInterfaceClazz(String interfaceClazz) {
        this.interfaceClazz = interfaceClazz;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public boolean isRegister() {
        return register;
    }

    public void setRegister(boolean register) {
        this.register = register;
    }

    public boolean isSubscribe() {
        return subscribe;
    }

    public void setSubscribe(boolean subscribe) {
        this.subscribe = subscribe;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Integer concurrency) {
        this.concurrency = concurrency;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public String getCompress() {
        return compress;
    }

    public void setCompress(String compress) {
        this.compress = compress;
    }

    public void setValidation(Boolean validation) {
        this.validation = validation;
    }

    public Boolean getValidation() {
        return validation;
    }

    /**
     * add methods.
     *
     * @param methods the methods
     */
    public void addMethods(List<MethodConfig> methods) {
        if (this.methods == null) {
            this.methods = new ConcurrentHashMap<>();
        }
        if (methods != null) {
            for (MethodConfig config : methods) {
                this.methods.put(config.getName(), config);
            }
        }
    }

    /**
     * set methods.
     *
     * @param methods
     */
    public void setMethods(List<MethodConfig> methods) {
        if (this.methods == null) {
            this.methods = new ConcurrentHashMap<>();
        } else {
            this.methods.clear();
        }
        if (methods != null) {
            for (MethodConfig config : methods) {
                this.methods.put(config.getName(), config);
            }
        }
    }

    /**
     * Sets parameter.
     *
     * @param key   the key
     * @param value the value
     */
    public void setParameter(final String key, final String value) {
        if (parameters == null) {
            parameters = new HashMap<>();
        }
        if (key == null) {
            return;
        } else if (value == null) {
            parameters.remove(key);
        } else {
            parameters.put(key, value);
        }
    }

    /**
     * Sets parameter.
     *
     * @param key   the key
     * @param value the value
     */
    public void setParameter(final String key, final Number value) {
        setParameter(key, value == null ? null : value.toString());
    }

    /**
     * Gets parameter.
     *
     * @param key the key
     * @return the value
     */
    public String getParameter(final String key) {
        return parameters == null || key == null ? null : parameters.get(key);
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public String getCacheProvider() {
        return cacheProvider;
    }

    public void setCacheProvider(String cacheProvider) {
        this.cacheProvider = cacheProvider;
    }

    public String getCacheKeyGenerator() {
        return cacheKeyGenerator;
    }

    public void setCacheKeyGenerator(String cacheKeyGenerator) {
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    public Boolean getCache() {
        return cache;
    }

    public void setCache(Boolean cache) {
        this.cache = cache;
    }

    public Long getCacheExpireTime() {
        return cacheExpireTime;
    }

    public void setCacheExpireTime(Long cacheExpireTime) {
        this.cacheExpireTime = cacheExpireTime;
    }

    public Integer getCacheCapacity() {
        return cacheCapacity;
    }

    public void setCacheCapacity(Integer cacheCapacity) {
        this.cacheCapacity = cacheCapacity;
    }

    public Boolean getCacheNullable() {
        return cacheNullable;
    }

    public void setCacheNullable(Boolean cacheNullable) {
        this.cacheNullable = cacheNullable;
    }

    public Configure getConfigure() {
        return configure;
    }

    public void setConfigure(Configure configure) {
        this.configure = configure;
    }

    /**
     * ?????????????????????
     *
     * @param handler
     */
    public void addEventHandler(final ConfigHandler handler) {
        if (handler != null) {
            configHandlers.add(handler);
        }
    }

    /**
     * ?????????????????????
     *
     * @param handler
     */
    public void removeEventHandler(final ConfigHandler handler) {
        if (handler != null) {
            configHandlers.remove(handler);
        }
    }

    /**
     * ?????????????????????
     *
     * @return
     */
    protected ProxyFactory getProxyFactory() {
        return PROXY.getOrDefault(proxy == null || proxy.isEmpty() ? DEFAULT_PROXY : proxy);
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param remoteUrls
     */
    protected static String getLocalHost(String remoteUrls) {
        InetSocketAddress remote = null;
        if (!remoteUrls.isEmpty()) {
            int nodeEnd = remoteUrls.indexOf(",");
            int shardEnd = remoteUrls.indexOf(";");
            int end = Math.min(nodeEnd, shardEnd);
            end = end > 0 ? end : Math.max(nodeEnd, shardEnd);
            String delimiter = end == nodeEnd ? "," : ";";
            String remoteUrlString = end > 0 ? remoteUrls.substring(0, remoteUrls.indexOf(delimiter)) : remoteUrls;
            URL remoteUrl = URL.valueOf(remoteUrlString, GlobalContext.getString(PROTOCOL_KEY));
            remote = new InetSocketAddress(remoteUrl.getHost(), remoteUrl.getPort());
            if ("http".equals(remoteUrl.getProtocol()) || "https".equals(remoteUrl.getProtocol())) {
                InetAddress address = remote.getAddress();
                String ip = address.getHostAddress();
                int port = remote.getPort() == 0 ? 80 : remote.getPort();
                remote = new InetSocketAddress(ip, port);
            }
        }
        return Ipv4.getLocalIp(remote);
    }

    /**
     * ????????????????????????
     *
     * @param config ????????????
     * @param remote ?????????????????????????????????????????????????????????
     * @return
     */
    protected static ProviderConfig.ServerAddress getAddress(final ServerConfig config, final String remote) {
        String host;
        String bindIp = null;
        if (Ipv4.isLocalHost(config.getHost())) {
            //??????????????????
            host = getLocalHost(remote);
            //????????????
            bindIp = "0.0.0.0";
        } else {
            host = config.getHost();
        }
        int port = config.getPort() == null ? PORT_OPTION.getValue() : config.getPort();
        return new ProviderConfig.ServerAddress(host, bindIp, port);
    }

    /**
     * ????????????????????????
     *
     * @param configs
     * @return
     */
    protected static List<URL> parse(final List<RegistryConfig> configs) {
        List<URL> result = new ArrayList<>(configs.size());
        for (RegistryConfig config : configs) {
            result.add(parse(config));
        }
        return result;
    }

    /**
     * ????????????????????????
     *
     * @param config ??????????????????
     * @return url
     */
    protected static URL parse(final RegistryConfig config) {
        Map<String, String> parameters = new HashMap<>();
        //??????????????????
        GlobalContext.getContext().forEach((key, value) -> parameters.put(key, value.toString()));
        if (isNotEmpty(config.getId())) {
            parameters.put(REGISTRY_NAME_KEY, config.getId());
        }
        //????????????????????????????????????
        String address = config.getAddress();
        if (isNotEmpty(address)) {
            parameters.put(Constants.ADDRESS_OPTION.getName(), address);
        }
        //regConfig????????????
        config.addAttribute2Map(parameters);
        //??????????????????url
        return new URL(config.getRegistry(), Ipv4.ANYHOST, 0, parameters);
    }

    @Override
    protected Map<String, String> addAttribute2Map(final Map<String, String> params) {
        super.addAttribute2Map(params);
        addElement2Map(params, Constants.INTERFACE_CLAZZ_OPTION, interfaceClazz);
        addElement2Map(params, Constants.ALIAS_OPTION, alias);
        addElement2Map(params, Constants.FILTER_OPTION, filter);
        //register???subscribe????????????true?????????url????????????true?????????????????????params
        if (!register) {
            addElement2Map(params, Constants.REGISTER_OPTION, register);
        }
        if (!subscribe) {
            addElement2Map(params, Constants.SUBSCRIBE_OPTION, subscribe);
        }
        addElement2Map(params, Constants.TIMEOUT_OPTION, timeout);
        addElement2Map(params, Constants.PROXY_OPTION, proxy);
        addElement2Map(params, Constants.VALIDATION_OPTION, validation);
        addElement2Map(params, Constants.COMPRESS_OPTION, compress);
        addElement2Map(params, Constants.CONCURRENCY_OPTION, concurrency);
        addElement2Map(params, Constants.CACHE_OPTION, cache);
        addElement2Map(params, Constants.CACHE_EXPIRE_TIME_OPTION, cacheExpireTime);
        addElement2Map(params, Constants.CACHE_PROVIDER_OPTION, cacheProvider);
        addElement2Map(params, Constants.CACHE_KEY_GENERATOR_OPTION, cacheKeyGenerator);
        addElement2Map(params, Constants.CACHE_CAPACITY_OPTION, cacheCapacity);
        addElement2Map(params, Constants.CACHE_NULLABLE_OPTION, cacheNullable);

        if (null != parameters) {
            parameters.forEach((k, v) -> addElement2Map(params, k, v));
        }
        if (null != methods) {
            methods.forEach((k, v) -> v.addAttribute2Map(params));
        }
        return params;
    }

    /**
     * ????????????????????????
     *
     * @param event
     */
    protected void publish(final ConfigEvent event) {
        if (event != null && !configHandlers.isEmpty()) {
            for (ConfigHandler handler : configHandlers) {
                try {
                    handler.handle(event);
                } catch (Throwable e) {
                    logger.error("Error occurs while publish config event. caused by " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * ???????????????
     *
     * @return
     */
    protected boolean isClose() {
        return Shutdown.isShutdown();
    }

    /**
     * ??????????????????
     *
     * @param <T>
     */
    protected abstract static class AbstractController<T extends AbstractInterfaceConfig> {
        //TODO ?????????????????????URL
        /**
         * ??????
         */
        protected T config;
        /**
         * ??????????????????
         */
        protected URL url;
        /**
         * ????????????
         */
        protected volatile URL serviceUrl;
        /**
         * ?????????URL
         */
        protected volatile URL subscribeUrl;
        /**
         * consumer bean ??????
         */
        protected CompletableFuture<URL> waitingConfig = new CompletableFuture<>();
        /**
         * ????????????????????????????????????????????????????????????????????????????????????
         */
        protected Configure configureRef;
        /**
         * ??????????????????????????????
         */
        protected ConfigHandler configHandler = this::onConfigEvent;
        /**
         * ????????????????????????
         */
        protected final AtomicBoolean updateOwner = new AtomicBoolean(false);
        /**
         * ????????????
         */
        protected AtomicReference<Map<String, String>> events = new AtomicReference<>();
        /**
         * ??????????????????2
         */
        protected String updateTask;
        /**
         * ????????????
         */
        protected Map<String, String> attributes;
        /**
         * ????????????
         */
        protected long version = Long.MIN_VALUE;

        public AbstractController(T config) {
            this.config = config;
            this.updateTask = "UpdateTask-" + config.name();
        }

        /**
         * ??????
         */
        public void broken() {
            if (!waitingConfig.isDone()) {
                waitingConfig.completeExceptionally(new InitializationException("Unexport interrupted waiting config."));
            }
        }

        /**
         * ??????????????????
         *
         * @return
         */
        protected boolean isClose() {
            return config.isClose();
        }

        /**
         * ???????????????
         *
         * @return
         */
        protected abstract boolean isOpened();

        /**
         * ????????????
         *
         * @param updates
         * @return
         */
        protected boolean isChanged(final Map<String, String> updates) {
            if (updates.isEmpty()) {
                return attributes != null && !attributes.isEmpty();
            } else {
                return attributes == null || attributes.size() != updates.size() || !updates.equals(attributes);
            }
        }

        /**
         * ?????????????????????????????????????????????
         *
         * @param event
         */
        protected void onConfigEvent(final ConfigEvent event) {
            if (isClose()) {
                return;
            }
            if (event.getVersion() <= version) {
                //??????????????????
                return;
            }
            //????????????????????????
            Map<String, String> updates = event.getDatum();
            if (!waitingConfig.isDone()) {
                //??????URL??????
                logger.info("Success subscribing global config " + config.name());
                attributes = updates;
                //?????????????????????????????????????????????????????????
                waitingConfig.complete(configure(updates));
            } else if (!isChanged(updates)) {
                //????????????
                return;
            } else {
                //???????????????
                String alias = updates.get(Constants.ALIAS_OPTION.getName());
                if (alias == null || alias.isEmpty()) {
                    return;
                }
                //????????????????????????????????????????????????????????????????????????
                attributes = new HashMap<>(updates);
                //?????????????????????????????????????????????Invoker??????
                updates.put(Constants.COUNTER, String.valueOf(event.getVersion()));
                events.set(updates);
                if (isOpened()) {
                    //?????????????????????????????????????????????????????????????????????
                    update();
                }
            }
            config.publish(event);
        }

        /**
         * ???
         *
         * @param source
         * @param target
         * @param consumer
         */
        protected <U> void chain(final CompletableFuture<U> source, final CompletableFuture<Void> target, final Consumer<U> consumer) {
            source.whenComplete((v, e) -> {
                if (e != null) {
                    target.completeExceptionally(e);
                } else if (isClose()) {
                    target.completeExceptionally(new InitializationException("Status is illegal."));
                } else if (consumer != null) {
                    consumer.accept(v);
                } else {
                    target.complete(null);
                }
            });
        }

        /**
         * ????????????
         */
        protected void update() {
            //?????????????????????????????????
            if (!isClose() && events.get() != null && updateOwner.compareAndSet(false, true)) {
                //?????????????????????
                timer().add(updateTask, SystemClock.now() + 200, () -> {
                    //?????????????????????
                    Map<String, String> updates = events.get();
                    if (!isClose() && updates != null) {
                        //????????????URL????????????
                        update(configure(updates)).whenComplete((v, t) -> {
                            events.compareAndSet(updates, null);
                            updateOwner.set(false);
                            if (!isClose()) {
                                if (t != null) {
                                    logger.error(String.format("Error occurs while updating attribute. caused by %s. %s", t.getMessage(), config.name()));
                                }
                                //????????????????????????
                                update();
                            }
                        });
                    }
                });
            }
        }

        /**
         * ??????????????????
         *
         * @param newUrl ?????????URL
         */
        protected abstract CompletableFuture<Void> update(final URL newUrl);

        /**
         * ???????????????????????????
         *
         * @param registry
         * @param subscribed
         */
        protected CompletableFuture<Void> subscribe(final Registry registry, final AtomicBoolean subscribed) {
            final CompletableFuture<Void> future = new CompletableFuture<>();
            if (!config.subscribe && !config.register) {
                future.complete(null);
            } else if (!config.register && configureRef != null && configureRef != registry) {
                //????????????????????????????????????????????????????????????
                if (subscribed.compareAndSet(false, true)) {
                    subscribeUrl = buildSubscribedUrl(configureRef, serviceUrl);
                    configureRef.subscribe(subscribeUrl, configHandler);
                }
                future.complete(null);
            } else {
                registry.open().whenComplete((v, t) -> {
                    if (t != null) {
                        future.completeExceptionally(new InitializationException(
                                String.format("Registry open error. %s", registry.getUrl().toString(false, false))));
                    } else if (subscribed.compareAndSet(false, true)) {
                        //????????????????????????
                        if (configureRef == null) {
                            configureRef = config.configure == null ? registry : config.configure;
                        }
                        //????????????
                        if (config.subscribe) {
                            subscribeUrl = buildSubscribedUrl(configureRef, serviceUrl);
                            logger.info("Start subscribing global config " + config.name());
                            configureRef.subscribe(subscribeUrl, configHandler);
                        }
                        future.complete(null);
                    }
                });
            }
            return future;
        }

        /**
         * ???????????????????????????????????????????????????
         *
         * @param newUrl ????????????URL
         * @param force  ?????????????????????
         */
        protected boolean resubscribe(final URL newUrl, final boolean force) {
            URL oldUrl = subscribeUrl;
            //???????????????????????????
            if (configureRef == null || oldUrl == newUrl) {
                return false;
            }
            //??????????????????
            boolean oldSubscribe = oldUrl == null ? false : oldUrl.getBoolean(Constants.SUBSCRIBE_OPTION);
            String oldAlias = oldUrl == null ? null : oldUrl.getString(Constants.ALIAS_OPTION.getName());
            boolean newSubscribe = newUrl == null ? false : newUrl.getBoolean(Constants.SUBSCRIBE_OPTION);
            String newAlias = newUrl == null ? null : newUrl.getString(Constants.ALIAS_OPTION.getName());
            if (newSubscribe && oldSubscribe && !force && Objects.equals(oldAlias, newAlias)) {
                //????????????????????????????????????
                return false;
            } else if (newSubscribe) {
                //???????????????
                if (oldSubscribe) {
                    //??????????????????
                    configureRef.unsubscribe(oldUrl, configHandler);
                }
                logger.info("Start resubscribing config " + config.name());
                configureRef.subscribe(newUrl, configHandler);
                subscribeUrl = newUrl;
                return true;
            } else if (oldSubscribe) {
                configureRef.unsubscribe(oldUrl, configHandler);
                subscribeUrl = null;
                return true;
            } else {
                subscribeUrl = null;
                return false;
            }
        }

        /**
         * ????????????
         *
         * @param updates ??????????????????
         * @return
         */
        protected URL configure(final Map<String, String> updates) {
            Map<String, String> result = new HashMap<>(32);
            //???????????????????????????
            String path = url.getPath();
            //????????????????????????
            Configurator.update(GlobalContext.getContext(), result, GLOBAL_ALLOWED);
            //???????????????????????????????????????????????????????????????
            Configurator.update(GlobalContext.getInterfaceConfig(Constants.GLOBAL_SETTING), result, GLOBAL_ALLOWED);
            //????????????????????????,?????????????????????????????????????????????????????????????????????????????????
            Map<String, String> parameters = url.getParameters();
            parameters.remove(Region.DATA_CENTER);
            parameters.remove(Region.REGION);
            result.putAll(parameters);
            //???????????????????????????????????????
            Configurator.update(GlobalContext.getInterfaceConfig(path), result, GLOBAL_ALLOWED);
            //????????????
            CONFIGURATOR.extensions().forEach(o -> Configurator.update(o.configure(path), result, CONFIG_ALLOWED));
            //????????????
            Configurator.update(updates, result, CONFIG_ALLOWED);

            return new URL(url.getProtocol(), url.getUser(), url.getPassword(), url.getHost(), url.getPort(), path, result);
        }

        /**
         * ???????????????URL
         *
         * @param url
         * @return
         */
        protected URL buildRegisteredUrl(final Registry registry, final URL url) {
            return registry == null ? null : registry.normalize(url);
        }

        /**
         * ?????????????????????URL
         *
         * @param url
         * @return
         */
        protected URL buildSubscribedUrl(final Configure configure, final URL url) {
            return configure == null ? null : configure.normalize(url);
        }

        public URL getServiceUrl() {
            return serviceUrl;
        }
    }

}
