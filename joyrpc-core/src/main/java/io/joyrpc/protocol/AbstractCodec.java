package io.joyrpc.protocol;

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

import io.joyrpc.codec.compression.AdaptiveCompressOutputStream;
import io.joyrpc.codec.compression.Compression;
import io.joyrpc.codec.serialization.Serialization;
import io.joyrpc.constants.ExceptionCode;
import io.joyrpc.exception.CodecException;
import io.joyrpc.exception.LafException;
import io.joyrpc.exception.ProtocolException;
import io.joyrpc.exception.SerializerException;
import io.joyrpc.protocol.Protocol.MessageConverter;
import io.joyrpc.protocol.message.MessageHeader;
import io.joyrpc.protocol.message.RequestMessage;
import io.joyrpc.protocol.message.ResponseMessage;
import io.joyrpc.transport.buffer.ChannelBuffer;
import io.joyrpc.transport.codec.Codec;
import io.joyrpc.transport.codec.DecodeContext;
import io.joyrpc.transport.codec.EncodeContext;
import io.joyrpc.transport.codec.LengthFieldFrameCodec;
import io.joyrpc.transport.message.Header;
import io.joyrpc.transport.message.Message;
import io.joyrpc.transport.session.Session;
import io.joyrpc.util.StringUtils;
import io.joyrpc.util.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static io.joyrpc.Plugin.COMPRESSION_SELECTOR;
import static io.joyrpc.Plugin.SERIALIZATION_SELECTOR;

/**
 * ????????????
 */
public abstract class AbstractCodec implements Codec, LengthFieldFrameCodec {

    /**
     * ??????
     */
    protected Protocol protocol;

    /**
     * ????????????
     *
     * @param protocol ??????
     */
    public AbstractCodec(Protocol protocol) {
        this.protocol = protocol;
    }

    /**
     * ???????????????
     *
     * @param target    ?????????
     * @param converter ?????????
     * @return ????????????
     */
    protected <T> T convert(final T target, final MessageConverter converter) {
        Function<Object, Object> function = converter == null ? null : converter.message();
        return function == null ? target : (T) function.apply(target);
    }

    /**
     * ???????????????
     *
     * @param header    ??????
     * @param converter ?????????
     * @return ??????
     */
    protected Header convert(final Header header, final MessageConverter converter) {
        if (converter == null) {
            return header;
        }
        Header result = header;
        Function<Byte, Byte> serialization = converter.serialization();
        Function<Byte, Byte> compression = converter.compression();
        Function<Byte, Byte> checksum = converter.checksum();
        Function<Integer, Integer> messageType = converter.messageType();
        //???????????????
        if (messageType != null || serialization != null || compression != null || checksum != null) {
            result = header.clone();
            if (messageType != null) {
                result.setMsgType(messageType.apply(result.getMsgType()));
            }
            if (serialization != null) {
                result.setSerialization(serialization.apply(result.getSerialization()));
            }
            if (compression != null) {
                result.setCompression(compression.apply(result.getCompression()));
            }
            if (checksum != null) {
                result.setChecksum(checksum.apply(result.getChecksum()));
            }

        }
        return result;
    }

    /**
     * ????????????
     *
     * @param msg ????????????
     * @param e   ??????
     * @return ????????????
     */
    protected CodecException toCodecException(final String msg, final Exception e) {
        if (e instanceof CodecException) {
            CodecException err = (CodecException) e;
            if (StringUtils.isEmpty(err.getErrorCode())) {
                err.setErrorCode(ExceptionCode.CODEC_DEFAULT_EXCEPTION);
            }
            throw err;
        } else if (e instanceof LafException && StringUtils.isNotEmpty(((LafException) e).getErrorCode())) {
            return new CodecException(msg, e, ((LafException) e).getErrorCode());
        } else if (e instanceof SerializerException) {
            //???????????????
            return new CodecException(msg, e, ExceptionCode.CODEC_SERIALIZER_EXCEPTION);
        } else if (e instanceof IOException) {
            //???????????????
            return new CodecException(msg, e, ExceptionCode.CODEC_IO_EXCEPTION);
        } else {
            //?????????????????????
            return new CodecException(msg, e, ExceptionCode.CODEC_DEFAULT_EXCEPTION);
        }
    }


