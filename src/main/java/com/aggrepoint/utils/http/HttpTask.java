package com.aggrepoint.utils.http;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.util.HttpConstants;

import com.aggrepoint.utils.SupplierX;

public class HttpTask {
	private Executor exec;
	private SupplierX<Request> requestSupplier;
	private Request request;
	private BiConsumer<String, Exception> process;
	private Object data;

	/**
	 * @param url         仅用于日志打印，不用于请求
	 * @param request     实际用于请求
	 * @param process
	 * @param longTimeout 是否使用超时时间较长的通道
	 */
	public HttpTask(Request request, BiConsumer<String, Exception> process) {
		this.request = request;
		this.process = process;
	}

	public HttpTask(SupplierX<Request> requestSupplier, BiConsumer<String, Exception> process) {
		this.requestSupplier = requestSupplier;
		this.process = process;
	}

	public HttpTask(String url, BiConsumer<String, Exception> process) {
		this.request = new RequestBuilder(HttpConstants.Methods.GET).setUrl(url).build();
		this.process = process;
	}

	public HttpTask(String url) {
		this(url, null);
	}

	public Request getRequest() throws Exception {
		if (requestSupplier != null)
			return requestSupplier.get();
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
