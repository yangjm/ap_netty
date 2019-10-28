package com.aggrepoint.utils.http;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.util.HttpConstants;

public class HttpTask {
	private Executor exec;
	private String url;
	private Request request;
	private BiConsumer<String, Exception> process;
	private Object data;

	/**
	 * @param url
	 *            仅用于日志打印，不用于请求
	 * @param request
	 *            实际用于请求
	 * @param process
	 * @param longTimeout
	 *            是否使用超时时间较长的通道
	 */
	public HttpTask(String url, Request request, BiConsumer<String, Exception> process) {
		this.url = url;
		this.request = request;
		this.process = process;
	}

	public HttpTask(String url, BiConsumer<String, Exception> process) {
		this.url = url;
		this.request = new RequestBuilder(HttpConstants.Methods.GET).setUrl(url).build();
		this.process = process;
	}

	public HttpTask(String url) {
		this(url, null);
	}

	public String getUrl() {
		return url;
	}

	public Request getRequest() {
		return request;
	}

	public BiConsumer<String, Exception> getProcess() {
		return process;
	}

	public HttpTask setProcess(BiConsumer<String, Exception> process) {
		this.process = process;
		return this;
	}

	public Object getData() {
		return data;
	}

	public void setData(Object data) {
		this.data = data;
	}

	public Executor getExecutor() {
		return exec;
	}

	public void setExecutor(Executor exec) {
		this.exec = exec;
	}
}
