package io.joyrpc.protocol.grpc.handler;

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

import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.joyrpc.codec.compression.Compression;
import io.joyrpc.codec.serialization.Serialization;
import io.joyrpc.codec.serialization.UnsafeByteArrayInputStream;
import io.joyrpc.codec.serialization.UnsafeByteArrayOutputStream;
import io.joyrpc.exception.MethodOverloadException;
import io.joyrpc.exception.RpcException;
import io.joyrpc.protocol.AbstractHttpHandler;
import io.joyrpc.protocol.MsgType;
import io.joyrpc.protocol.grpc.HeaderMapping;
import io.joyrpc.protocol.grpc.exception.GrpcBizException;
import io.joyrpc.protocol.message.*;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.channel.ChannelContext;
import io.joyrpc.transport.channel.EnhanceCompletableFuture;
import io.joyrpc.transport.http.HttpMethod;
import io.joyrpc.transport.http2.*;
import io.joyrpc.transport.message.Message;
import io.joyrpc.transport.session.Session;
import io.joyrpc.util.GrpcType;
import io.joyrpc.util.GrpcType.ClassWrapper;
import io.joyrpc.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.joyrpc.Plugin.*;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.protocol.grpc.GrpcServerProtocol.GRPC_NUMBER;
import static io.joyrpc.transport.http.HttpHeaders.Values.GZIP;
import static io.joyrpc.util.ClassUtils.*;
import static io.joyrpc.util.GrpcType.F_RESULT;

/**
 * grpc client???????????????handler
 */
public class GrpcClienttHandler extends AbstractHttpHandler {

    private final static Logger logger = LoggerFactory.getLogger(GrpcClienttHandler.class);

    protected static final String USER_AGENT = "joyrpc";

    protected Serialization serialization = SERIALIZATION_SELECTOR.select((byte) Serialization.PROTOBUF_ID);

    protected Map<Integer, Http2ResponseMessage> http2ResponseNoEnds = new ConcurrentHashMap<>();

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public Object received(final ChannelContext ctx, final Object message) {
        if (message instanceof Http2ResponseMessage) {
            Http2ResponseMessage http2RespMsg = (Http2ResponseMessage) message;
            try {
                return input(ctx.getChannel(), http2RespMsg);
            } catch (Throwable e) {
                logger.error(String.format("Error occurs while parsing grpc request from %s", Channel.toString(ctx.getChannel().getRemoteAddress())), e);
                MessageHeader header = new MessageHeader((byte) Serialization.PROTOBUF_ID, MsgType.BizReq.getType(), GRPC_NUMBER);
                header.setMsgId(((Http2Message) message).getBizMsgId());
                header.addAttribute(HeaderMapping.STREAM_ID.getNum(), ((Http2Message) message).getStreamId());
                throw new RpcException(header, e);
            }
        } else {
            return message;
        }
    }

    @Override
    public Object wrote(final ChannelContext ctx, final Object message) {
        if (message instanceof RequestMessage) {
            try {
                return output(ctx.getChannel(), (RequestMessage<?>) message);
            } catch (Exception e) {
                logger.error(String.format("Error occurs while write grpc request from %s", Channel.toString(ctx.getChannel().getRemoteAddress())), e);
                throw new RpcException(((RequestMessage) message).getHeader(), e);
            }
        } else {
            return message;
        }
    }

    /**
     * ??????grpc??????
     *
     * @param channel ??????
     * @param message ??????
     * @return ????????????
     */
    protected Object input(final Channel channel, final Http2ResponseMessage message) throws IOException {
        if (message.getStreamId() <= 0) {
            return null;
        }
        Http2ResponseMessage http2Msg = adjoin(message);
        if (http2Msg == null) {
            return null;
        }
        MessageHeader header = new MessageHeader(serialization.getTypeId(), MsgType.BizResp.getType(), GRPC_NUMBER);
        header.setMsgId(http2Msg.getBizMsgId());
        header.addAttribute(HeaderMapping.STREAM_ID.getNum(), http2Msg.getStreamId());
        ResponsePayload payload;
        Object grpcStatusVal = http2Msg.endHeaders().get(GRPC_STATUS_KEY);
        int grpcStatus = grpcStatusVal == null ? Status.Code.UNKNOWN.value() : Integer.parseInt(grpcStatusVal.toString());
        if (grpcStatus == Status.Code.OK.value()) {
            EnhanceCompletableFuture<Integer, Message> future = channel.getFutureManager().get(http2Msg.getBizMsgId());
            if (future != null) {
                payload = decodePayload(http2Msg, (ReturnType) future.getAttr());
            } else {
                payload = new ResponsePayload(new GrpcBizException(String.format("request is timeout. id=%d", http2Msg.getBizMsgId())));
            }
        } else {
            Status status = Status.fromCodeValue(grpcStatus);
            String errMsg = String.format("%s [%d]: %s", status.getCode().name(), grpcStatus, http2Msg.headers().get(GRPC_MESSAGE_KEY));
            payload = new ResponsePayload(new GrpcBizException(errMsg));
        }
        return new ResponseMessage<>(header, payload);
    }

