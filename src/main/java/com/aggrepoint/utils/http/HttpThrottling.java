package com.aggrepoint.utils.http;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * 实现Http请求速度控制
 * 
 * 使用一组HttpChannel尽快发送Http请求，每个channel上的请求之间保持一定时间间隔
 * 
 * 每个HttpThrottling内建立一个用于延时执行任务的线程
 * </pre>
 * 
 * @author jiangmingyang
 */
public class HttpThrottling implements HttpRequester {
	/** 等待运行的请求队列 */
	private ArrayBlockingQueue<Waiting> waitingQueue;

	private static class Waiting {
		HttpTask task;
		ResponseFuture future;

		public Waiting(HttpTask task) {
			this.task = task;
		}
	}

	public HttpThrottling(HttpChannel[] channels, int requestInterval, int maxWaiting) {
		long[] lastRequestTime = new long[channels.length];

		waitingQueue = new ArrayBlockingQueue<>(maxWaiting);

		// 请求线程
		new Thread(() -> {
			while (true) {
				Waiting waiting = null;
				try {
					waiting = waitingQueue.poll(60, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
				}

				if (waiting == null) {
					continue;
				}

				boolean run = false;

				do {
					long waitTime = requestInterval;

					// 寻找可用的channel
					for (int i = 0; i < channels.length; i++) {
						if (requestInterval > 0) {
							long w = requestInterval - (System.currentTimeMillis() - lastRequestTime[i]);

							if (w > 0) {
								if (w < waitTime)
									waitTime = w;
								continue;
							}
						}

						// 找到可用的channel
						lastRequestTime[i] = System.currentTimeMillis();
						waiting.future.setResponseFuture(channels[i].request(waiting.task).getListenableFuture());
						run = true;
						break;
					}

					if (!run) { // 没有可用channel, 等待
						try {
							TimeUnit.MICROSECONDS.sleep(waitTime);
						} catch (InterruptedException e) {
						}
					}
				} while (!run);
			}
		}, HttpThrottling.class.getName()).start();
	}

	/**
	 * 增加一个任务
	 * 
	 * @param task
	 * @return 返回NULL说明等待的请求已经超过限量
	 * @throws InterruptedException
	 */
	@Override
	public ResponseFuture request(HttpTask task) {
		if (waitingQueue.remainingCapacity() == 0) {
			if (task.getExecutor() == null)
				task.getProcess().accept(null, new ThrottlingOverflowException());
			else
				task.getExecutor().execute(() -> {
					task.getProcess().accept(null, new ThrottlingOverflowException());
				});
			return null;
		}

		Waiting waiting = new Waiting(task);
		waiting.future = new ResponseFuture(() -> waitingQueue.remove(waiting));
		try {
			waitingQueue.put(waiting);
		} catch (InterruptedException e) {
			if (task.getExecutor() == null)
				task.getProcess().accept(null, e);
			else
				task.getExecutor().execute(() -> {
					task.getProcess().accept(null, e);
				});
			return null;
		}
		return waiting.future;
	}

	/**
	 * 从队列中撤销还未执行到的请求
	 * 
	 * @param waiting
	 * @return
	 */
	public boolean cancel(Waiting waiting) {
		return waitingQueue.remove(waiting);
	}
}
