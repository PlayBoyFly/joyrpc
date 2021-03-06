package io.joyrpc;

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

import io.joyrpc.cache.CacheFactory;
import io.joyrpc.cache.CacheKeyGenerator;
import io.joyrpc.cluster.MetricHandler;
import io.joyrpc.cluster.candidate.Candidature;
import io.joyrpc.cluster.discovery.config.Configure;
import io.joyrpc.cluster.discovery.naming.Registar;
import io.joyrpc.cluster.discovery.registry.RegistryFactory;
import io.joyrpc.cluster.distribution.*;
import io.joyrpc.cluster.distribution.loadbalance.adaptive.Arbiter;
import io.joyrpc.cluster.distribution.loadbalance.adaptive.Election;
import io.joyrpc.cluster.distribution.loadbalance.adaptive.Judge;
import io.joyrpc.codec.CodecType;
import io.joyrpc.codec.checksum.Checksum;
import io.joyrpc.codec.compression.Compression;
import io.joyrpc.codec.crypto.Decryptor;
import io.joyrpc.codec.crypto.Encryptor;
import io.joyrpc.codec.crypto.Signature;
import io.joyrpc.codec.digester.Digester;
import io.joyrpc.codec.serialization.*;
import io.joyrpc.config.InterfaceOptionFactory;
import io.joyrpc.config.validator.InterfaceValidator;
import io.joyrpc.context.ConfigEventHandler;
import io.joyrpc.context.Configurator;
import io.joyrpc.context.ContextSupplier;
import io.joyrpc.context.Environment;
import io.joyrpc.context.injection.NodeReqInjection;
import io.joyrpc.context.injection.RespInjection;
import io.joyrpc.context.injection.Transmit;
import io.joyrpc.event.EventBus;
import io.joyrpc.expression.ExpressionProvider;
import io.joyrpc.extension.*;
import io.joyrpc.filter.ConsumerFilter;
import io.joyrpc.filter.ProviderFilter;
import io.joyrpc.health.Doctor;
import io.joyrpc.invoker.ExceptionHandler;
import io.joyrpc.invoker.FilterChainFactory;
import io.joyrpc.invoker.GroupInvoker;
import io.joyrpc.metric.DashboardFactory;
import io.joyrpc.permission.Authentication;
import io.joyrpc.permission.Authorization;
import io.joyrpc.permission.Identification;
import io.joyrpc.protocol.ClientProtocol;
import io.joyrpc.protocol.MessageHandler;
import io.joyrpc.protocol.Protocol.ProtocolVersion;
import io.joyrpc.protocol.ServerProtocol;
import io.joyrpc.proxy.GrpcFactory;
import io.joyrpc.proxy.ProxyFactory;
import io.joyrpc.thread.ThreadPool;
import io.joyrpc.transport.EndpointFactory;
import io.joyrpc.transport.channel.ChannelManagerFactory;
import io.joyrpc.transport.http.HttpClient;
import io.joyrpc.transport.telnet.TelnetHandler;
import io.joyrpc.transport.transport.TransportFactory;

/**
 * @date: 23/1/2019
 */
public interface Plugin {

    /**
     * ??????????????????
     */
    ExtensionPoint<InterfaceValidator, String> INTERFACE_VALIDATOR = new ExtensionPointLazy<>(InterfaceValidator.class);
    /**
     * ?????????????????????
     */
    ExtensionPoint<MessageHandler, Integer> MESSAGE_HANDLER = new ExtensionPointLazy<>(MessageHandler.class);

    /**
     * ???????????????
     */
    ExtensionSelector<MessageHandler, Integer, Integer, MessageHandler> MESSAGE_HANDLER_SELECTOR = new MessageHandlerSelector(MESSAGE_HANDLER);

    /**
     * ??????????????????
     */
    ExtensionPoint<FilterChainFactory, String> FILTER_CHAIN_FACTORY = new ExtensionPointLazy<>(FilterChainFactory.class);