    /**
     * ????????????
     *
     * @param message    ??????
     * @param returnType ????????????
     * @return ??????
     * @throws IOException
     */
    protected ResponsePayload decodePayload(final Http2ResponseMessage message, final ReturnType returnType) throws IOException {
        Http2Headers headers = message.headers();
        InputStream in = new UnsafeByteArrayInputStream(message.content());
        //??????????????????
        int isCompression = in.read();
        //????????????4???
        if (in.skip(4) < 4) {
            throw new IOException(String.format("request data is not full. id=%d", message.getBizMsgId()));
        }
        //????????????
        if (isCompression > 0) {
            Pair<String, Compression> pair = getEncoding((String) headers.get(GrpcUtil.MESSAGE_ACCEPT_ENCODING));
            if (pair != null) {
                in = pair.getValue().decompress(in);
            }
        }
        //????????????
        Object response = serialization.getSerializer().deserialize(in, returnType.getReturnType());
        if (returnType.isWrapper()) {
            response = getValue(returnType.getReturnType(), F_RESULT, response);
        }
        return new ResponsePayload(response);
    }

    /**
     * ???????????????????????????
     *
     * @param message ??????
     * @return ????????????
     */
    protected Http2ResponseMessage adjoin(final Http2ResponseMessage message) {
        Http2ResponseMessage http2Msg = null;
        if (message.endHeaders() == null) {
            http2ResponseNoEnds.put(message.getStreamId(), message);
            return null;
        } else {
            http2Msg = http2ResponseNoEnds.remove(message.getStreamId());
            if (http2Msg != null) {
                http2Msg.setEndHeaders(message.endHeaders());
            } else {
                http2Msg = message;
            }
        }
        return http2Msg;
    }

    /**
     * ??????
     *
     * @param channel
     * @param message
     * @return
     */
    protected Object output(final Channel channel, final RequestMessage<?> message) throws IOException, NoSuchMethodException, MethodOverloadException {
        if (!(message.getPayLoad() instanceof Invocation)) {
            return message;
        }
        Invocation invocation = (Invocation) message.getPayLoad();
        Session session = message.getSession();
        Http2Headers headers = buildHeaders(invocation, session, channel);
        //???grpc??????????????????????????????????????????GrpcType
        GrpcType grpcType = getGrpcType(invocation.getClazz(), invocation.getMethodName(), (c, m) -> GRPC_FACTORY.get().generate(c, m));
        //??????payload
        Object payLoad = wrapPayload(invocation, grpcType);
        //???????????????????????? future ???
        EnhanceCompletableFuture<Integer, Message> future = channel.getFutureManager().get(message.getMsgId());
        storeReturnType(invocation, grpcType, future);

        byte compressType = message.getHeader().getCompression();
        //??????content
        UnsafeByteArrayOutputStream baos = new UnsafeByteArrayOutputStream();
        //????????????
        baos.write(0);
        //??????(??????)
        baos.write(new byte[]{0, 0, 0, 0}, 0, 4);
        //?????????
        if (payLoad != null) {
            serialization.getSerializer().serialize(baos, payLoad);
        }
        //?????? content ????????????
        byte[] content = baos.toByteArray();
        //????????????
        if (content.length > 1024 && compressType > 0) {
            Compression compression = COMPRESSION_SELECTOR.select(compressType);
            if (compression != null) {
                //?????????????????????
                baos.reset();
                baos.write(new byte[]{1, 0, 0, 0, 0});
                content = compress(compression, baos, content, 5, content.length - 5);
                headers.set(GrpcUtil.MESSAGE_ENCODING, compression.getTypeName());
            }
        }
        //????????????
        int length = content.length - 5;
        content[1] = (byte) (length >>> 24);
        content[2] = (byte) (length >>> 16);
        content[3] = (byte) (length >>> 8);
        content[4] = (byte) length;
        return new DefaultHttp2RequestMessage(0, message.getMsgId(), headers, content);
    }

