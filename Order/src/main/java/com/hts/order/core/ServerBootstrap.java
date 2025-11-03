package com.hts.order.core;

import com.hts.order.cache.OrderIndexCache;
import com.hts.order.config.OrderExecutorConfig;
import com.hts.order.config.ServerConfig;
import com.hts.order.core.pipeline.*;
import com.hts.order.global.DtoMapper;
import com.hts.order.global.HandlerRegistry;
import com.hts.order.global.ProtobufParser;
import com.hts.order.repository.OrderRepository;
import com.hts.order.shard.ConsistentShardSelector;
import com.hts.order.shard.OrderShardExecutor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.concurrent.ExecutorService;

@Singleton
public class ServerBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ServerBootstrap.class);

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ExecutorService blockingPool;
    private final ServerConfig serverConfig;
    private final HandlerRegistry handlerRegistry;
    private final ProtobufParser protobufParser;
    private final DtoMapper dtoMapper;
    private final ConsistentShardSelector shardSelector;
    private final OrderShardExecutor orderShardExecutor;
    private final OrderIndexCache orderIndexCache;
    private final OrderRepository orderRepository;
    private final OrderExecutorConfig orderExecutorConfig;
    private final io.netty.bootstrap.ServerBootstrap bootstrap;

    private Channel serverChannel;

    @Inject
    public ServerBootstrap(
            @Named("bossGroup") EventLoopGroup bossGroup,
            @Named("workerGroup") EventLoopGroup workerGroup,
            @Named("blockingPool") ExecutorService blockingPool,
            ServerConfig serverConfig,
            HandlerRegistry handlerRegistry,
            ProtobufParser protobufParser,
            DtoMapper dtoMapper,
            ConsistentShardSelector shardSelector,
            OrderShardExecutor orderShardExecutor,
            OrderIndexCache orderIndexCache,
            OrderRepository orderRepository,
            OrderExecutorConfig orderExecutorConfig) {

        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.blockingPool = blockingPool;
        this.serverConfig = serverConfig;
        this.handlerRegistry = handlerRegistry;
        this.protobufParser = protobufParser;
        this.dtoMapper = dtoMapper;
        this.shardSelector = shardSelector;
        this.orderShardExecutor = orderShardExecutor;
        this.orderIndexCache = orderIndexCache;
        this.orderRepository = orderRepository;
        this.orderExecutorConfig = orderExecutorConfig;

        PacketDecoder packetDecoder = new PacketDecoder();
        ExceptionHandler exceptionHandler = new ExceptionHandler();

        this.bootstrap = new io.netty.bootstrap.ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(EpollServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new FrameDecoder());
                        p.addLast(packetDecoder);
                        p.addLast(new PayloadDecoder(protobufParser));
                        p.addLast(new DispatchHandler(
                                handlerRegistry,
                                dtoMapper,
                                blockingPool,
                                shardSelector,
                                orderShardExecutor,
                                orderIndexCache,
                                orderRepository,
                                orderExecutorConfig
                        ));
                        p.addLast(exceptionHandler);
                    }
                });
    }

    public void start() {
        try {
            int port = serverConfig.getPort();
            log.info("Starting order on port {}", port);

            serverChannel = bootstrap.bind(port).sync().channel();

            log.info("Server started successfully on port {}", port);
            log.info("Boss threads: {}, Worker threads: {}, Blocking threads: {}",
                    serverConfig.getBossThreads(),
                    serverConfig.getWorkerThreads(),
                    serverConfig.getBlockingPoolThreads());

            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Server interrupted", e);
        } finally {
            stop();
        }
    }

    public void stop() {
        log.info("Shutting down order...");

        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            bossGroup.shutdownGracefully().sync();
            workerGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        blockingPool.shutdown();

        log.info("Server shutdown complete");
    }
}
