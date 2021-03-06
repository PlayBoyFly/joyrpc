package io.joyrpc.config.inner;

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

import io.joyrpc.cluster.Shard;
import io.joyrpc.cluster.distribution.ExceptionPolicy;
import io.joyrpc.cluster.distribution.ExceptionPredication;
import io.joyrpc.cluster.distribution.FailoverPolicy;
import io.joyrpc.cluster.distribution.FailoverPolicy.DefaultFailoverPolicy;
import io.joyrpc.cluster.distribution.Router;
import io.joyrpc.cluster.distribution.loadbalance.adaptive.AdaptiveConfig;
import io.joyrpc.cluster.distribution.loadbalance.adaptive.AdaptivePolicy;
import io.joyrpc.cluster.distribution.loadbalance.adaptive.Judge;
import io.joyrpc.config.AbstractInterfaceOption;
import io.joyrpc.context.IntfConfiguration;
import io.joyrpc.exception.InitializationException;
import io.joyrpc.extension.ExtensionMeta;
import io.joyrpc.extension.URL;
import io.joyrpc.extension.WrapperParametric;
import io.joyrpc.invoker.CallbackMethod;
import io.joyrpc.permission.BlackWhiteList;
import io.joyrpc.permission.ExceptionBlackWhiteList;
import io.joyrpc.protocol.message.Invocation;
import io.joyrpc.protocol.message.RequestMessage;
import io.joyrpc.util.ClassUtils;
import io.joyrpc.util.SystemClock;
import io.joyrpc.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Validator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.joyrpc.Plugin.*;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.constants.ExceptionCode.CONSUMER_FAILOVER_CLASS;
import static io.joyrpc.context.adaptive.AdaptiveConfiguration.ADAPTIVE;
import static io.joyrpc.context.mock.MockConfiguration.MOCK;
import static io.joyrpc.context.router.SelectorConfiguration.SELECTOR;
import static io.joyrpc.util.ClassUtils.forName;
import static io.joyrpc.util.ClassUtils.isReturnFuture;
import static io.joyrpc.util.StringUtils.SEMICOLON_COMMA_WHITESPACE;
import static io.joyrpc.util.StringUtils.split;
import static io.joyrpc.util.Timer.timer;

/**
 * ?????????????????????
 */
public class InnerConsumerOption extends AbstractInterfaceOption {

    private static final Logger logger = LoggerFactory.getLogger(InnerConsumerOption.class);

    /**
     * ??????Provider?????????META-INF/retry/xxx.xxx.xxx.xx?????????????????????
     */
    protected static final Map<String, Set<String>> INNER_EXCEPTIONS = new ConcurrentHashMap<>();
    /**
     * ??????????????????????????????????????????
     */
    protected static final String RETRY_RESOURCE_PATH = "META-INF/retry/";
    /**
     * ??????????????????
     */
    protected Consumer<Router> configure;
    /**
     * ????????????????????????????????????????????????????????????????????????????????????
     */
    protected BiFunction<String, AdaptiveConfig, AdaptiveConfig> scorer;
    /**
     * ??????????????????????????????
     */
    protected int maxRetry;
    /**
     * ???????????????????????????????????????
     */
    protected boolean retryOnlyOncePerNode;
    /**
     * ??????????????????????????????????????????
     */
    protected String failoverSelector;
    /**
     * ????????????
     */
    protected String failoverPredication;
    /**
     * ???????????????
     */
    protected BiPredicate<Shard, RequestMessage<Invocation>> selector;
    /**
     * ?????????????????????
     */
    protected Router router;
    /**
     * ?????????????????????
     */
    protected int forks;
    /**
     * ????????????
     */
    protected BlackWhiteList<Class<? extends Throwable>> failoverBlackWhiteList;
    /**
     * ?????????????????????????????????
     */
    protected AdaptiveConfig intfConfig;
    /**
     * ?????????????????????????????????
     */
    protected IntfConfiguration<String, AdaptiveConfig> dynamicConfig;
    /**
     * ?????????????????????
     */
    protected IntfConfiguration<String, BiPredicate<Shard, RequestMessage<Invocation>>> selectorConfig;
    /**
     * MOCK??????
     */
    protected IntfConfiguration<String, Map<String, Map<String, Object>>> mockConfig;
    /**
     * ?????????
     */
    protected List<Judge> judges;

