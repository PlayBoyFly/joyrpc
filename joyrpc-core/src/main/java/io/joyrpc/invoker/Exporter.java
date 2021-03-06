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

import io.joyrpc.Invoker;
import io.joyrpc.InvokerAware;
import io.joyrpc.Result;
import io.joyrpc.cluster.discovery.config.ConfigHandler;
import io.joyrpc.cluster.discovery.config.Configure;
import io.joyrpc.cluster.discovery.registry.Registry;
import io.joyrpc.config.ConfigAware;
import io.joyrpc.config.InterfaceOption;
import io.joyrpc.config.InterfaceOption.MethodOption;
import io.joyrpc.config.ProviderConfig;
import io.joyrpc.config.Warmup;
import io.joyrpc.constants.Constants;
import io.joyrpc.constants.ExceptionCode;
import io.joyrpc.context.RequestContext;
import io.joyrpc.event.Publisher;
import io.joyrpc.exception.InitializationException;
import io.joyrpc.exception.ShutdownExecption;
import io.joyrpc.extension.URL;
import io.joyrpc.invoker.ExporterEvent.EventType;
import io.joyrpc.permission.Authentication;
import io.joyrpc.permission.Authorization;
import io.joyrpc.permission.Identification;
import io.joyrpc.protocol.message.Invocation;
import io.joyrpc.protocol.message.RequestMessage;
import io.joyrpc.proxy.MethodCaller;
import io.joyrpc.transport.DecoratorServer;
import io.joyrpc.transport.Server;
import io.joyrpc.transport.transport.ServerTransport;
import io.joyrpc.util.Close;
import io.joyrpc.util.Futures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static io.joyrpc.Plugin.*;
import static io.joyrpc.constants.Constants.FILTER_CHAIN_FACTORY_OPTION;

/**
 * @date: 15/1/2019
 */
public class Exporter extends AbstractInvoker {

    private static final Logger logger = LoggerFactory.getLogger(Exporter.class);
    /**
     * ??????
     */
    protected ProviderConfig<?> config;
    /**
     * ????????????
     */
    protected String compress;
    /**
     * ?????????URL
     */
    protected List<URL> registerUrls;
    /**
     * ?????????????????????
     */
    protected Consumer<Exporter> closing;
    /**
     * ?????????????????????
     */
    protected ConfigHandler configHandler;
    /**
     * ??????
     */
    protected Server server;
    /**
     * ????????????
     */
    protected CallbackContainer container;
    /**
     * ??????
     */
    protected int port;
    /**
     * ????????????
     */
    protected Object ref;
    /**
     * ?????????
     */
    protected Invoker chain;
    /**
     * ????????????
     */
    protected List<Registry> registries;
    /**
     * ???????????????????????????
     */
    protected Registry subscribe;
    /**
     * ???????????????
     */
    protected Identification identification;
    /**
     * ????????????
     */
    protected Authentication authentication;
    /**
     * ????????????
     */
    protected Authorization authorization;
    /**
     * ??????
     */
    protected Warmup warmup;
    /**
     * ???????????????
     */
    protected Publisher<ExporterEvent> publisher;
    /**
     * ????????????
     */
    protected InterfaceOption options;

