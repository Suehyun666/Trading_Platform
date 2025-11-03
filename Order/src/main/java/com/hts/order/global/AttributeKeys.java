package com.hts.order.global;

import com.hts.order.core.protocol.PacketHeader;
import io.netty.util.AttributeKey;

public final class AttributeKeys {
    public static final AttributeKey<String> SESSION_ID = AttributeKey.valueOf("SESSION_ID");
    public static final AttributeKey<Long> ACCOUNT_ID = AttributeKey.valueOf("ACCOUNT_ID");
    public static final AttributeKey<PacketHeader> HEADER_KEY = AttributeKey.valueOf("packet_header");
    public static final AttributeKey<String> LAST_ERROR = AttributeKey.valueOf("FrameDecoder:LAST_ERROR");

    private AttributeKeys() {}
}
