package com.aggrepoint.utils.ws;

import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aggrepoint.utils.TriConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * @author jiangmingyang
 */
public class WebSocketClientHandler<V> extends SimpleChannelInboundHandler<Object> {
	private static final Logger logger = LoggerFactory.getLogger(WebSocketClientHandler.class);

	private String id;
	private final WebSocketClientHandshaker handshaker;
	private ChannelPromise handshakeFuture;

	private BiConsumer<Channel, V> connected;
	private TriConsumer<Channel, WebSocketFrame, V> processor;
	private V data;

	public WebSocketClientHandler(String id, V data, WebSocketClientHandshaker handshaker,
			BiConsumer<Channel, V> connected, TriConsumer<Channel, WebSocketFrame, V> processor) {
		this.id = id;
		this.data = data;
		this.connected = connected;
		this.processor = processor;
		this.handshaker = handshaker;
	}

	public ChannelFuture handshakeFuture() {
		return handshakeFuture;
	}

	@Override
	public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
		handshakeFuture = ctx.newPromise();
	}

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		handshaker.handshake(ctx.channel());
	}

	@Override
	public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
		final Channel ch = ctx.channel();
		if (!handshaker.isHandshakeComplete()) {
			// web socket client connected
			handshaker.finishHandshake(ch, (FullHttpResponse) msg);
			handshakeFuture.setSuccess();

			if (connected != null)
				connected.accept(ch, data);
			return;
		}

		if (msg instanceof WebSocketFrame) {
			final WebSocketFrame frame = (WebSocketFrame) msg;
			if (frame instanceof TextWebSocketFrame) {
				processor.accept(ch, (TextWebSocketFrame) frame, data);
			} else if (frame instanceof BinaryWebSocketFrame) {
				processor.accept(ch, (BinaryWebSocketFrame) frame, data);
			} else if (frame instanceof PongWebSocketFrame) {
			} else if (frame instanceof CloseWebSocketFrame)
				ch.close();
		}
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		String msg = cause.getMessage();
		if (msg == null)
			msg = cause.toString();
		logger.error("[" + id + "] {}", msg);
		ctx.close();
	}
}
