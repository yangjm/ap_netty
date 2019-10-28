package com.aggrepoint.utils.http;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;

/**
 * 用于实现延迟获得ListenableFuture<Response>
 * 
 * 对于HttpThrottling，request()执行完成时请求还未被安排运行，还未获得ListenableFuture<Response>
 * 
 * @author jiangmingyang
 */
public class ResponseFuture implements Future<Response> {
	Callable<Boolean> cancel;

	boolean cancelled = false;
	ListenableFuture<Response> future;

	public ResponseFuture(Callable<Boolean> cancel) {
		this.cancel = cancel;
	}

	/**
	 * 直接封装ListenableFuture
	 */
	public ResponseFuture(ListenableFuture<Response> future) {
		this.future = future;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if (future != null)
			return future.cancel(mayInterruptIfRunning);

		if (cancelled)
			return true;

		try {
			if (cancel != null)
				cancelled = cancel.call();
		} catch (Exception e) {
		}

		return cancelled;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public boolean isDone() {
		return future == null ? false : future.isDone();
	}

	public ListenableFuture<Response> getListenableFuture() {
		if (future == null) { // 等待future
			synchronized (this) {
				try {
					this.wait();
				} catch (InterruptedException e) {
				}
			}
		}

		if (future == null)
			return null;

		return future;
	}

	@Override
	public Response get() throws InterruptedException, ExecutionException {
		return getListenableFuture().get();
	}

	@Override
	public Response get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		long msTimeout = unit.toMillis(timeout);
		if (future == null) { // 等待future
			long start = System.currentTimeMillis();
			synchronized (this) {
				try {
					TimeUnit.MILLISECONDS.timedWait(this, msTimeout);
				} catch (InterruptedException e) {
				}
			}
			msTimeout -= (System.currentTimeMillis() - start);
			if (msTimeout < 0)
				msTimeout = 0;
		}

		if (future == null)
			return null;

		return future.get(msTimeout, TimeUnit.MILLISECONDS);
	}

	public void setResponseFuture(ListenableFuture<Response> future) {
		this.future = future;
		synchronized (this) {
			this.notifyAll();
		}
	}

	public Response sync() {
		try {
			return get();
		} catch (Exception e) {
		}
		return null;
	}

	public static void sync(List<ResponseFuture> list) {
		if (list == null || list.size() == 0)
			return;
		for (ResponseFuture lf : list)
			try {
				lf.get();
			} catch (Exception e) {
				// 这个异常应该已经在request()代码中交给task.process处理了
			}
	}

	public static void sync(ResponseFuture... lf) {
		if (lf == null || lf.length == 0)
			return;
		sync(Arrays.asList(lf));
	}
}
