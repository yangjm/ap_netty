package com.aggrepoint.utils.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aggrepoint.utils.StringUtils;

/**
 * <pre>
 * 一个HttpChannel代表一个指定了以下全部或部分特性的Http请求异步发送通道
 * 
 * 		- 绑定IP - 绑定代理 - 超时时间
 * </pre>
 * 
 * @author jiangmingyang
 */
public class HttpChannel implements HttpRequester {
	private static final Logger logger = LoggerFactory.getLogger(HttpChannel.class);

	Executor exec;
	AsyncHttpClient client;
	String bindIp;

	/**
	 * @param exec           用于执行处理HTTP响应的逻辑。如果HttpTask中带有Executor，则用HttpTask中的Executor
	 * @param bindIp         绑定本地IP地址
	 * @param connectTimeout 连接超时
	 * @param requestTimeout 请求超时
	 * @param readTimeout    读超时
	 * @param followRedirect
	 * @param proxyIp        代理服务器IP
	 * @param proxyPort      代理服务器端口
	 */
	public HttpChannel(Executor exec, String bindIp, Integer connectTimeout, Integer requestTimeout,
			Integer readTimeout, boolean followRedirect, String proxyIp, Integer proxyPort) {
		this.exec = exec;
		this.bindIp = StringUtils.isEmpty(bindIp) ? null : bindIp;

		DefaultAsyncHttpClientConfig.Builder clientBuilder = Dsl.config();
		if (connectTimeout != null)
			clientBuilder.setConnectTimeout(connectTimeout);
		if (requestTimeout != null)
			clientBuilder.setRequestTimeout(requestTimeout);
		if (readTimeout != null)
			clientBuilder.setReadTimeout(readTimeout);
		clientBuilder.setFollowRedirect(followRedirect);
		if (!StringUtils.isEmpty(proxyIp) && proxyPort != null)
			clientBuilder.setProxyServer(Dsl.proxyServer(proxyIp, proxyPort));
		client = Dsl.asyncHttpClient(clientBuilder);
	}

	/**
	 * 发起请求，在exec中执行请求结果。返回的ListenableFuture可以用于等待请求完成
	 */
	@Override
	public ResponseFuture request(HttpTask task) {
		Request request = null;
		try {
			request = task.getRequest();
		} catch (Exception e) {
			logger.error("获取请求对象异常", e);
			return null;
		}

		if (bindIp != null && bindIp.equals(request.getLocalAddress().getHostAddress())) {
			try {
				request = new RequestBuilder(request).setLocalAddress(InetAddress.getByName(bindIp)).build();
			} catch (UnknownHostException e) {
				logger.error(bindIp + "不是合法的绑定地址");
			}
		}

		ListenableFuture<Response> future = client.executeRequest(request);

		future.addListener(() -> {
			try {
				if (task.getProcess() != null)
					task.getProcess().accept(future.get().getResponseBody(), null);
			} catch (InterruptedException | ExecutionException e) {
				task.getProcess().accept(null, e);
			}
		}, task.getExecutor() == null ? exec : task.getExecutor());

		return new ResponseFuture(future);
	}
}