    @Override
    public void encode(final EncodeContext context, final ChannelBuffer buffer, final Object message) throws CodecException {
        if (message == null) {
            return;
        } else if (!(message instanceof Message)) {
            throw new ProtocolException("Not support this type of Object.");
        }
        Message target = (Message) message;
        Header header = null;
        try {
            //????????????
            MessageConverter converter = protocol.outMessage();
            //???????????????
            header = convert(target.getHeader(), converter);
            target = convert(target, converter);
            byte[] magicCodes = protocol.getMagicCode();
            buffer.ensureWritable(magicCodes == null ? 0 : magicCodes.length + 4 + estimateHeaderSize());
            //???????????????
            int start = buffer.writerIndex();
            if (magicCodes != null && magicCodes.length > 0) {
                buffer.setBytes(start, magicCodes, 0, magicCodes.length);
                start += magicCodes.length;
            }
            //?????????????????????
            buffer.setInt(start, 0);
            //??????????????????????????????
            buffer.writerIndex(start + 4);
            //???????????????
            int compress = encodeHeader(buffer, header);
            //???????????????
            if (target.getPayLoad() != null) {
                //???????????????
                encodePayload(context, buffer, target, compress);
            }
            int length = buffer.writerIndex() - start;
            header.setLength(length);
            buffer.setInt(start, length);
        } catch (CodecException e) {
            e.setHeader(header == null ? target.getHeader() : header);
            throw e;
        } catch (Exception e) {
            CodecException ce = toCodecException("Error occurs while encoding.", e);
            ce.setHeader(header == null ? target.getHeader() : header);
            throw ce;
        }
    }

    /**
     * ?????????????????????
     *
     * @return ?????????????????????
     */
    protected int estimateHeaderSize() {
        return 18;
    }

    /**
     * ????????????????????????????????????????????????????????????????????????????????????
     *
     * @param buffer ?????????
     * @param header ??????
     * @return ????????????
     */
    protected int encodeHeader(final ChannelBuffer buffer, final Header header) {
        //??????2??????????????????????????????????????????????????????????????????
        int start = buffer.writerIndex();
        buffer.setShort(start, 0);
        buffer.setByte(start + 2, header.getMsgType());
        buffer.setInt(start + 3, header.getMsgId());
        buffer.setInt(start + 7, header.getSessionId());
        buffer.setByte(start + 11, header.getSerialization());
        //????????????
        int result = start + 12;
        buffer.setByte(start + 12, Compression.NONE);
        //????????????????????????????????????????????????ID
        buffer.setInt(start + 13, header.getTimeout());
        //???????????????
        buffer.writerIndex(start + 17);
        //??????????????????
        MessageHeader messageHeader = (MessageHeader) header;
        encodeAttributes(buffer, messageHeader.getAttributes());
        int headLength = buffer.writerIndex() - start;
        header.setHeaderLength((short) headLength);
        // ??????head???????????????
        buffer.setShort(start, headLength);
        return result;
    }

    /**
     * ????????????????????????
     *
     * @param buffer     ?????????
     * @param attributes ??????
     */
    protected void encodeAttributes(final ChannelBuffer buffer, final Map<Byte, Object> attributes) {
        int size = attributes == null ? 0 : attributes.size();
        int pos = buffer.writerIndex();
        buffer.setByte(pos++, size);
        if (size > 0) {
            byte key;
            Object val;
            for (Map.Entry<Byte, Object> attr : attributes.entrySet()) {
                key = attr.getKey();
                val = attr.getValue();
                if (val != null) {
                    if (val instanceof Integer) {
                        buffer.ensureWritable(6);
                        buffer.setByte(pos++, key);
                        buffer.setByte(pos++, (byte) 1);
                        buffer.setInt(pos, (Integer) val);
                        pos += 4;
                    } else if (val instanceof String) {
                        byte[] bytes = ((String) val).getBytes(StandardCharsets.UTF_8);
                        int length = bytes.length;
                        buffer.ensureWritable(4 + length);
                        buffer.setByte(pos++, key);
                        buffer.setByte(pos++, (byte) 2);
                        buffer.setShort(pos, length);
                        pos += 2;
                        if (length > 0) {
                            buffer.setBytes(pos, bytes, 0, length);
                            pos += bytes.length;
                        }
                    } else if (val instanceof Byte) {
                        buffer.ensureWritable(3);
                        buffer.setByte(pos++, key);
                        buffer.setByte(pos++, (byte) 3);
                        buffer.setByte(pos++, (Byte) val);
                    } else if (val instanceof Short) {
                        buffer.ensureWritable(4);
                        buffer.setByte(pos++, key);
                        buffer.setByte(pos++, (byte) 4);
                        buffer.setShort(pos, (Short) val);
                        pos += 2;
                    } else {
                        throw new CodecException("Value of attrs in message header must be byte/short/int/string", ExceptionCode.CODEC_HEADER_FORMAT_EXCEPTION);
                    }
                }
            }
        }
        buffer.writerIndex(pos);
    }

