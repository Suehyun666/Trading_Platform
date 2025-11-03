package com.hts.order.global;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtobufParser {
    private final Map<Short, Map<Short, Parser<?>>> parsers = new ConcurrentHashMap<>();

    /**
     * ByteBuf를 Protobuf 메시지로 파싱 (Zero-copy 최적화)
     *
     * Direct ByteBuffer를 사용하는 경우 CodedInputStream.newInstance(ByteBuffer)로
     * 메모리 복사 없이 파싱합니다. Heap buffer인 경우 기존 방식을 사용합니다.
     *
     * @param payload Protobuf 직렬화된 데이터
     * @param parser Protobuf 파서
     * @param <T> Protobuf 메시지 타입
     * @return 파싱된 메시지
     * @throws InvalidProtocolBufferException 파싱 실패 시
     */
    public static <T> T parse(ByteBuf payload, Parser<T> parser) throws InvalidProtocolBufferException {
        // Direct buffer인 경우 Zero-copy 최적화
        if (payload.nioBufferCount() == 1) {
            ByteBuffer nioBuffer = payload.nioBuffer();
            CodedInputStream codedInputStream = CodedInputStream.newInstance(nioBuffer);
            return parser.parseFrom(codedInputStream);
        }

        // Heap buffer 또는 composite buffer인 경우 기존 방식
        byte[] bytes = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), bytes);
        return parser.parseFrom(bytes);
    }

    public <T> void register(short serviceId, short methodId, Parser<T> parser) {
        // Parser 등록
        parsers.computeIfAbsent(serviceId, k -> new ConcurrentHashMap<>()).
                put(methodId, parser);
    }

    public Parser<?> getParser(short serviceId, short methodId) {
        Map<Short, Parser<?>> serviceParsers = parsers.get(serviceId);
        return serviceParsers != null ? serviceParsers.get(methodId) : null;
    }
}
