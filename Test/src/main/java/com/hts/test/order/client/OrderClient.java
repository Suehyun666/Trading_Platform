package com.hts.test.order.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hts.generated.grpc.*;
import com.hts.test.common.PacketHeader;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class OrderClient {
    private static final Logger log = LoggerFactory.getLogger(OrderClient.class);
    private static final AtomicLong correlationIdCounter = new AtomicLong(1);

    private final String host;
    private final int port;
    private final long accountId;
    private Channel channel;
    private EventLoopGroup group;
    private volatile CompletableFuture<ServerResponse> responseFuture;

    public OrderClient(String host, int port, long accountId) {
        this.host = host;
        this.port = port;
        this.accountId = accountId;
    }

    public void connect() throws Exception {
        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ResponseHandler());
                    }
                });

        ChannelFuture future = bootstrap.connect(host, port).sync();
        this.channel = future.channel();
        log.info("Connected to {}:{} for account {}", host, port, accountId);
    }

    public CompletableFuture<ServerResponse> placeOrder(String symbol, String side, long quantity, long price) {
        try {
            long correlationId = correlationIdCounter.getAndIncrement();
            long sessionId = accountId; // sessionId = accountId for testing

            // Build protobuf request
            var secure = SecureSession.newBuilder()
                    .setSessionId(sessionId)
                    .build();

            var request = NewOrderRequest.newBuilder()
                    .setSecure(secure)
                    .setSymbol(symbol)
                    .setSide(Side.valueOf(side))
                    .setOrderType(OrderType.LIMIT)
                    .setQuantity(quantity)
                    .setPrice(price)
                    .setTimeInForce(TimeInForce.DAY)
                    .build();

            byte[] protoBytes = request.toByteArray();

            // Build packet header (24 bytes)
            PacketHeader header = PacketHeader.createRequest(
                    PacketHeader.SERVICE_ORDER,
                    PacketHeader.METHOD_PLACE_ORDER,
                    correlationId,
                    protoBytes.length
            );

            // Encode: length (4 bytes) + header (24 bytes) + proto payload
            int totalLen = PacketHeader.HEADER_SIZE + protoBytes.length;
            ByteBuf frame = Unpooled.buffer(4 + totalLen);
            frame.writeInt(totalLen);
            header.encode(frame);
            frame.writeBytes(protoBytes);

            // Send and wait for response
            responseFuture = new CompletableFuture<>();
            channel.writeAndFlush(frame).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    responseFuture.completeExceptionally(future.cause());
                }
            });

            return responseFuture;

        } catch (Exception e) {
            CompletableFuture<ServerResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }

    private ServerResponse parseResponse(ByteBuf payload) throws InvalidProtocolBufferException {
        // Decode header (24 bytes)
        PacketHeader header = PacketHeader.decode(payload);

        // Check if error response
        if (header.isError()) {
            byte[] protoBytes = new byte[header.getPayloadLen()];
            payload.readBytes(protoBytes);

            var errorMsg = ErrorMessage.parseFrom(protoBytes);
            return new ServerResponse(
                    header.getCorrelationId(),
                    true,
                    errorMsg.getCode(),
                    errorMsg.getMessage(),
                    0L,
                    null
            );
        }

        // Success response
        byte[] protoBytes = new byte[header.getPayloadLen()];
        payload.readBytes(protoBytes);

        var orderResponse = OrderResponse.parseFrom(protoBytes);
        return new ServerResponse(
                header.getCorrelationId(),
                false,
                0,
                orderResponse.getMessage(),
                orderResponse.getOrderId(),
                orderResponse.getStatus().name()
        );
    }

    public void disconnect() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private class ResponseHandler extends ChannelInboundHandlerAdapter {
        private ByteBuf accumulator = Unpooled.buffer();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            accumulator.writeBytes(buf);
            buf.release();

            // Try to read frame: length (4 bytes) + header (24 bytes) + payload
            while (accumulator.readableBytes() >= 4) {
                accumulator.markReaderIndex();
                int totalLen = accumulator.readInt();

                if (accumulator.readableBytes() < totalLen) {
                    accumulator.resetReaderIndex();
                    break;
                }

                // Read complete frame
                ByteBuf framePayload = accumulator.readBytes(totalLen);

                try {
                    ServerResponse response = parseResponse(framePayload);
                    if (responseFuture != null && !responseFuture.isDone()) {
                        responseFuture.complete(response);
                    }
                } catch (Exception e) {
                    if (responseFuture != null && !responseFuture.isDone()) {
                        responseFuture.completeExceptionally(e);
                    }
                } finally {
                    framePayload.release();
                }
            }

            accumulator.discardReadBytes();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Client error for account {}", accountId, cause);
            if (responseFuture != null && !responseFuture.isDone()) {
                responseFuture.completeExceptionally(cause);
            }
            ctx.close();
        }
    }

    public record ServerResponse(
            long correlationId,
            boolean isError,
            int errorCode,
            String message,
            long orderId,
            String status
    ) {
        @Override
        public String toString() {
            if (isError) {
                return String.format("ErrorResponse[corrId=%d, code=%d, msg='%s']",
                        correlationId, errorCode, message);
            } else {
                return String.format("OrderResponse[corrId=%d, orderId=%d, status=%s, msg='%s']",
                        correlationId, orderId, status, message);
            }
        }
    }
}