    /**
     * ???????????????
     *
     * @param context  ?????????
     * @param buffer   ?????????
     * @param message  ??????
     * @param compress ????????????
     */
    protected void encodePayload(final EncodeContext context, final ChannelBuffer buffer, final Message message, final int compress) throws Exception {
        Header header = message.getHeader();
        // ?????????object??????
        Serialization serialization = SERIALIZATION_SELECTOR.select(header.getSerialization());
        if (serialization == null) {
            throw new CodecException(String.format("serialization %d is not found.", header.getSerialization()));
        }
        //?????????????????????????????????????????????
        adjustEncode(message, serialization);

        if (header.getCompression() > 0) {
            Compression compression = COMPRESSION_SELECTOR.select(header.getCompression());
            if (compression != null) {
                //???????????????
                AdaptiveCompressOutputStream acos = new AdaptiveCompressOutputStream(buffer, compression, 2048);
                serialize(serialization, acos, message, context);
                //?????????????????????????????????
                acos.finish();
                //??????
                acos.flush();
                //??????????????????
                buffer.setByte(compress, !acos.isCompressed() ? Compression.NONE : header.getCompression());
                return;
            } else {
                buffer.setByte(compress, Compression.NONE);
            }
        }
        serialize(serialization, buffer.outputStream(), message, context);
    }

    /**
     * ????????????????????????????????????????????????????????????
     *
     * @param message       ??????
     * @param serialization ?????????
     */
    protected void adjustEncode(final Message message, final Serialization serialization) {

    }

    /**
     * ?????????
     *
     * @param serialization ?????????
     * @param os            ?????????
     * @param message       ??????
     * @param context       ?????????
     */
    protected void serialize(final Serialization serialization, final OutputStream os, final Message message, final EncodeContext context) {
        serialization.getSerializer().serialize(os, message.getPayLoad());
    }

    @Override
    public Object decode(final DecodeContext context, final ChannelBuffer buffer) throws CodecException {
        if (buffer.readableBytes() < 1) {
            return null;
        }
        Header header = null;
        try {
            header = decodeHeader(buffer);
            if (header == null) {
                return null;
            }
            //??????session
            int sessionId = header.getSessionId();
            Session session = header.getSession();
            if (session == null || session.getSessionId() != sessionId) {
                if (sessionId > 0) {
                    header.setSession(context.getChannel().getSession(sessionId));
                } else if (session != null) {
                    header.setSession(null);
                }
            }
            //????????????
            MessageConverter converter = protocol.inMessage();
            header = convert(header, converter);
            return convert(decodeMessage(context, buffer, header), converter);
        } catch (CodecException e) {
            e.setHeader(header);
            throw e;
        } catch (Exception e) {
            CodecException ce = toCodecException("Error occurs while decoding.", e);
            ce.setHeader(header);
            throw ce;
        }
    }

    /**
     * ???????????????
     *
     * @return ?????????
     */
    protected Header decodeHeader(final ChannelBuffer buffer) {
        // ???????????????
        int length = buffer.readInt();
        // ???????????????
        short headerLength = buffer.readShort();

        MessageHeader header = new MessageHeader();
        header.setMsgType(buffer.readByte());
        header.setMsgId(buffer.readInt());
        header.setSessionId(buffer.readInt());
        header.setSerialization(buffer.readByte());
        header.setCompression(buffer.readByte());
        header.setTimeout(buffer.readInt());
        header.setAttributes(decodeAttributes(buffer));
        header.setLength(length);
        header.setHeaderLength(headerLength);
        header.setProtocolType(AbstractProtocol.PROTOCOL_NUMBER);
        return header;
    }

