package com.aggrepoint.utils.http;

import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * 基于HttpThrottling或HttpChannel不断重复执行请求任务
 * 
 * 有任务执行时，HttpTaskRepeat内建立一个用于重复执行任务的线程。如果要执行的任务被清空，线程会被清除
 * 
 * HttpTaskRepeat会一次性将所有要重复执行的任务提交执行，然后等待执行结果。因此HttpThrottling的队列容量需要大于
 * 任务总数个数
 * </pre>
 * 
 * @author jiangmingyang
 */
public class HttpRepeat {
	HttpRequester runner;
	/** 需要反复循环执行的任务 */
	private List<HttpTask> repeatTask;
	/** 执行反复循环任务的时间间隔 */
	private long repeatInterval;
	/** 负责执行循环任务的线程 */
	private Thread repeateTaskThread;

	public HttpRepeat(HttpRequester runner, long interval) {
		this.runner = runner;
		this.repeatInterval = interval;
	}

	/**
	 * 重设要反复执行的任务，返回之前的任务列表
	 */
	public List<HttpTask> setTasks(List<HttpTask> tasks) {
		List<HttpTask> current = repeatTask;
		repeatTask = tasks;

		if (repeatTask == null) // 清空要执行的任务，停止
			repeateTaskThread = null;
		else if (repeateTaskThread == null) {
			repeateTaskThread = new Thread(() -> {
				while (Thread.currentThread() == repeateTaskThread) {
					long time = System.currentTimeMillis();
					List<ResponseFuture> list = null;
					synchronized (repeatTask) {
						try {
							list = runner.request(repeatTask);
						} catch (Exception e) {
						}
					}

					// 阻塞至所有任务执行完毕
					ResponseFuture.sync(list);

					// { 等待下次运行
					long wait = repeatInterval - (System.currentTimeMillis() - time);
					if (wait > 0)
						try {
							Thread.sleep(wait);
						} catch (Exception e) {
						}
					// }
				}
			});
			repeateTaskThread.start();
		}

		return current;
	}

	/**
	 * 增加一个repeat task
	 * 
	 * @param task
	 */
	public void addTask(HttpTask task) {
		if (repeatTask == null) {
			List<HttpTask> list = new ArrayList<>();
			list.add(task);
			setTasks(list);
			return;
		}

		synchronized (repeatTask) {
			repeatTask.add(task);
		}
	}

	public void removeTask(HttpTask task) {
		synchronized (repeatTask) {
			repeatTask.remove(task);
		}
	}
}