    /**
     * ????????????
     *
     * @param interfaceClass ?????????
     * @param interfaceName  ????????????
     * @param url            URL
     * @param configure      ???????????????
     * @param scorer         ????????????????????????????????????????????????????????????????????????????????????
     */
    public InnerConsumerOption(final Class<?> interfaceClass, final String interfaceName, final URL url,
                               final Consumer<Router> configure,
                               final BiFunction<String, AdaptiveConfig, AdaptiveConfig> scorer) {
        super(interfaceClass, interfaceName, url);
        this.configure = configure;
        this.scorer = scorer;
        setup();
        buildOptions();
    }

    @Override
    protected void setup() {
        super.setup();
        this.maxRetry = url.getInteger(RETRIES_OPTION);
        this.retryOnlyOncePerNode = url.getBoolean(RETRY_ONLY_ONCE_PER_NODE_OPTION);
        this.failoverSelector = url.getString(FAILOVER_SELECTOR_OPTION);
        this.failoverPredication = url.getString(FAILOVER_PREDICATION_OPTION);
        //????????????failoverPredication??????????????????????????????????????????????????????failoverPredication
        this.failoverBlackWhiteList = buildFailoverBlackWhiteList();
        this.forks = url.getInteger(FORKS_OPTION);
        this.mockConfig = new IntfConfiguration<>(MOCK, interfaceName, config -> {
            if (options != null) {
                options.forEach((method, mo) -> {
                    InnerConsumerMethodOption cmo = ((InnerConsumerMethodOption) mo);
                    cmo.mock = config == null ? null : config.get(method);
                });
            }
        });
        //????????????
        if (configure != null) {
            this.selectorConfig = new IntfConfiguration<>(SELECTOR, interfaceName, v -> this.selector = v);
            this.router = ROUTER.get(url.getString(ROUTER_OPTION));
            configure.accept(router);
        }
        if (scorer != null) {
            this.intfConfig = new AdaptiveConfig(url);
            this.judges = new LinkedList<>();
            JUDGE.extensions().forEach(judges::add);
            //???????????????????????????????????????
            this.dynamicConfig = new IntfConfiguration<>(ADAPTIVE, interfaceName,
                    config -> {
                        if (options != null) {
                            options.forEach((method, op) -> ((InnerConsumerMethodOption) op).adaptiveConfig.setDynamicIntfConfig(config));
                        }
                    });
            timer().add(new Scorer("scorer-" + interfaceName,
                    () -> {
                        //????????????????????????????????????????????????????????????????????????????????????????????????
                        AdaptiveConfig dynamic = dynamicConfig.get();
                        return !closed.get() && (intfConfig.getAvailabilityScore() == null && (dynamic == null || dynamic.getAvailabilityScore() == null)
                                || intfConfig.getConcurrencyScore() == null && (dynamic == null || dynamic.getConcurrencyScore() == null)
                                || intfConfig.getQpsScore() == null && (dynamic == null || dynamic.getQpsScore() == null)
                                || intfConfig.getTpScore() == null && (dynamic == null || dynamic.getTpScore() == null));
                    },
                    () -> {
                        options.forEach((method, mo) -> {
                            InnerConsumerMethodOption cmo = ((InnerConsumerMethodOption) mo);
                            if (cmo.autoScore) {
                                //?????????????????????????????????
                                cmo.adaptiveConfig.setScore(scorer.apply(method, cmo.adaptiveConfig.config));
                                cmo.autoScore = false;
                            }
                        });
                    }));
        }
    }