    /**
     * ????????????
     *
     * @param invocation ????????????
     * @param session    ??????
     * @param channel    channel
     * @return ??????
     */
    protected Http2Headers buildHeaders(final Invocation invocation, final Session session, final Channel channel) {
        //??????headers
        final InetSocketAddress remoteAddress = channel.getRemoteAddress();
        String authority = remoteAddress.getHostName() + ":" + remoteAddress.getPort();
        String path = "/" + invocation.getClassName() + "/" + invocation.getMethodName();
        //??????http2header??????
        Http2Headers headers = new DefaultHttp2Headers().authority(authority).path(path).method(HttpMethod.POST).scheme("http");
        //????????????
        Map<String, Object> attachments = invocation.getAttachments();
        if (attachments != null) {
            attachments.forEach((key, value) -> {
                if (value != null) {
                    Class<?> clazz = value.getClass();
                    if (CharSequence.class.isAssignableFrom(clazz)
                            || clazz.isPrimitive() || clazz.isEnum()
                            || Boolean.class == clazz
                            || Number.class.isAssignableFrom(clazz)) {
                        headers.set(key, value.toString());
                    } else {
                        //??????????????????JSON
                        headers.set(key, JSON.get().toJSONString(value));
                    }
                }
            });
        }
        headers.set(GrpcUtil.CONTENT_TYPE_KEY.name(), GrpcUtil.CONTENT_TYPE_GRPC);
        headers.set(GrpcUtil.TE_HEADER.name(), GrpcUtil.TE_TRAILERS);
        headers.set(GrpcUtil.USER_AGENT_KEY.name(), USER_AGENT);
        headers.set(ALIAS_OPTION.getName(), invocation.getAlias());
        String acceptEncoding = GZIP;
        if (session != null && session.getCompressions() != null) {
            acceptEncoding = String.join(",", session.getCompressions());
        }
        headers.set(GrpcUtil.MESSAGE_ACCEPT_ENCODING, acceptEncoding);

        return headers;
    }

    /**
     * ??????????????????
     *
     * @param invocation ????????????
     * @param grpcType   grpc??????
     * @param future     future
     */
    protected void storeReturnType(final Invocation invocation, final GrpcType grpcType,
                                   final EnhanceCompletableFuture<Integer, Message> future) {
        ClassWrapper respWrapper = grpcType.getResponse();
        if (respWrapper != null) {
            future.setAttr(new ReturnType(respWrapper.getClazz(), respWrapper.isWrapper()));
        } else {
            //????????????????????????????????????
            future.setAttr(new ReturnType(invocation.getMethod().getReturnType(), false));
        }
    }

    /**
     * ??????payload
     *
     * @param invocation ????????????
     * @param grpcType   ??????
     * @return ???????????????
     */
    protected Object wrapPayload(final Invocation invocation, final GrpcType grpcType) {
        Object payLoad;
        Object[] args = invocation.getArgs();
        ClassWrapper wrapper = grpcType.getRequest();
        //??????reqWrapper?????????payLoad
        if (wrapper == null) {
            payLoad = (args == null || args.length == 0) ? null : args[0];
        } else if (wrapper.isWrapper()) {
            payLoad = newInstance(wrapper.getClazz());
            List<Field> wrapperFields = getFields(payLoad.getClass());
            int i = 0;
            for (Field field : wrapperFields) {
                setValue(wrapper.getClazz(), field, payLoad, args[i++]);
            }
        } else {
            payLoad = args[0];
        }
        return payLoad;
    }

    /**
     * ??????????????????????????????????????????Future???
     */
    protected static class ReturnType {
        /**
         * ???????????????
         */
        protected Class<?> returnType;
        /**
         * ??????????????????
         */
        protected boolean wrapper;

        public ReturnType(Class<?> returnType, boolean wrapper) {
            this.returnType = returnType;
            this.wrapper = wrapper;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public void setReturnType(Class<?> returnType) {
            this.returnType = returnType;
        }

        public boolean isWrapper() {
            return wrapper;
        }

        public void setWrapper(boolean wrapper) {
            this.wrapper = wrapper;
        }
    }

}
