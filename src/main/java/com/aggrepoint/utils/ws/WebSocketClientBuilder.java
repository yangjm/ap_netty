package com.aggrepoint.utils.ws;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.aggrepoint.utils.TriConsumer;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * <pre>
 * 保存构建WebSocketClient所需的参数，构建WebSocketClient
 * 允许在WebSocketClient实例创建后动态修改参数
 * </pre>
 * 
 * @author jiangmingyang
 *
 * @param <T>
 * @param <V>
 */
public class WebSocketClientBuilder<T extends WebSocketFrame, V> {
	public static final long DEFAULT_INACTIVE_TIME = 60 * 1000;

	URI uri;

	/** 在日志中代表一个连接 */
	String key;

	/** 负责执行回调函数的ExecutorService */
	ExecutorService execSvc;

	/** 准备启动运行回调。可以在启动运行前调整Builder */
	Runnable starting;
	/** 连接成功回调 */
	BiConsumer<Channel, V> connectedHandler;
	/** 接收到数据包回调 */
	TriConsumer<Channel, T, V> frameHandler;
	/** 发送心跳 */
	BiConsumer<Channel, V> heartBeat;
	/** 连接断开回调 */
	Consumer<V> disconnected;
	/** 建立连接遇到异常 */
	BiFunction<Throwable, V, Boolean> connectError;
	/** 附加数据，从构造函数传入，被传递给各个回调方法 */
	V data;
	/**
	 * 最长允许不活跃的时间。如果WebSocketClient是被WebSocketClientMonitor管理，不活跃超过这个时间的话，连接会被重新建立
	 */
	long maxInactiveTime = DEFAULT_INACTIVE_TIME;
	/** 发送请求消息的时间间隔 */
	long requestInterval;

	/**
	 * @param execSvc 如果不为空，则用其执行connectedHandler、frameHandler、heartBeat和disconnected。
	 *                connectError不用execSvc执行，因为需要在遇到异常时根据其返回结果决定是否要停止连接
	 * @param key
	 * @param uri
	 */
	public WebSocketClientBuilder(ExecutorService execSvc, String key, final String uri) {
		this.key = key;
		this.execSvc = execSvc;
		setUri(uri);
	}

	public WebSocketClientBuilder<T, V> setExecutorService(ExecutorService execSvc) {
		this.execSvc = execSvc;
		return this;
	}

	public WebSocketClientBuilder<T, V> setKey(String key) {
		this.key = key;
		return this;
	}

	public WebSocketClientBuilder<T, V> setUri(String uri) {
		this.uri = URI.create(uri);
		String protocol = this.uri.getScheme();
		if (!"ws".equals(protocol) && !"wss".equals(protocol)) {
			throw new IllegalArgumentException("Unsupported protocol: " + protocol);
		}
		return this;
	}

	public WebSocketClientBuilder<T, V> setData(V data) {
		this.data = data;
		return this;
	}

	public WebSocketClientBuilder<T, V> setMaxInactiveTime(long t) {
		maxInactiveTime = t;
		return this;
	}

	public WebSocketClientBuilder<T, V> setRequestInterval(long t) {
		requestInterval = t;
		return this;
	}

	public WebSocketClientBuilder<T, V> onStarting(Runnable v) {
		starting = v;
		return this;
	}

	public WebSocketClientBuilder<T, V> onConnected(BiConsumer<Channel, V> v) {
		connectedHandler = v;
		return this;
	}

	public WebSocketClientBuilder<T, V> onFrame(TriConsumer<Channel, T, V> v) {
		frameHandler = v;
		return this;
	}

	public WebSocketClientBuilder<T, V> onHeartBeat(BiConsumer<Channel, V> v) {
		heartBeat = v;
		return this;
	}

	public WebSocketClientBuilder<T, V> onDisconnected(Consumer<V> v) {
		disconnected = v;
		return this;
	}

	/**
	 * @param v 返回false表示停止重试
	 * @return
	 */
	public WebSocketClientBuilder<T, V> onConnectError(BiFunction<Throwable, V, Boolean> v) {
		connectError = v;
		return this;
	}

	public WebSocketClient<T, V> build() {
		return new WebSocketClient<T, V>(requestInterval, this);
	}
}