    @Override
    protected void doClose() {
        if (selectorConfig != null) {
            selectorConfig.close();
        }
        if (dynamicConfig != null) {
            dynamicConfig.close();
        }
        if (mockConfig != null) {
            mockConfig.close();
        }
    }

    @Override
    protected InnerMethodOption create(final WrapperParametric parametric) {
        Method method = getMethod(parametric.getName());
        Map<String, Map<String, Object>> methodMocks = mockConfig.get();
        return new InnerConsumerMethodOption(
                method,
                getImplicits(parametric.getName()),
                parametric.getPositive(TIMEOUT_OPTION.getName(), timeout),
                new Concurrency(parametric.getInteger(CONCURRENCY_OPTION.getName(), concurrency)),
                getCachePolicy(parametric),
                getValidator(parametric),
                parametric.getString(HIDDEN_KEY_TOKEN, token),
                method != null && isReturnFuture(interfaceClass, method),
                getCallback(method),
                parametric.getInteger(FORKS_OPTION.getName(), forks),
                () -> selector,
                getRoute(parametric),
                new DefaultFailoverPolicy(
                        parametric.getInteger(RETRIES_OPTION.getName(), maxRetry),
                        parametric.getBoolean(RETRY_ONLY_ONCE_PER_NODE_OPTION.getName(), retryOnlyOncePerNode),
                        new MyTimeoutPolicy(),
                        new MyExceptionPolicy(failoverBlackWhiteList, EXCEPTION_PREDICATION.get(failoverPredication)),
                        FAILOVER_SELECTOR.get(parametric.getString(FAILOVER_SELECTOR_OPTION.getName(), failoverSelector))),
                scorer == null ? null : new MethodAdaptiveConfig(intfConfig, new AdaptiveConfig(parametric), dynamicConfig.get(), judges),
                methodMocks == null ? null : methodMocks.get(parametric.getName()));
    }

    /**
     * ??????????????????
     *
     * @param parametric ??????
     * @return ????????????
     */
    protected Router getRoute(final WrapperParametric parametric) {
        //??????????????????
        Router methodRouter = null;
        if (configure != null) {
            methodRouter = ROUTER.get(parametric.getString(ROUTER_OPTION.getName()));
            if (methodRouter != null) {
                configure.accept(methodRouter);
            } else {
                methodRouter = router;
            }

        }
        return methodRouter;
    }


    /**
     * ?????????????????????
     *
     * @return ??????????????????
     */
    protected BlackWhiteList<Class<? extends Throwable>> buildFailoverBlackWhiteList() {
        //?????????????????????
        Set<String> names = new HashSet<>(INNER_EXCEPTIONS.computeIfAbsent(interfaceName, this::getInnerExceptions));
        //??????URL???????????????
        String value = url.getString(FAILOVER_WHEN_THROWABLE_OPTION);
        if (value != null && !value.isEmpty()) {
            String[] classes = split(value, SEMICOLON_COMMA_WHITESPACE);
            Collections.addAll(names, classes);
        }
        Set<Class<? extends Throwable>> failoverClass = new HashSet<>();
        Class<?> c;
        for (String name : names) {
            try {
                c = forName(name);
                if (!Throwable.class.isAssignableFrom(c)) {
                    logger.error("Failover exception class is not implement Throwable. " + name);
                }
                failoverClass.add((Class<? extends Throwable>) c);
            } catch (ClassNotFoundException e) {
                logger.error("Failover exception class is not found. " + name);
            }
        }
        return new ExceptionBlackWhiteList(failoverClass, null, false);
    }

