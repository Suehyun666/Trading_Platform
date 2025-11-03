package com.hts.order.core.pipeline;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.hts.order.core.exception.ProtocolException;
import com.hts.order.core.protocol.Frame;
import com.hts.order.core.protocol.MessageEnvelope;
import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.global.ProtobufParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public final class PayloadDecoder extends MessageToMessageDecoder<Frame> {
    private static final Logger log = LoggerFactory.getLogger(PayloadDecoder.class);
    private final ProtobufParser parsers;

    public PayloadDecoder(ProtobufParser parsers) {
        this.parsers = parsers;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Frame frame, List<Object> out) throws Exception {
        try {
            PacketHeader header = frame.header();
            Parser<?> parser = parsers.getParser(header.getServiceId(), header.getMethodId());
            if (parser == null) {
                throw new ProtocolException("No parser registered: serviceId=" +
                        header.getServiceId() + ", methodId=" + header.getMethodId(),
                        header, ProtocolException.DECODE_ERROR);
            }

            Message payload = (Message) ProtobufParser.parse(frame.payload(), parser);
            out.add(new MessageEnvelope(header, payload));
        } finally {
            frame.release();
        }
    }
}