    /**
     * ????????????
     *
     * @param name          ??????
     * @param url           ??????URL
     * @param config        ?????????????????????
     * @param registries    ????????????
     * @param registerUrls  ?????????URL
     * @param configure     ??????
     * @param subscribeUrl  ???????????????URL
     * @param configHandler ???????????????
     * @param server        ??????
     * @param container     ????????????
     * @param publisher     ????????????
     * @param closing       ???????????????
     */
    protected Exporter(final String name,
                       final URL url,
                       final ProviderConfig<?> config,
                       final List<Registry> registries,
                       final List<URL> registerUrls,
                       final Configure configure,
                       final URL subscribeUrl,
                       final ConfigHandler configHandler,
                       final Server server,
                       final CallbackContainer container,
                       final Publisher<ExporterEvent> publisher,
                       final Consumer<Exporter> closing) {
        this.name = name;
        this.config = config;
        this.registries = registries;
        this.registerUrls = registerUrls;
        this.configure = configure;
        this.url = url;
        this.subscribeUrl = subscribeUrl;
        this.configHandler = configHandler;
        this.server = server;
        this.container = container;
        this.closing = closing;

        //??????
        this.alias = url.getString(Constants.ALIAS_OPTION);
        //????????????
        this.interfaceClass = config.getProxyClass();
        //?????????????????????
        this.interfaceName = url.getPath();
        //????????????????????????????????????????????????????????????????????????
        this.ref = config.getRef();
        this.warmup = config.getWarmup();
        this.port = url.getPort();
        this.compress = url.getString(Constants.COMPRESS_OPTION.getName());
        this.options = INTERFACE_OPTION_FACTORY.get().create(interfaceClass, interfaceName, url, ref);
        this.chain = FILTER_CHAIN_FACTORY.getOrDefault(url.getString(FILTER_CHAIN_FACTORY_OPTION))
                .build(this, this::invokeMethod);
        this.identification = IDENTIFICATION.get(url.getString(Constants.IDENTIFICATION_OPTION));
        this.authentication = AUTHENTICATOR.get(url.getString(Constants.AUTHENTICATION_OPTION));
        this.authorization = AUTHORIZATION.get(url.getString(Constants.AUTHORIZATION_OPTION));
        this.publisher = publisher;
        this.publisher.offer(new ExporterEvent(EventType.INITIAL, name, this));
        //????????????????????????
        if (authentication != null && authentication instanceof InvokerAware) {
            setup((InvokerAware) authentication);
        }
        if (authorization != null && authorization instanceof InvokerAware) {
            setup((InvokerAware) authorization);
        }
    }

    @Override
    protected CompletableFuture<Void> doOpen() {
        CompletableFuture<Void> result = new CompletableFuture<>();

        warmup().whenComplete((v, t) -> {
            if (t != null) {
                //??????????????????????????????
                result.completeExceptionally(t);
            } else {
                if (warmup != null) {
                    logger.info("Success warmuping provider " + name);
                }
                server.open(r -> {
                    if (!r.isSuccess()) {
                        result.completeExceptionally(new InitializationException(String.format("Error occurs while open server : %s error", name), r.getThrowable()));
                    } else {
                        //????????????????????????
                        configAware().whenComplete((o, s) -> {
                            if (s != null) {
                                result.completeExceptionally(new InitializationException(String.format("Error occurs while setup server : %s error", name), r.getThrowable()));
                            } else {
                                //????????????
                                Futures.chain(doRegister(registries), result);
                            }
                        });
                    }
                });
            }
        });
        return result;
    }

    /**
     * ??????
     *
     * @return
     */
    protected CompletableFuture<Void> warmup() {
        return warmup == null ? CompletableFuture.completedFuture(null) : warmup.setup(config);
    }

