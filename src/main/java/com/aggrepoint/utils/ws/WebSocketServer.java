package com.aggrepoint.utils.ws;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.aggrepoint.utils.TriConsumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;

public class WebSocketServer {
	private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);

	/**
	 * 启动运行WebSocketServer
	 * 
	 * @param certChain
	 *            SSL证书所在路径
	 * @param privateKey
	 *            SSL证书私钥所在路径
	 * @param port
	 *            监听端口
	 * @param webSocketPath
	 *            websocket请求路径
	 * @param httpHandler
	 *            处理HTTP请求
	 * @param wsConnected
	 *            WS连接建立成功回调。返回的对象会被传递给frameReceived和wsDisconnect
	 * @param frameReceived
	 *            接收到WS帧
	 * @param wsDisconnect
	 *            WS断开连接
	 * @return 返回的Runable对象，如果被执行，会停止服务器
	 * @throws SSLException
	 * @throws FileNotFoundException
	 */
	public static <T> Runnable start(String certChain, String privateKey, int port, String webSocketPath,
			BiConsumer<Channel, FullHttpRequest> httpHandler, BiFunction<Channel, String, T> wsConnected,
			TriConsumer<T, Channel, WebSocketFrame> frameReceived, Consumer<T> wsDisconnect)
			throws SSLException, FileNotFoundException {
		SslContext sslCtx;
		if (!StringUtils.isEmpty(certChain) && !StringUtils.isEmpty(privateKey)) {
			// 指定了证书路径，启用SSL
			sslCtx = SslContextBuilder.forServer(new FileInputStream(certChain), new FileInputStream(privateKey))
					.build();
		} else {
			sslCtx = null;
		}

		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workerGroup = new NioEventLoopGroup();

		Runnable stop = () -> {
			if (bossGroup != null)
				try {
					bossGroup.shutdownGracefully();
				} catch (Exception e) {
				}
			if (workerGroup != null)
				try {
					workerGroup.shutdownGracefully();
				} catch (Exception e) {
				}
		};

		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
					.handler(new LoggingHandler(LogLevel.INFO)).childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch) throws Exception {
							ChannelPipeline pipeline = ch.pipeline();
							if (sslCtx != null)
								pipeline.addLast(sslCtx.newHandler(ch.alloc()));
							pipeline.addLast(new HttpServerCodec()).addLast(new HttpObjectAggregator(65536))
									.addLast(new WebSocketServerHandler<T>(webSocketPath, httpHandler, wsConnected,
											frameReceived, wsDisconnect));
						}
					});

			Channel ch = b.bind(port).sync().channel();

			ch.closeFuture().addListener(new ChannelFutureListener() {
				public void operationComplete(ChannelFuture future) {
					stop.run();
				}
			});
		} catch (Exception e) {
			logger.error("Error starting", e);
			stop.run();
		}

		return stop;
	}

	/**
	 * 发送HTTP响应
	 * 
	 * @param ctx
	 * @param req
	 * @param res
	 */
	public static void sendHttpResponse(Channel channel, FullHttpRequest req, FullHttpResponse res) {
		// Generate an error page if response getStatus code is not OK (200).
		if (res.status().code() != 200) {
			ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
			res.content().writeBytes(buf);
			buf.release();
			HttpUtil.setContentLength(res, res.content().readableBytes());
		}

		// Send the response and close the connection if necessary.
		ChannelFuture f = channel.writeAndFlush(res);
		if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
			f.addListener(ChannelFutureListener.CLOSE);
		}
	}

	/**
	 * 将BinaryWebSocketFrame转换为byte数组
	 * 
	 * @param bframe
	 * @return
	 */
	public byte[] toBytes(BinaryWebSocketFrame bframe) {
		if (!bframe.isFinalFragment())
			throw new UnsupportedOperationException("Multiple fragments not supported!");

		byte[] bytes = new byte[bframe.content().readableBytes()];
		bframe.content().readBytes(bytes);
		return bytes;
	}
}
