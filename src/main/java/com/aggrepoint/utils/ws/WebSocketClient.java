package com.aggrepoint.utils.ws;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * 一个WebSocketClient实例代表一个WebSocket的客户连接。
 *
 * @param <T>
 * @param <V>
 */
public class WebSocketClient<T extends WebSocketFrame, V> implements WebSocketClientIntf {
	public static final long DEFAULT_INACTIVE_TIME = 60 * 1000;
	private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);
	private static ScheduledExecutorService scheduleService = Executors.newScheduledThreadPool(20);

	private WebSocketClientBuilder<T, V> builder;
	/** 不为空表示连接成功 */
	private Channel channel;
	private long connectedTime;
	private long disconnectTime;
	/** 最后一次活跃时间 */
	private long activeTimestamp;
	/** 最后一次主动发送心跳的时间 */
	private long heartBeatTime;
	/** 发送请求消息的时间间隔 */
	private long requestInterval;

	/** 是否正在连接中。避免重复连接 */
	private boolean connecting = false;
	/** websocket的onConnect是否已经运行完毕 */
	private boolean connected = false;
	private ReentrantLock connectedLock = new ReentrantLock();

	/** 消息发送速度控制：当前正在等待发送的所有消息 */
	private List<Object> messages = new ArrayList<>();
	/** 消息发送速度控制：最后一次发送消息的时间 */
	private long lastSendTime;
	/** 当前是否有调度任务正在运行 */
	private boolean scheduling = false;

	private String localIp = null;

	@Override
	public void logStats() {
		logger.info("{}@{} {} connect: {} active: {} heartbeat: {} msgcount: {} lastsend: {} last disconnect: {}",
				builder.key, localIp == null ? "" : localIp, builder.uri.toString(),
				connected + "/" + isChannelConnected() + "/" + DateFormat.format(connectedTime),
				DateFormat.format(activeTimestamp), DateFormat.format(heartBeatTime), messages.size(),
				DateFormat.format(lastSendTime), DateFormat.format(disconnectTime));
	}

	/**
	 * 调度时间到，从消息队列中取出一个发送
	 */
	private void retrieveAndSend() {
		synchronized (messages) {
			if (messages.size() > 0) {
				channel.writeAndFlush(messages.get(0));
				lastSendTime = System.currentTimeMillis();
				messages.remove(0);
			}

			if (messages.size() > 0)
				scheduleService.schedule(() -> retrieveAndSend(), requestInterval, TimeUnit.MILLISECONDS);
			else
				scheduling = false;
		}
	}

	/**
	 * 返回true表示已经发送，false表示等待调度
	 */
	public synchronized boolean sendMessage(Object obj) {
		if (requestInterval == 0 || !scheduling && System.currentTimeMillis() - lastSendTime > requestInterval) {
			channel.writeAndFlush(obj);
			lastSendTime = System.currentTimeMillis();
			return true;
		}

		synchronized (messages) {
			messages.add(obj);
			if (!scheduling) {
				long scheduleTime = requestInterval - (System.currentTimeMillis() - lastSendTime);
				if (scheduleTime <= 0)
					scheduleTime = 1;

				scheduling = true;
				scheduleService.schedule(() -> retrieveAndSend(), scheduleTime, TimeUnit.MILLISECONDS);
			}
		}
		return false;
	}

	/**
	 * 如果WebSocketClient是被WebSocketClientMonitor管理，true表示接受监控管理，包括自动重连，检查消息活跃，触发心跳
	 */
	private boolean beMonitored = true;

	protected WebSocketClient(long requestInterval, WebSocketClientBuilder<T, V> builder) {
		this.requestInterval = requestInterval;
		this.builder = builder;
	}

	public WebSocketClientBuilder<T, V> getBuilder() {
		return builder;
	}

	@Override
	public String getKey() {
		return builder.key;
	}

	static NioEventLoopGroup group = new NioEventLoopGroup();

	/** 断开连接。如果受监管，随后会被自动重连 */
	public synchronized void disconnect() {
		if (channel == null)
			return;

		try {
			channel.writeAndFlush(new CloseWebSocketFrame());
			channel.closeFuture().await(1000);
		} catch (Exception e) {
			logger.error("异常", e);
		}

		if (channel != null) { // 没有在1秒中内正常终止，强行终止
			try {
				channel.close();
			} catch (Exception e) {
			}
			channel = null;
			connected = false;
		}
	}

	/**
	 * 如果当前正在连接，返回NULL。如果当前已经连接，则先中断连接
	 * 
	 * @return
	 * @throws InterruptedException
	 * @throws SSLException
	 */
	@Override
	public synchronized boolean open(String localIp) throws Exception {
		if (connecting) {
			logger.info("[" + builder.key + "] 已经在连接中");
			return false;
		}

		this.localIp = localIp;

		if (channel != null)
			disconnect();

		connecting = true;
		connected = false;

		if (builder.starting != null)
			builder.starting.run();

		String theIp = localIp == null ? "" : localIp;

		String scheme = builder.uri.getScheme() == null ? "ws" : builder.uri.getScheme();
		final String host = builder.uri.getHost();
		final int port;
		if (builder.uri.getPort() == -1) {
			if ("ws".equalsIgnoreCase(scheme)) {
				port = 80;
			} else if ("wss".equalsIgnoreCase(scheme)) {
				port = 443;
			} else {
				port = -1;
			}
		} else {
			port = builder.uri.getPort();
		}
		final SslContext sslCtx;
		if ("wss".equalsIgnoreCase(builder.uri.getScheme())) {
			sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		} else {
			sslCtx = null;
		}

		@SuppressWarnings("unchecked")
		final WebSocketClientHandler<V> handler = new WebSocketClientHandler<>(builder.key, builder.data,
				WebSocketClientHandshakerFactory.newHandshaker(builder.uri, WebSocketVersion.V13, null, false,
						new DefaultHttpHeaders(), 1000 * 1024),
				(channel, data) -> {
					connectedLock.lock();
					try {
						if (builder.connectedHandler == null)
							return;

						if (builder.execSvc == null)
							builder.connectedHandler.accept(channel, data);
						else
							builder.execSvc.submit(() -> {
								builder.connectedHandler.accept(channel, data);
							});
					} finally {
						connected = true;
						connectedLock.unlock();
					}
				}, (channel, frame, data) -> {
					activeTimestamp = System.currentTimeMillis();

					if (builder.frameHandler == null)
						return;

					if (builder.execSvc == null)
						builder.frameHandler.accept(channel, (T) frame, data);
					else {
						// 保留frame对象，使其可以在execSvc线程中访问
						frame.retain();
						builder.execSvc.submit(() -> {
							try {
								builder.frameHandler.accept(channel, (T) frame, data);
							} finally {
								// 释放frame对象
								frame.release();
							}
						});
					}
				});

		Bootstrap b = new Bootstrap();

		b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
			@Override
			public void initChannel(SocketChannel ch) throws Exception {
				ChannelPipeline pipeline = ch.pipeline();
				if (sslCtx != null)
					pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));
				pipeline.addLast(new HttpClientCodec());
				pipeline.addLast(new HttpObjectAggregator(65535));
				pipeline.addLast(handler);
			}
		});

		activeTimestamp = System.currentTimeMillis();
		logger.info(theIp + "[" + builder.key + "] 正在启动...");

		ChannelFuture cf = null;
		if (localIp != null && !"".equals(localIp))
			cf = b.connect(new InetSocketAddress(builder.uri.getHost(), port), new InetSocketAddress(localIp, 0));
		else
			cf = b.connect(builder.uri.getHost(), port);

		cf.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				if (!future.isSuccess()) {
					connecting = false;

					logger.info(theIp + "[" + builder.key + "] 建立连接失败");

					if (builder.connectError != null && !builder.connectError.apply(future.cause(), builder.data))
						beMonitored = false;
				} else {
					connectedTime = heartBeatTime = activeTimestamp = System.currentTimeMillis();

					logger.info(theIp + "[" + builder.key + "] 建立连接成功");

					// { 设置中断监听
					Channel theChannel = channel = future.channel();
					channel.closeFuture().addListener(new ChannelFutureListener() {
						@Override
						public void operationComplete(ChannelFuture future) throws Exception {
							disconnectTime = System.currentTimeMillis();

							logger.info(theIp + "[" + builder.key + "] 连接中断");

							if (channel == theChannel) {
								channel = null;
								connected = false;
							}

							if (builder.disconnected != null)
								if (builder.execSvc == null)
									builder.disconnected.accept(builder.data);
								else
									builder.execSvc.submit(() -> builder.disconnected.accept(builder.data));
						}
					});
					// }
				}

				connecting = false;
			}
		});

		return true;

	}

	public boolean reconnect() throws Exception {
		return open(localIp);
	}

	@Override
	public boolean isChannelConnected() {
		return channel != null;
	}

	public boolean isConnected() {
		return connected;
	}

	@Override
	public boolean isConnecting() {
		return connecting;
	}

	public boolean ifConnected(Runnable connected) {
		connectedLock.lock();
		try {
			if (this.connected) {
				connected.run();
				return true;
			}
		} finally {
			connectedLock.unlock();
		}
		return false;
	}

	@Override
	public boolean beMonitored() {
		return beMonitored;
	}

	@Override
	public void heartBeat() {
		if (builder.heartBeat != null && channel != null)
			if (builder.execSvc == null)
				builder.heartBeat.accept(channel, builder.data);
			else
				builder.execSvc.submit(() -> {
					builder.heartBeat.accept(channel, builder.data);
				});

		heartBeatTime = System.currentTimeMillis();
	}

	@Override
	public Long getHeartBeatTime() {
		if (builder.heartBeat == null || channel == null)
			return null;
		return heartBeatTime;
	}

	@Override
	public long getInactiveTime() {
		return System.currentTimeMillis() - activeTimestamp;
	}

	/** 断开连接并且脱离监管，不再自动重连 */
	public void stop() throws InterruptedException {
		beMonitored = false;
		disconnect();
	}

	@Override
	public boolean disconnectIfInactive() {
		if (!connected || builder.maxInactiveTime == 0 || getInactiveTime() < builder.maxInactiveTime)
			return false;
		disconnect();
		return true;
	}

	@Override
	public boolean disconnectIfReconnect() {
		if (!connected || builder.reconnectInterval == 0
				|| System.currentTimeMillis() - connectedTime < builder.reconnectInterval)
			return false;
		disconnect();
		return true;
	}

	public Channel getChannel() {
		return channel;
	}

	@Override
	public void setMonitored() {
		beMonitored = true;
	}
}