    /**
     * ????????????
     *
     * @param context ?????????
     * @param buffer  ?????????
     * @param header  ??????
     * @return ??????
     */
    protected Object decodeMessage(final DecodeContext context, final ChannelBuffer buffer, final Header header) throws Exception {
        MsgType msgType = MsgType.valueOf((byte) header.getMsgType());
        if (msgType == null) {
            throw new CodecException(String.format("Error occurs while decoding. unknown message type %d!", header.getMsgType()), ExceptionCode.CODEC_HEADER_FORMAT_EXCEPTION);
        }
        //TODO ??????????????????????????????
        MessageHeader msgHeader = (MessageHeader) header;
        if (buffer.readableBytes() <= 0) {
            if (msgType.isRequest()) {
                return new RequestMessage<>(msgHeader);
            } else {
                return new ResponseMessage<>(msgHeader);
            }
        }

        Serialization serialization = SERIALIZATION_SELECTOR.select(header.getSerialization());
        if (serialization == null) {
            throw new CodecException(String.format("Error occurs while decoding. unknown serialization type %d!", header.getSerialization()), ExceptionCode.CODEC_SERIALIZER_EXCEPTION);
        }
        Compression compression = COMPRESSION_SELECTOR.select(header.getCompression());
        InputStream inputStream = buffer.inputStream();
        inputStream = compression == null ? inputStream : compression.decompress(inputStream);
        Class payloadClass = getPayloadClass(msgType);

        Object payload = payloadClass == null ? null : deserialize(serialization, inputStream, payloadClass, msgHeader, context);
        if (msgType.isRequest()) {
            RequestMessage request = new RequestMessage(msgHeader, payload);
            request.setReceiveTime(SystemClock.now());
            adjustDecode(request, serialization);
            return request;
        } else {
            ResponseMessage<Object> response = new ResponseMessage<>(msgHeader, payload);
            adjustDecode(response, serialization);
            return response;
        }

    }

    /**
     * ????????????
     *
     * @param serialization ?????????
     * @param is            ?????????
     * @param type          ??????
     * @param header        ???
     * @param context       ?????????
     */
    protected Object deserialize(final Serialization serialization, final InputStream is, final Type type, final MessageHeader header, final DecodeContext context) {
        return serialization.getSerializer().deserialize(is, type);
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param message       ??????
     * @param serialization ?????????
     */
    protected void adjustDecode(final Message message, final Serialization serialization) {

    }

    /**
     * ????????????????????????
     *
     * @param type ??????
     * @return ?????????
     */
    protected Class getPayloadClass(final MsgType type) {
        return type.getPayloadClz();
    }

    /**
     * ??????????????????
     *
     * @param buffer ?????????
     * @return ????????????
     */
    protected Map<Byte, Object> decodeAttributes(final ChannelBuffer buffer) {
        byte size = buffer.readByte();
        if (size <= 0) {
            return null;
        }
        Map<Byte, Object> attributes = new HashMap<>(size);
        byte key;
        byte type;
        for (int i = 0; i < size; i++) {
            key = buffer.readByte();
            type = buffer.readByte();
            switch (type) {
                case 1:
                    attributes.put(key, buffer.readInt());
                    break;
                case 2:
                    attributes.put(key, buffer.readString(null, true));
                    break;
                case 3:
                    attributes.put(key, buffer.readByte());
                    break;
                case 4:
                    attributes.put(key, buffer.readShort());
                    break;
                default:
                    throw new CodecException("Value of attrs in message header must be byte/short/int/string", ExceptionCode.CODEC_HEADER_FORMAT_EXCEPTION);

            }
        }
        return attributes;
    }

    @Override
    public LengthFieldFrame getLengthFieldFrame() {
        return new LengthFieldFrame(2, 4, -4, 2);
    }

    /**
     * ??????
     */
    public enum Empty {
        /**
         * ??????
         */
        NULL
    }
}