    /**
     * ????????????
     *
     * @return
     */
    protected CompletableFuture<Void> configAware() {
        if (server instanceof ConfigAware) {
            return ((ConfigAware) server).setup(config);
        } else if (server instanceof DecoratorServer) {
            ServerTransport transport = ((DecoratorServer) server).getTransport();
            while (transport != null) {
                if (transport instanceof ConfigAware) {
                    return ((ConfigAware) transport).setup(config);
                } else if (!(transport instanceof DecoratorServer)) {
                    return CompletableFuture.completedFuture(null);
                } else {
                    transport = ((DecoratorServer) transport).getTransport();
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected CompletableFuture<Void> doClose() {
        //????????????????????????????????????????????????
        options.close();
        publisher.offer(new ExporterEvent(EventType.CLOSE, name, this));
        CompletableFuture<Void> future1 = deregister().whenComplete((v, t) -> logger.info("Success deregister provider config " + name));
        CompletableFuture<Void> future2 = unsubscribe().whenComplete((v, t) -> logger.info("Success unsubscribe provider config " + name));
        //????????????
        CompletableFuture<Void> future3 = new CompletableFuture<>();
        if (server != null) {
            server.close(o -> {
                //????????????????????????????????????
                Close.close(server.getBizThreadPool(), 0);
                future3.complete(null);
            });
        } else {
            future3.complete(null);
        }
        return CompletableFuture.allOf(future1, future2, future3).whenComplete((v, t) -> {
            if (closing != null) {
                closing.accept(this);
            }
            logger.info("Success close provider config " + name);
        });

    }

    @Override
    protected Throwable shutdownException() {
        return new ShutdownExecption("provider is shutdown", ExceptionCode.PROVIDER_OFFLINE, true);
    }

    @Override
    protected CompletableFuture<Result> doInvoke(final RequestMessage<Invocation> request) {
        Invocation invocation = request.getPayLoad();
        MethodOption option = options.getOption(invocation.getMethodName());
        //????????????????????????????????????????????????
        invocation.setClazz(interfaceClass);
        invocation.setMethod(option.getMethod());
        //??????????????????????????????Validate
        invocation.setObject(ref);
        //?????????????????????????????????
        request.setAuthentication(authentication);
        request.setIdentification(identification);
        request.setAuthorization(authorization);
        request.setOption(option);

        //??????????????????
        RequestContext context = request.getContext();
        context.setAsync(option.isAsync());
        context.setProvider(true);
        //???????????????????????????????????????????????????
        context.setAttachments(option.getImplicits());
        //????????????
        if (option.getCallback() != null) {
            container.addCallback(request, request.getTransport());
        }

        //???????????????
        return chain.invoke(request);
    }

    /**
     * ????????????
     *
     * @param request
     * @return
     */
    protected CompletableFuture<Result> invokeMethod(final RequestMessage<Invocation> request) {

        Invocation invocation = request.getPayLoad();

        CompletableFuture<Result> resultFuture;
        //???????????????????????????????????????????????????????????????
        RequestContext context = request.getContext();
        RequestContext.restore(context);
        try {
            MethodCaller caller = ((InterfaceOption.ProviderMethodOption) request.getOption()).getCaller();
            // ?????? ????????????????????????
            Object value = caller != null ? caller.invoke(invocation.getArgs()) : invocation.invoke(ref);
            resultFuture = CompletableFuture.completedFuture(new Result(context, value));
        } catch (IllegalArgumentException | IllegalAccessException e) { // ??????????????????????????????????????????????????????
            resultFuture = CompletableFuture.completedFuture(new Result(context, e));
        } catch (InvocationTargetException e) { // ????????????????????????
            resultFuture = CompletableFuture.completedFuture(new Result(context, e.getCause()));
        } finally {
            RequestContext.remove();
        }

        return resultFuture;
    }

    /**
     * ??????
     *
     * @return
     */
    protected CompletableFuture<Void> doRegister(final List<Registry> registries) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!url.getBoolean(Constants.REGISTER_OPTION)) {
            result.complete(null);
        } else {
            //???????????????
            CompletableFuture<?>[] futures = new CompletableFuture[registries.size()];
            for (int i = 0; i < registries.size(); i++) {
                futures[i] = registries.get(i).register(url);
            }
            //??????????????????
            CompletableFuture.allOf(futures).whenComplete((v, t) -> {
                if (t == null) {
                    publisher.offer(new ExporterEvent(EventType.OPEN, name, this));
                    result.complete(null);
                } else {
                    result.completeExceptionally(new InitializationException(String.format("Open registry : %s error", url), t));
                }
            });
        }
        return result;
    }

    /**
     * ??????????????????
     *
     * @return
     */
    protected CompletableFuture<Void> unsubscribe() {
        if (url.getBoolean(Constants.SUBSCRIBE_OPTION)) {
            //????????????
            // todo ????????????????????????
            configure.unsubscribe(subscribeUrl, configHandler);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * ??????
     *
     * @return
     */
    protected CompletableFuture<Void> deregister() {
        if (registries == null || registries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        } else {
            CompletableFuture<?>[] futures = new CompletableFuture[registries.size()];
            //???????????????????????????1???
            for (int i = 0; i < registries.size(); i++) {
                futures[i] = registries.get(i).deregister(registerUrls.get(i), 1);
            }
            return CompletableFuture.allOf(futures);
        }
    }

    public ProviderConfig<?> getConfig() {
        return config;
    }

    public String getCompress() {
        return compress;
    }

    public Server getServer() {
        return server;
    }

    public List<Registry> getRegistries() {
        return registries;
    }

    public int getPort() {
        return port;
    }

    public Authentication getAuthentication() {
        return authentication;
    }

    public Identification getIdentification() {
        return identification;
    }

    public Authorization getAuthorization() {
        return authorization;
    }
}
