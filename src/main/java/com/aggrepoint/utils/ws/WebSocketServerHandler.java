package com.aggrepoint.utils.ws;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.aggrepoint.utils.TriConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;

/**
 * 受理HTTP请求，升级WS连接，受理WS数据包
 *
 * @param <T>
 */
public class WebSocketServerHandler<T> extends SimpleChannelInboundHandler<FullHttpRequest> {
	private static final Logger logger = LoggerFactory.getLogger(SimpleChannelInboundHandler.class);

	private String webSocketPath;
	BiConsumer<Channel, FullHttpRequest> httpHandler;
	BiFunction<Channel, String, T> wsConnected;
	TriConsumer<T, Channel, WebSocketFrame> frameReceived;
	Consumer<T> wsDisconnect;

	public WebSocketServerHandler(String webSocketPath, BiConsumer<Channel, FullHttpRequest> httpHandler,
			BiFunction<Channel, String, T> wsConnected, TriConsumer<T, Channel, WebSocketFrame> frameReceived,
			Consumer<T> wsDisconnect) {
		this.webSocketPath = webSocketPath;
		this.httpHandler = httpHandler;
		this.wsConnected = wsConnected;
		this.frameReceived = frameReceived;
		this.wsDisconnect = wsDisconnect;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof FullHttpRequest) {
			HttpHeaders headers = ((FullHttpRequest) msg).headers();
			if ("Upgrade".equalsIgnoreCase(headers.get("Connection"))
					|| "WebSocket".equalsIgnoreCase(headers.get("Upgrade"))) { // 升级为websocket连接
				// { 获取客户端ip
				String ip = headers.get("X-Forwarded-For");
				if (StringUtils.isEmpty(ip))
					ip = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
				String theIp = ip;
				// }

				ctx.pipeline().replace(this, "WebSocket", new WebSocketServerCompressionHandler())
						.addLast(new WebSocketServerProtocolHandler(webSocketPath, null, true))
						.addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {
							T svc;

							@Override
							protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame)
									throws Exception {
								if (svc == null)
									svc = wsConnected.apply(ctx.channel(), theIp);

								// ping and pong frames already handled

								frameReceived.accept(svc, ctx.channel(), frame);
							}

							@Override
							public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
								logger.info("handler removed.");
								// 终止handler
								if (svc != null)
									wsDisconnect.accept(svc);
							}

							@Override
							public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
								super.exceptionCaught(ctx, cause);
								logger.error("Error", cause);
							}
						});

				// 交给下一个handler处理
				ctx.fireChannelRead(msg);
				return;
			}
		}

		super.channelRead(ctx, msg);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
		// Handle a bad request.
		if (!req.decoderResult().isSuccess()) {
			WebSocketServer.sendHttpResponse(ctx.channel(), req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
			return;
		}

		httpHandler.accept(ctx.channel(), req);
	}
}