    /**
     * ?????????????????????????????????
     *
     * @param interfaceName ????????????
     * @return ????????????
     */
    protected Set<String> getInnerExceptions(final String interfaceName) {
        Set<String> names = new HashSet<>();
        ClassLoader loader = ClassUtils.getCurrentClassLoader();
        String line;
        String name;
        ExtensionMeta<ExceptionPredication, String> meta;
        ExtensionMeta<ExceptionPredication, String> max = null;
        try {
            Enumeration<java.net.URL> urls = loader.getResources(RETRY_RESOURCE_PATH + interfaceName);
            while ((urls.hasMoreElements())) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    while ((line = reader.readLine()) != null) {
                        //????????????????????????
                        if (line.startsWith("[") && line.endsWith("]")) {
                            //????????????????????????????????????
                            if (failoverPredication == null || failoverPredication.isEmpty()) {
                                name = line.substring(1, line.length() - 1);
                                //??????????????????????????????????????????
                                meta = EXCEPTION_PREDICATION.meta(name);
                                if (meta != null && (max == null || max.getOrder() > meta.getOrder())) {
                                    max = meta;
                                }
                            }
                        } else {
                            //?????????
                            names.add(line);
                        }
                    }
                } catch (IOException e) {
                    throw new InitializationException(e.getMessage(), CONSUMER_FAILOVER_CLASS);
                }
            }
        } catch (IOException e) {
            throw new InitializationException(e.getMessage(), CONSUMER_FAILOVER_CLASS);
        }
        if (max != null) {
            failoverPredication = max.getExtension().getName();
        }
        return names;

    }

    /**
     * ????????????
     */
    protected static class InnerConsumerMethodOption extends InnerMethodOption implements ConsumerMethodOption {
        /**
         * ?????????
         */
        protected int forks;
        /**
         * ??????????????????????????????
         */
        protected Supplier<BiPredicate<Shard, RequestMessage<Invocation>>> selector;
        /**
         * ????????????
         */
        protected Router router;
        /**
         * ????????????
         */
        protected FailoverPolicy failoverPolicy;
        /**
         * ???????????????????????????
         */
        protected MethodAdaptiveConfig adaptiveConfig;

        /**
         * ????????????????????????????????????
         */
        protected volatile boolean autoScore;
        /**
         * Mock??????
         */
        protected volatile Map<String, Object> mock;

        public InnerConsumerMethodOption(final Method method, final Map<String, ?> implicits, final int timeout, final Concurrency concurrency,
                                         final CachePolicy cachePolicy, final Validator validator,
                                         final String token, final boolean async, final CallbackMethod callback, final int forks,
                                         final Supplier<BiPredicate<Shard, RequestMessage<Invocation>>> selector,
                                         final Router router, final FailoverPolicy failoverPolicy,
                                         final MethodAdaptiveConfig adaptiveConfig,
                                         final Map<String, Object> mock) {
            super(method, implicits, timeout, concurrency, cachePolicy, validator, token, async, callback);
            this.forks = forks;
            this.selector = selector;
            this.router = router;
            this.failoverPolicy = failoverPolicy;
            this.adaptiveConfig = adaptiveConfig;
            this.mock = mock;
        }

        @Override
        public int getForks() {
            return forks;
        }

        @Override
        public BiPredicate<Shard, RequestMessage<Invocation>> getSelector() {
            return selector == null ? null : selector.get();
        }

        @Override
        public Router getRouter() {
            return router;
        }

        @Override
        public FailoverPolicy getFailoverPolicy() {
            return failoverPolicy;
        }

        @Override
        public AdaptivePolicy getAdaptivePolicy() {
            return adaptiveConfig.getPolicy();
        }

        @Override
        public Map<String, Object> getMock() {
            return mock;
        }

        @Override
        public void setAutoScore(final boolean autoScore) {
            if (this.autoScore != autoScore) {
                this.autoScore = autoScore;
            }
        }
    }

    /**
     * ????????????
     */
    protected static class MyExceptionPolicy implements ExceptionPolicy {
        /**
         * ??????????????????
         */
        protected BlackWhiteList<Class<? extends Throwable>> failoverBlackWhiteList;
        /**
         * ????????????
         */
        protected ExceptionPredication exceptionPredication;

        /**
         * ????????????
         *
         * @param failoverBlackWhiteList ??????????????????
         * @param exceptionPredication   ????????????
         */
        public MyExceptionPolicy(final BlackWhiteList<Class<? extends Throwable>> failoverBlackWhiteList,
                                 final ExceptionPredication exceptionPredication) {
            this.failoverBlackWhiteList = failoverBlackWhiteList;
            this.exceptionPredication = exceptionPredication;
        }

        @Override
        public boolean test(final Throwable throwable) {
            //???????????????????????????????????????????????????????????????????????????????????????
            return failoverBlackWhiteList.isValid(throwable.getClass()) || (exceptionPredication != null && exceptionPredication.test(throwable));
        }
    }

    /**
     * ?????????????????????
     */
    protected static class MethodAdaptiveConfig {
        /**
         * ??????????????????
         */
        protected final AdaptiveConfig intfConfig;
        /**
         * ??????????????????
         */
        protected AdaptiveConfig dynamicIntfConfig;
        /**
         * ??????????????????
         */
        protected final AdaptiveConfig methodConfig;
        /**
         * ?????????
         */
        protected final List<Judge> judges;
        /**
         * ??????????????????
         */
        protected AdaptiveConfig score;
        /**
         * ????????????
         */
        protected volatile AdaptiveConfig config;
        /**
         * ????????????????????????????????????????????????
         */
        protected volatile AdaptivePolicy policy;

        /**
         * ????????????
         *
         * @param intfConfig        ??????????????????
         * @param methodConfig      ??????????????????
         * @param dynamicIntfConfig ??????????????????
         * @param judges            ??????
         */
        public MethodAdaptiveConfig(final AdaptiveConfig intfConfig, final AdaptiveConfig methodConfig,
                                    final AdaptiveConfig dynamicIntfConfig, final List<Judge> judges) {
            this.intfConfig = intfConfig;
            this.methodConfig = methodConfig;
            this.dynamicIntfConfig = dynamicIntfConfig;
            this.judges = judges;
            this.policy = new AdaptivePolicy(intfConfig, judges);
            update();
        }

        public void setDynamicIntfConfig(AdaptiveConfig dynamicIntfConfig) {
            if (dynamicIntfConfig != this.dynamicIntfConfig) {
                this.dynamicIntfConfig = dynamicIntfConfig;
                update();
            }
        }

        public void setScore(AdaptiveConfig score) {
            if (score != this.score) {
                this.score = score;
                update();
            }
        }

        public AdaptivePolicy getPolicy() {
            return policy;
        }

        /**
         * ??????
         */
        protected synchronized void update() {
            AdaptiveConfig result = new AdaptiveConfig(intfConfig);
            result.merge(dynamicIntfConfig);
            result.merge(methodConfig);
            //????????????
            config = new AdaptiveConfig(result);
            //???????????????????????????
            result.merge(score);
            policy = new AdaptivePolicy(result, judges);
        }
    }

    /**
     * ???????????????
     */
    protected static class Scorer implements Timer.TimeTask {
        /**
         * ??????
         */
        protected String name;
        /**
         * ???????????????
         */
        protected Supplier<Boolean> condition;
        /**
         * ????????????
         */
        protected Runnable runnable;

        public Scorer(String name, Supplier<Boolean> condition, Runnable runnable) {
            this.name = name;
            this.condition = condition;
            this.runnable = runnable;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getTime() {
            //??????????????????????????????10????????????
            return SystemClock.now() + 10000L + ThreadLocalRandom.current().nextLong(10000L);
        }

        @Override
        public void run() {
            //?????????????????????
            if (condition.get()) {
                runnable.run();
                if (condition.get()) {
                    //????????????
                    timer().add(this);
                }
            }
        }
    }

}
