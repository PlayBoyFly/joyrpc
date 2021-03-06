package io.joyrpc.spring.boot;

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

import io.joyrpc.config.AbstractConsumerConfig;
import io.joyrpc.config.AbstractIdConfig;
import io.joyrpc.config.AbstractInterfaceConfig;
import io.joyrpc.config.ConsumerGroupConfig;
import io.joyrpc.context.GlobalContext;
import io.joyrpc.spring.ConsumerBean;
import io.joyrpc.spring.ConsumerGroupBean;
import io.joyrpc.spring.ProviderBean;
import io.joyrpc.spring.boot.annotation.AnnotationProvider;
import io.joyrpc.util.Pair;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.joyrpc.spring.boot.Plugin.ANNOTATION_PROVIDER;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.genericBeanDefinition;
import static org.springframework.context.annotation.AnnotationConfigUtils.registerAnnotationConfigProcessors;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;
import static org.springframework.util.ClassUtils.getShortName;
import static org.springframework.util.ClassUtils.resolveClassName;
import static org.springframework.util.StringUtils.hasText;
import static org.springframework.util.StringUtils.isEmpty;

/**
 * ?????????????????????
 */
public class RpcDefinitionPostProcessor implements BeanDefinitionRegistryPostProcessor,
        BeanPostProcessor, BeanClassLoaderAware {

    /**
     * ????????????
     */
    public static final String SERVER_NAME = "server";
    /**
     * ??????????????????
     */
    public static final String REGISTRY_NAME = "registry";

    public static final String BEAN_NAME = "rpcDefinitionPostProcessor";
    public static final String RPC_PREFIX = "rpc";
    public static final String PROVIDER_PREFIX = "provider-";
    public static final String CONSUMER_PREFIX = "consumer-";

    protected final ConfigurableEnvironment environment;

    protected final ResourceLoader resourceLoader;

    protected final ApplicationContext applicationContext;

    protected RpcProperties rpcProperties;

    protected ClassLoader classLoader;
    /**
     * ConsumerBean??????
     */
    protected Map<String, ConsumerBean<?>> consumers = new HashMap<>();
    /**
     * ConsumerGroupBean??????
     */
    protected Map<String, ConsumerGroupBean<?>> groups = new HashMap<>();
    /**
     * ProviderBean ??????
     */
    protected Map<String, ProviderBean<?>> providers = new HashMap<>();
    /**
     * ????????????????????????????????????
     */
    protected Map<Member, AbstractConsumerConfig<?>> members = new HashMap<>();
    /**
     * Consumer???????????????
     */
    protected Map<String, AtomicInteger> consumerNameCounters = new HashMap<>();
    /**
     * Provider???????????????
     */
    protected Map<String, AtomicInteger> providerNameCounters = new HashMap<>();

    /**
     * ????????????
     */
    public RpcDefinitionPostProcessor(final ApplicationContext applicationContext,
                                      final ConfigurableEnvironment environment,
                                      final ResourceLoader resourceLoader) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.resourceLoader = resourceLoader;
        this.rpcProperties = Binder.get(environment).bind(RPC_PREFIX, RpcProperties.class).orElseGet(RpcProperties::new);
        //???????????????
        if (rpcProperties.getConsumers() != null) {
            rpcProperties.getConsumers().forEach(c -> addConfig(c, CONSUMER_PREFIX, consumerNameCounters, consumers));
        }
        //???????????????
        if (rpcProperties.getGroups() != null) {
            rpcProperties.getGroups().forEach(c -> addConfig(c, CONSUMER_PREFIX, consumerNameCounters, groups));
        }
        //?????????????????????
        if (rpcProperties.getProviders() != null) {
            rpcProperties.getProviders().forEach(c -> addConfig(c, PROVIDER_PREFIX, providerNameCounters, providers));
        }
    }

    @Override
    public void postProcessBeanDefinitionRegistry(final BeanDefinitionRegistry registry) throws BeansException {
        //?????????????????????????????????????????????????????????
        Set<String> packages = new LinkedHashSet<>();
        if (rpcProperties.getPackages() != null) {
            rpcProperties.getPackages().forEach(pkg -> {
                if (hasText(pkg)) {
                    packages.add(pkg.trim());
                }
            });
        }
        processPackages(packages, registry);
        //??????Bean
        register(registry);
    }

    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
        //?????????????????????Consumer?????????????????????????????????
        processConsumerAnnotation(bean.getClass(),
                (f, c) -> {
                    AbstractConsumerConfig<?> config = members.get(f);
                    if (config != null) {
                        ReflectionUtils.makeAccessible(f);
                        ReflectionUtils.setField(f, bean, config.proxy());
                    }
                },
                (m, c) -> {
                    AbstractConsumerConfig<?> config = members.get(m);
                    if (config != null) {
                        ReflectionUtils.invokeMethod(m, bean, config.proxy());
                    }
                });
        return bean;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * ???????????????
     *
     * @param config   ??????
     * @param prefix   ??????
     * @param counters ?????????
     * @param configs  ????????????
     */
    protected <T extends AbstractInterfaceConfig> void addConfig(final T config,
                                                                 final String prefix,
                                                                 final Map<String, AtomicInteger> counters,
                                                                 final Map<String, T> configs) {
        if (config == null) {
            return;
        }
        String name = computeName(config, prefix, counters);
        if (!isEmpty(name)) {
            if (configs.putIfAbsent(name, config) != null) {
                //????????????
                throw new BeanInitializationException("duplication bean name " + name);
            }
        }
    }

    /**
     * ????????????
     *
     * @param config   ??????
     * @param prefix   ??????
     * @param counters ?????????
     * @param <T>
     * @return
     */
    protected <T extends AbstractInterfaceConfig> String computeName(final T config,
                                                                     final String prefix,
                                                                     final Map<String, AtomicInteger> counters) {
        String name = config.getId();
        String interfaceClazz = config.getInterfaceClazz();
        if (isEmpty(name) && !isEmpty(interfaceClazz)) {
            name = prefix + Introspector.decapitalize(getShortName(interfaceClazz));
            if (counters != null) {
                AtomicInteger counter = counters.computeIfAbsent(name, n -> new AtomicInteger(0));
                int index = counter.incrementAndGet();
                name = index == 1 ? name : name + "-" + index;
            }
            config.setId(name);
        }
        return name;
    }

    /**
     * ???????????????????????????
     *
     * @param consumer       ???????????????
     * @param interfaceClazz ?????????
     */
    protected AbstractConsumerConfig<?> addAnnotationConsumer(final ConsumerBean<?> consumer, final Class<?> interfaceClazz) {
        consumer.setInterfaceClass(interfaceClazz);
        consumer.setInterfaceClazz(interfaceClazz.getName());
        //?????????????????????????????????
        String name = computeName(consumer, CONSUMER_PREFIX, null);
        //???????????????????????????
        ConsumerGroupBean<?> groupBean = groups.get(name);
        if (groupBean != null) {
            //??????????????????
            groupBean.setInterfaceClazz(consumer.getInterfaceClazz());
            groupBean.setInterfaceClass(interfaceClazz);
            if (isEmpty(groupBean.getAlias())) {
                groupBean.setAlias(consumer.getAlias());
            }
            return groupBean;
        } else {
            //???????????????
            ConsumerBean<?> old = consumers.putIfAbsent(name, consumer);
            if (old != null) {
                old.setInterfaceClazz(consumer.getInterfaceClazz());
                old.setInterfaceClass(interfaceClazz);
                if (isEmpty(old.getAlias())) {
                    old.setAlias(consumer.getAlias());
                }
            }
            return old != null ? old : consumer;
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param provider       ???????????????
     * @param interfaceClazz ?????????
     * @param refName        ????????????
     */
    protected ProviderBean<?> addProvider(final ProviderBean<?> provider, final Class<?> interfaceClazz, final String refName) {
        //???????????????
        provider.setInterfaceClass(interfaceClazz);
        provider.setInterfaceClazz(interfaceClazz.getName());
        provider.setRefName(refName);
        //?????????????????????????????????
        ProviderBean<?> old = providers.putIfAbsent(provider.getId(), provider);
        if (old != null) {
            if (isEmpty(old.getInterfaceClazz())) {
                old.setInterfaceClazz(provider.getInterfaceClazz());
            }
            if (isEmpty(old.getAlias())) {
                old.setAlias(provider.getAlias());
            }
            if (isEmpty(old.getRefName())) {
                old.setRefName(refName);
            }
        }
        return old != null ? old : provider;
    }


    /**
     * ??????
     */
    protected void register(final BeanDefinitionRegistry registry) {
        //??????
        String defRegName = register(registry, rpcProperties.getRegistry(), REGISTRY_NAME);
        String defServerName = register(registry, rpcProperties.getServer(), SERVER_NAME);
        register(registry, rpcProperties.getRegistries(), REGISTRY_NAME);
        register(registry, rpcProperties.getServers(), SERVER_NAME);
        consumers.forEach((name, c) -> register(c, registry, defRegName));
        groups.forEach((name, c) -> register(c, registry, defRegName));
        providers.forEach((name, p) -> register(p, registry, defRegName, defServerName));
        //??????????????????
        if (rpcProperties.getParameters() != null) {
            //??????????????????????????????????????????????????????
            rpcProperties.getParameters().forEach(GlobalContext::put);
        }
    }

    /**
     * ???????????????
     *
     * @param config     ???????????????
     * @param registry   BeanDefinitionRegistry
     * @param defRegName ??????????????????
     */
    protected void register(final ConsumerBean<?> config, final BeanDefinitionRegistry registry, final String defRegName) {
        BeanDefinitionBuilder builder = genericBeanDefinition(ConsumerBean.class, () -> config)
                .setRole(RootBeanDefinition.ROLE_INFRASTRUCTURE);
        //????????????????????????Proxy????????????ROLE_INFRASTRUCTURE?????????Spring?????????
        if (config.getRegistry() == null
                && isEmpty(config.getRegistryName())
                && !isEmpty(defRegName)) {
            //??????registry
            config.setRegistryName(defRegName);
        }
        //??????
        registry.registerBeanDefinition(config.getName(), builder.getBeanDefinition());
    }

    /**
     * ???????????????
     *
     * @param config     ???????????????
     * @param registry   BeanDefinitionRegistry
     * @param defRegName ??????????????????
     */
    protected void register(final ConsumerGroupBean<?> config, final BeanDefinitionRegistry registry, final String defRegName) {
        BeanDefinitionBuilder builder = genericBeanDefinition(ConsumerGroupConfig.class, () -> config)
                .setRole(RootBeanDefinition.ROLE_INFRASTRUCTURE);
        //????????????????????????Proxy????????????ROLE_INFRASTRUCTURE?????????Spring?????????
        if (config.getRegistry() == null
                && isEmpty(config.getRegistryName())
                && !isEmpty(defRegName)) {
            //??????registry
            config.setRegistryName(defRegName);
        }
        //??????
        registry.registerBeanDefinition(config.getName(), builder.getBeanDefinition());
    }

    /**
     * ?????????????????????
     *
     * @param config        ?????????????????????
     * @param registry      ????????????
     * @param defRegName    ????????????????????????
     * @param defServerName ????????????????????????
     */
    protected void register(final ProviderBean<?> config, final BeanDefinitionRegistry registry, final String defRegName,
                            final String defServerName) {
        //????????????????????????Proxy????????????ROLE_INFRASTRUCTURE?????????Spring?????????
        BeanDefinitionBuilder builder = genericBeanDefinition(ProviderBean.class, () -> config)
                .setRole(RootBeanDefinition.ROLE_INFRASTRUCTURE);
        //???????????????????????????????????????
        if (CollectionUtils.isEmpty(config.getRegistry())
                && CollectionUtils.isEmpty(config.getRegistryNames())
                && !isEmpty(defRegName)) {
            //??????????????????
            config.setRegistryNames(Arrays.asList(defRegName));
        }
        //??????Server
        if (config.getServerConfig() == null
                && isEmpty(config.getServerName())
                && !isEmpty(defServerName)) {
            config.setServerName(defServerName);
        }
        //??????
        registry.registerBeanDefinition(config.getName(), builder.getBeanDefinition());
    }

    /**
     * ??????rpc??????????????????class???
     *
     * @param packages ?????????
     * @param registry ????????????
     */
    protected void processPackages(Set<String> packages, BeanDefinitionRegistry registry) {
        //??????
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, false, environment, resourceLoader);
        registerAnnotationConfigProcessors(registry);
        scanner.addIncludeFilter(new AnnotationFilter());
        //???????????????rpc?????????????????????bean??????
        for (String basePackage : packages) {
            Set<BeanDefinition> definitions = scanner.findCandidateComponents(basePackage);
            if (!CollectionUtils.isEmpty(definitions)) {
                for (BeanDefinition definition : definitions) {
                    processConsumerAnnotation(definition);
                    processProviderAnnotation(definition, registry);
                }
            }
        }

    }

    /**
     * ?????????????????????
     *
     * @param definition bean??????
     */
    protected void processConsumerAnnotation(final BeanDefinition definition) {
        String className = definition.getBeanClassName();
        if (!isEmpty(className)) {
            Class<?> beanClass = resolveClassName(className, classLoader);
            processConsumerAnnotation(beanClass,
                    (f, c) -> members.put(f, addAnnotationConsumer(c, f.getType())),
                    (m, c) -> members.put(m, addAnnotationConsumer(c, m.getParameterTypes()[1])));
        }
    }

    /**
     * ?????????????????????
     *
     * @param beanClass      bean???
     * @param fieldConsumer  ???????????????
     * @param methodConsumer ???????????????
     */
    protected void processConsumerAnnotation(final Class<?> beanClass,
                                             final BiConsumer<Field, ConsumerBean<?>> fieldConsumer,
                                             final BiConsumer<Method, ConsumerBean<?>> methodConsumer) {

        Class<?> targetClass = beanClass;
        Pair<AnnotationProvider<Annotation, Annotation>, Annotation> pair;
        while (targetClass != null && targetClass != Object.class) {
            //????????????????????????
            for (Field field : targetClass.getDeclaredFields()) {
                if (!Modifier.isFinal(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                    pair = getConsumerAnnotation(field);
                    if (pair != null) {
                        fieldConsumer.accept(field, pair.getKey().toConsumerBean(pair.getValue(), environment));
                    }
                }
            }
            //????????????????????????
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers())
                        && method.getParameterCount() == 1
                        && method.getName().startsWith("set")) {
                    pair = getConsumerAnnotation(method);
                    if (pair != null) {
                        methodConsumer.accept(method, pair.getKey().toConsumerBean(pair.getValue(), environment));
                    }
                }
            }
            targetClass = targetClass.getSuperclass();
        }
    }

    /**
     * ???????????????????????????
     *
     * @param definition bean??????
     * @param registry   ????????????
     */
    protected void processProviderAnnotation(final BeanDefinition definition, BeanDefinitionRegistry registry) {
        String className = definition.getBeanClassName();
        if (isEmpty(className)) {
            return;
        }
        Class<?> providerClass = resolveClassName(className, classLoader);
        //???????????????????????????
        Class<?> targetClass = providerClass;
        Pair<AnnotationProvider<Annotation, Annotation>, Annotation> pair = null;
        while (targetClass != null && targetClass != Object.class) {
            pair = getProviderAnnotation(targetClass);
            if (pair != null) {
                break;
            }
            targetClass = targetClass.getSuperclass();
        }
        if (pair != null) {
            ProviderBean<?> provider = pair.getKey().toProviderBean(pair.getValue(), environment);
            //??????????????????
            Class<?> interfaceClazz = provider.getInterfaceClass(() -> getInterfaceClass(providerClass));
            if (interfaceClazz == null) {
                //??????????????????
                throw new BeanInitializationException("there is not any interface in class " + providerClass);
            }
            //????????????????????????Bean??????
            String refName = getComponentName(providerClass);
            if (refName == null) {
                refName = Introspector.decapitalize(getShortName(providerClass.getName()));
                //????????????????????????
                if (!registry.containsBeanDefinition(refName)) {
                    registry.registerBeanDefinition(refName, definition);
                }
            }
            if (isEmpty(provider.getId())) {
                provider.setId(PROVIDER_PREFIX + refName);
            }
            //??????provider
            addProvider(provider, interfaceClazz, refName);
        }
    }

    /**
     * ??????????????????
     *
     * @param providerClass ?????????????????????
     * @return ????????????
     */
    protected String getComponentName(final Class<?> providerClass) {
        String name = null;
        Component component = findAnnotation(providerClass, Component.class);
        if (component != null) {
            name = component.value();
        }
        if (isEmpty(name)) {
            Service service = findAnnotation(providerClass, Service.class);
            if (service != null) {
                name = service.value();
            }
        }
        return name;
    }

    /**
     * ????????????
     *
     * @param providerClass ??????????????????
     * @return ???????????????
     */
    protected Class<?> getInterfaceClass(final Class<?> providerClass) {
        Class<?> interfaceClazz = null;
        Class<?>[] interfaces = providerClass.getInterfaces();
        if (interfaces.length == 1) {
            interfaceClazz = interfaces[0];
        } else if (interfaces.length > 1) {
            //???????????????????????????????????????
            int max = -1;
            int priority;
            String providerClassName = providerClass.getSimpleName();
            String intfName;
            //??????????????????
            for (Class<?> intf : interfaces) {
                intfName = intf.getName();
                if (intfName.startsWith("java")) {
                    priority = 0;
                } else if (intfName.startsWith("javax")) {
                    priority = 0;
                } else {
                    priority = providerClassName.startsWith(intf.getSimpleName()) ? 2 : 1;
                }
                if (priority > max) {
                    interfaceClazz = intf;
                    max = priority;
                }
            }
        }
        return interfaceClazz;
    }


    /**
     * ??????
     *
     * @param registry      ?????????
     * @param configs       ????????????
     * @param defNamePrefix ????????????
     */
    protected <T extends AbstractIdConfig> void register(final BeanDefinitionRegistry registry,
                                                         final List<T> configs, final String defNamePrefix) {
        if (configs != null) {
            AtomicInteger counter = new AtomicInteger(0);
            for (T config : configs) {
                register(registry, config, defNamePrefix + "-" + counter.getAndIncrement());
            }
        }
    }

    /**
     * ??????
     *
     * @param registry BeanDefinitionRegistry
     * @param config   ??????
     * @param defName  ????????????
     * @param <T>
     */
    protected <T extends AbstractIdConfig> String register(final BeanDefinitionRegistry registry, final T config,
                                                           final String defName) {
        if (config == null) {
            return null;
        }
        String beanName = config.getId();
        if (isEmpty(beanName)) {
            beanName = defName;
        }
        if (!registry.containsBeanDefinition(beanName)) {
            RootBeanDefinition definition = new RootBeanDefinition((Class<T>) config.getClass(), () -> config);
            //??????Spring????????????
            definition.setRole(RootBeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition(beanName, definition);
        } else {
            throw new BeanInitializationException("duplication bean name " + beanName);
        }
        return beanName;
    }

    /**
     * ????????????
     *
     * @param function ??????
     * @return ????????????????????????????????????
     */
    protected Pair<AnnotationProvider<Annotation, Annotation>, Annotation> getAnnotation(final Function<AnnotationProvider<Annotation, Annotation>, Annotation> function) {
        Annotation result;
        for (AnnotationProvider<Annotation, Annotation> provider : ANNOTATION_PROVIDER.extensions()) {
            result = function.apply(provider);
            if (result != null) {
                return Pair.of(provider, result);
            }
        }
        return null;
    }

    /**
     * ????????????????????????????????????
     *
     * @param clazz ???
     * @return ????????????????????????????????????
     */
    protected Pair<AnnotationProvider<Annotation, Annotation>, Annotation> getProviderAnnotation(final Class<?> clazz) {
        return getAnnotation(p -> clazz.getDeclaredAnnotation(p.getProviderAnnotationClass()));
    }

    /**
     * ?????????????????????????????????
     *
     * @param method ??????
     * @return ????????????????????????????????????
     */
    protected Pair<AnnotationProvider<Annotation, Annotation>, Annotation> getConsumerAnnotation(final Method method) {
        return getAnnotation(p -> method.getAnnotation(p.getConsumerAnnotationClass()));
    }

    /**
     * ?????????????????????????????????
     *
     * @param field ??????
     * @return ????????????????????????????????????
     */
    protected Pair<AnnotationProvider<Annotation, Annotation>, Annotation> getConsumerAnnotation(final Field field) {
        return getAnnotation(p -> field.getAnnotation(p.getConsumerAnnotationClass()));
    }

    /**
     * ??????????????????????????????????????????????????????????????????
     */
    protected class AnnotationFilter implements TypeFilter {

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
            ClassMetadata classMetadata = metadataReader.getClassMetadata();
            if (classMetadata.isConcrete() && !classMetadata.isAnnotation()) {
                //?????????
                Class<?> clazz = resolveClassName(classMetadata.getClassName(), classLoader);
                //????????????Public
                if (Modifier.isPublic(clazz.getModifiers())) {
                    Class<?> targetClass = clazz;
                    while (targetClass != null && targetClass != Object.class) {
                        //????????????????????????????????????
                        if (getProviderAnnotation(targetClass) != null) {
                            return true;
                        }
                        //??????????????????????????????
                        for (Field field : targetClass.getDeclaredFields()) {
                            if (!Modifier.isFinal(field.getModifiers())
                                    && !Modifier.isStatic(field.getModifiers())
                                    && getConsumerAnnotation(field) != null) {
                                return true;
                            }
                        }
                        //?????????????????????????????????
                        for (Method method : clazz.getDeclaredMethods()) {
                            if (!Modifier.isStatic(method.getModifiers())
                                    && Modifier.isPublic(method.getModifiers())
                                    && method.getParameterCount() == 1
                                    && method.getName().startsWith("set")
                                    && getConsumerAnnotation(method) != null) {
                                return true;
                            }
                        }
                        targetClass = targetClass.getSuperclass();
                    }

                }
            }
            return false;
        }
    }


}
