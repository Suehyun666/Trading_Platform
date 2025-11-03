package com.hts.order.core.protocol;

import com.google.protobuf.Message;

public record MessageEnvelope(PacketHeader header, Message payload) {}