    /**
     * ????????????????????????
     */
    ExtensionPoint<ConsumerFilter, String> CONSUMER_FILTER = new ExtensionPointLazy<>(ConsumerFilter.class);
    /**
     * ??????????????????????????????
     */
    ExtensionPoint<ProviderFilter, String> PROVIDER_FILTER = new ExtensionPointLazy<>(ProviderFilter.class);
    /**
     * ?????????????????????
     */
    ExtensionPoint<CacheKeyGenerator, String> CACHE_KEY_GENERATOR = new ExtensionPointLazy<>(CacheKeyGenerator.class);

    /**
     * ??????????????????.
     */
    ExtensionPoint<ThreadPool, String> THREAD_POOL = new ExtensionPointLazy<>(ThreadPool.class);

    /**
     * ?????????????????????
     */
    ExtensionPoint<Transmit, String> TRANSMIT = new ExtensionPointLazy<>(Transmit.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<NodeReqInjection, String> NODE_REQUEST_INJECTION = new ExtensionPointLazy<>(NodeReqInjection.class);

    /**
     * ????????????
     */
    ExtensionPoint<RespInjection, String> RESPONSE_INJECTION = new ExtensionPointLazy<>(RespInjection.class);

    /**
     * ??????
     */
    ExtensionPoint<Configurator, String> CONFIGURATOR = new ExtensionPointLazy<>(Configurator.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<GroupInvoker, String> GROUP_ROUTE = new ExtensionPointLazy<>(GroupInvoker.class);

    /**
     * ????????????
     */
    ExtensionPoint<NodeSelector, String> NODE_SELECTOR = new ExtensionPointLazy<>(NodeSelector.class);

    /**
     * ????????????????????????????????????????????????
     */
    ExtensionPoint<ConfigEventHandler, String> CONFIG_EVENT_HANDLER = new ExtensionPointLazy<>(ConfigEventHandler.class);

    /**
     * ???????????????
     */
    ExtensionPoint<DashboardFactory, String> DASHBOARD_FACTORY = new ExtensionPointLazy<>(DashboardFactory.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<ExceptionHandler, String> EXCEPTION_HANDLER = new ExtensionPointLazy<>(ExceptionHandler.class);

    /**
     * ???????????????????????????
     */
    ExtensionPoint<GenericSerializer, String> GENERIC_SERIALIZER = new ExtensionPointLazy<>(GenericSerializer.class);

    /**
     * ???????????????
     */
    ExtensionPoint<ExpressionProvider, String> EXPRESSION_PROVIDER = new ExtensionPointLazy<>(ExpressionProvider.class);

    /**
     * ?????????????????????
     */
    ExtensionPoint<InterfaceOptionFactory, String> INTERFACE_OPTION_FACTORY = new ExtensionPointLazy<>(InterfaceOptionFactory.class);

    /**
     * ????????????????????????
     */
    class MessageHandlerSelector extends ExtensionSelector<MessageHandler, Integer, Integer, MessageHandler> {
        /**
         * ID??????
         */
        protected volatile MessageHandler[] handlers;

        /**
         * ????????????
         *
         * @param extensionPoint
         */
        public MessageHandlerSelector(ExtensionPoint<MessageHandler, Integer> extensionPoint) {
            super(extensionPoint, null);
        }

        @Override
        public MessageHandler select(final Integer condition) {
            if (handlers == null) {
                synchronized (this) {
                    if (handlers == null) {
                        final MessageHandler[] handlers = new MessageHandler[127];
                        extensionPoint.metas().forEach(o -> {
                            MessageHandler handler = o.getTarget();
                            handlers[handler.type()] = handler;
                        });
                        this.handlers = handlers;
                    }
                }
                ;
            }
            return handlers[condition];
        }
    }

    /**
     * ????????????
     */
    ExtensionPoint<Environment, String> ENVIRONMENT = new ExtensionPointLazy<>(Environment.class);

    /**
     * ???????????????????????????
     */
    ExtensionPoint<ContextSupplier, String> CONTEXT_SUPPLIER = new ExtensionPointLazy<>(ContextSupplier.class);


    /**
     * ????????????
     */
    ExtensionPoint<EventBus, String> EVENT_BUS = new ExtensionPointLazy<>(EventBus.class);

    /**
     * ????????????
     */
    ExtensionPoint<CacheFactory, String> CACHE = new ExtensionPointLazy<>(CacheFactory.class);

    /**
     * ????????????????????????
     */
    ExtensionPoint<Serialization, String> SERIALIZATION = new ExtensionPointLazy<>(Serialization.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<Authentication, String> AUTHENTICATOR = new ExtensionPointLazy<>(Authentication.class);

    /**
     * ????????????
     */
    ExtensionPoint<Identification, String> IDENTIFICATION = new ExtensionPointLazy<>(Identification.class);
    /**
     * ????????????
     */
    ExtensionPoint<Authorization, String> AUTHORIZATION = new ExtensionPointLazy<>(Authorization.class);

    /**
     * JSON?????????
     */
    ExtensionPoint<Json, String> JSON = new ExtensionPointLazy<>(Json.class);

    /**
     * JSON?????????
     */
    ExtensionPoint<Xml, String> XML = new ExtensionPointLazy<>(Xml.class);

    /**
     * ????????????
     */
    ExtensionPoint<Encryptor, String> ENCRYPTOR = new ExtensionPointLazy<>(Encryptor.class);

    /**
     * ????????????
     */
    ExtensionPoint<Decryptor, String> DECRYPTOR = new ExtensionPointLazy<>(Decryptor.class);

    /**
     * ????????????
     */
    ExtensionPoint<Signature, String> SIGNATURE = new ExtensionPointLazy<>(Signature.class);

    /**
     * ????????????
     */
    ExtensionPoint<Digester, String> DIGESTER = new ExtensionPointLazy<>(Digester.class);

    /**
     * ????????????
     */
    ExtensionPoint<Compression, String> COMPRESSION = new ExtensionPointLazy<>(Compression.class);

    /**
     * ???????????????
     */
    ExtensionPoint<Checksum, String> CHECKSUM = new ExtensionPointLazy<>(Checksum.class);

    /**
     * Proxy??????
     */
    ExtensionPoint<ProxyFactory, String> PROXY = new ExtensionPointLazy<>(ProxyFactory.class);

    /**
     * GRPC????????????
     */
    ExtensionPoint<GrpcFactory, String> GRPC_FACTORY = new ExtensionPointLazy<>(GrpcFactory.class);

    /**
     * ????????????
     */
    ExtensionPoint<Doctor, String> DOCTOR = new ExtensionPointLazy<>(Doctor.class);

    /**
     * ?????????????????????
     */
    ExtensionPoint<MetricHandler, String> METRIC_HANDLER = new ExtensionPointLazy<>(MetricHandler.class);

    /**
     * ???????????????
     */
    ExtensionPoint<ClientProtocol, String> CLIENT_PROTOCOL = new ExtensionPointLazy<>(ClientProtocol.class);

    /**
     * ????????????????????????????????????????????????
     */
    ExtensionSelector<ClientProtocol, String, ProtocolVersion, ClientProtocol> CLIENT_PROTOCOL_SELECTOR = new ExtensionSelector<>(CLIENT_PROTOCOL,
            new Selector.CacheSelector<>((extensions, protocolVersion) -> {
                String name = protocolVersion.getName();
                String version = protocolVersion.getVersion();
                //???????????????????????????????????????????????????
                if (version == null || version.isEmpty()) {
                    return extensions.get(name);
                }
                //??????????????????
                ClientProtocol protocol = extensions.get(version);
                if (protocol == null && name != null && !name.isEmpty()) {
                    String n;
                    //???????????????????????????????????????????????????????????????
                    for (ExtensionMeta<ClientProtocol, String> meta : extensions.metas()) {
                        //????????????
                        n = meta.getExtension().getName();
                        //???????????????????????????joyrpc2???joyrpc??????
                        if (n.startsWith(name)) {
                            try {
                                //?????????????????????
                                Integer.valueOf(n.substring(name.length()));
                                protocol = meta.getTarget();
                                break;
                            } catch (NumberFormatException e) {
                                if (n.equals(name) && protocol == null) {
                                    //???????????????????????????????????????????????????name???????????????????????????????????????
                                    protocol = meta.getTarget();
                                }
                            }
                        }
                    }

                }
                return protocol;
            }));

    /**
     * ???????????????
     */
    ExtensionPoint<ServerProtocol, String> SERVER_PROTOCOL = new ExtensionPointLazy<>(ServerProtocol.class);

    /**
     * ??????????????????????????????
     */
    ExtensionPoint<CustomCodec, Class> CUSTOM_CODEC = new ExtensionPointLazy<>(CustomCodec.class);

    /**
     * ChannelManager??????
     */
    ExtensionPoint<ChannelManagerFactory, String> CHANNEL_MANAGER_FACTORY = new ExtensionPointLazy<>(ChannelManagerFactory.class);

    /**
     * TransportFactory??????
     */
    ExtensionPoint<TransportFactory, String> TRANSPORT_FACTORY = new ExtensionPointLazy<>(TransportFactory.class);

    ExtensionPoint<EndpointFactory, String> ENDPOINT_FACTORY = new ExtensionPointLazy<>(EndpointFactory.class);

    /**
     * ?????????????????????
     */
    ExtensionPoint<Candidature, String> CANDIDATURE = new ExtensionPointLazy<>(Candidature.class);
    /**
     * ??????????????????
     */
    ExtensionPoint<LoadBalance, String> LOADBALANCE = new ExtensionPointLazy<>(LoadBalance.class);

    /**
     * ?????????LB???????????????
     */
    ExtensionPoint<Judge, String> JUDGE = new ExtensionPointLazy<Judge, String>(Judge.class);

    /**
     * ????????????
     */
    ExtensionPoint<Arbiter, String> ARBITER = new ExtensionPointLazy<Arbiter, String>(Arbiter.class);

    /**
     * ??????????????????????????????
     */
    ExtensionPoint<Election, String> ELECTION = new ExtensionPointLazy(Election.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<RateLimiter, String> LIMITER = new ExtensionPointLazy<>(RateLimiter.class);

    /**
     * ????????????
     */
    ExtensionPoint<Router, String> ROUTER = new ExtensionPointLazy<>(Router.class);

    /**
     * ???????????????????????????
     */
    ExtensionPoint<FailoverSelector, String> FAILOVER_SELECTOR = new ExtensionPointLazy<>(FailoverSelector.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<ExceptionPredication, String> EXCEPTION_PREDICATION = new ExtensionPointLazy<>(ExceptionPredication.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<Registar, String> REGISTAR = new ExtensionPointLazy<>(Registar.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<Configure, String> CONFIGURE = new ExtensionPointLazy<>(Configure.class);

    /**
     * Telnet???????????????
     */
    ExtensionPoint<TelnetHandler, String> TELNET_HANDLER = new ExtensionPointLazy<>(TelnetHandler.class);

    /**
     * HTTP?????????
     */
    ExtensionPoint<HttpClient, String> HTTP_CLIENT = new ExtensionPointLazy<>(HttpClient.class);

    /**
     * ??????????????????
     */
    ExtensionPoint<RegistryFactory, String> REGISTRY = new ExtensionPointLazy<>(RegistryFactory.class);

    /**
     * ??????????????????
     */
    ExtensionSelector<Serialization, String, Byte, Serialization> SERIALIZATION_SELECTOR = new Plugin.CodecSelector<>(SERIALIZATION);
    /**
     * ???????????????
     */
    ExtensionSelector<Compression, String, Byte, Compression> COMPRESSION_SELECTOR = new Plugin.CodecSelector<>(COMPRESSION);

    /**
     * ??????????????????
     */
    ExtensionSelector<Checksum, String, Byte, Checksum> CHECKSUM_SELECTOR = new Plugin.CodecSelector<>(CHECKSUM);

    /**
     * ????????????????????????
     *
     * @param <T>
     */
    class CodecSelector<T extends CodecType> extends ExtensionSelector<T, String, Byte, T> {
        /**
         * ID??????
         */
        protected volatile CodecType[] codecs;

        /**
         * ????????????
         *
         * @param extensionPoint
         */
        public CodecSelector(ExtensionPoint<T, String> extensionPoint) {
            super(extensionPoint, null);
        }

        @Override
        public T select(final Byte condition) {
            if (codecs == null) {
                synchronized (this) {
                    if (codecs == null) {
                        CodecType[] codecs = new CodecType[127];
                        extensionPoint.metas().forEach(o -> {
                            T target = o.getTarget();
                            codecs[target.getTypeId()] = target;
                        });
                        this.codecs = codecs;
                    }
                }
            }
            return (T) codecs[condition];
        }
    }
}
