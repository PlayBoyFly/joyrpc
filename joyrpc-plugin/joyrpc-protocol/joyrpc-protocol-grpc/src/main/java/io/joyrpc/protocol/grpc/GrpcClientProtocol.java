package io.joyrpc.protocol.grpc;

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

import io.joyrpc.exception.RpcException;
import io.joyrpc.extension.Extension;
import io.joyrpc.extension.URL;
import io.joyrpc.extension.condition.ConditionalOnClass;
import io.joyrpc.protocol.AbstractProtocol;
import io.joyrpc.protocol.ClientProtocol;
import io.joyrpc.protocol.MsgType;
import io.joyrpc.protocol.grpc.handler.GrpcClienttHandler;
import io.joyrpc.protocol.handler.RequestChannelHandler;
import io.joyrpc.protocol.handler.ResponseChannelHandler;
import io.joyrpc.protocol.message.MessageHeader;
import io.joyrpc.protocol.message.ResponseMessage;
import io.joyrpc.protocol.message.negotiation.NegotiationResponse;
import io.joyrpc.transport.Client;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.channel.ChannelHandlerChain;
import io.joyrpc.transport.codec.Codec;
import io.joyrpc.transport.codec.Http2Codec;
import io.joyrpc.transport.message.Message;
import io.joyrpc.transport.session.DefaultSession;
import io.joyrpc.transport.session.Session;

import java.util.Arrays;
import java.util.List;

import static io.joyrpc.Plugin.COMPRESSION;
import static io.joyrpc.Plugin.MESSAGE_HANDLER_SELECTOR;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.protocol.message.negotiation.NegotiationResponse.NOT_SUPPORT;
import static io.joyrpc.protocol.message.negotiation.NegotiationResponse.SUCCESS;

/**
 * @date: 2019/5/6
 */
@Extension("grpc")
@ConditionalOnClass("io.grpc.Codec")
public class GrpcClientProtocol extends AbstractProtocol implements ClientProtocol {

    protected static final String PROTOBUF = "protobuf";

    protected static final List<String> SERIALIZATIONS = Arrays.asList("protobuf");

    protected static final List<String> COMPRESSIONS = Arrays.asList("gzip", "deflate");

    @Override
    protected Codec createCodec() {
        return Http2Codec.INSTANCE;
    }

    @Override
    public ChannelHandlerChain buildChain() {
        //GrpcClientConvertHandler ??????????????????????????????streamId?????????????????????channel??????????????????chain???????????????build
        return new ChannelHandlerChain()
                .addLast(new GrpcClienttHandler())
                .addLast(new RequestChannelHandler<>(MESSAGE_HANDLER_SELECTOR, this::onException))
                .addLast(new ResponseChannelHandler());
    }

    @Override
    public byte[] getMagicCode() {
        return new byte[0];
    }

    @Override
    public Message negotiate(URL clusterUrl, final Client client) {
        NegotiationResponse response = new NegotiationResponse();
        //??????????????????????????????
        response.setSerializations(SERIALIZATIONS);
        //???????????????????????????
        response.setSerialization(PROTOBUF);
        response.setStatus(response.getSerialization() == null ? NOT_SUPPORT : SUCCESS);
        // ???????????????????????????????????????????????????
        response.setCompressions(COMPRESSION.available(COMPRESSIONS));
        String compression = clusterUrl.getString(COMPRESS_OPTION);
        if (compression != null && !compression.isEmpty()) {
            //???????????????????????????
            if (response.getCompressions().contains(compression)) {
                response.setCompression(compression);
            } else {
                response.setCompression(response.getCompressions().get(0));
            }
        }

        //????????????????????????
        response.addAttribute(CONFIG_KEY_INTERFACE, clusterUrl.getPath());
        response.addAttribute(ALIAS_OPTION.getName(), clusterUrl.getString(ALIAS_OPTION));
        // ????????????????????????
        return new ResponseMessage<>(new MessageHeader(MsgType.NegotiationResp.getType()), response);
    }

    @Override
    public Session session(final URL clusterUrl, Client client) {
        return new DefaultSession();
    }

    @Override
    public Message sessionbeat(URL clusterUrl, final Client client) {
        return null;
    }

    @Override
    public Message heartbeat(URL clusterUrl, final Client client) {
        return null;
    }

    @Override
    protected void onException(Channel channel, MessageHeader header, RpcException cause) {
        logger.error(String.format("Error %s occurs at %s ", cause.getClass().getName(), Channel.toString(channel)), cause);
    }
}
