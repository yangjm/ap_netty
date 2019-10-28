package com.aggrepoint.utils.netty.test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aggrepoint.utils.ws.WebSocketClientBuilder;
import com.aggrepoint.utils.ws.WebSocketClientMonitorSingle;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

public class WebSocketTest {
	private static final Logger logger = LoggerFactory.getLogger(WebSocketTest.class);
	ExecutorService executor = Executors.newFixedThreadPool(10);

	@Test
	public void clientTest() {
		WebSocketClientMonitorSingle cm = new WebSocketClientMonitorSingle("test", null, 0, 1000, 1000);

		Consumer<String> startDepth = symbol -> {
			cm.startClient(new WebSocketClientBuilder<TextWebSocketFrame, String>(executor, symbol,
					"wss://stream.binance.com:9443/ws/" + symbol.toLowerCase() + "@depth5").setData(symbol)
							.onConnected((channel, data) -> {
								logger.info("{} 连接成功", data);
							}).onFrame((channel, frame, data) -> {
								logger.info("{} 接收到数据: {}", data, frame.text());
							}).onDisconnected(data -> {
								logger.info("{} 连接断开", data);
							}).build());
		};

		startDepth.accept("ETHBTC");
		startDepth.accept("ETHUSDT");
		startDepth.accept("BTCUSDT");
		startDepth.accept("EOSETH");
		startDepth.accept("EOSBTC");
		startDepth.accept("EOSUSDT");
		cm.sync();
	}
}
