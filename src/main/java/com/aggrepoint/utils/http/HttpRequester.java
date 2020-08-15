package com.aggrepoint.utils.http;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * 执行HTTP请求
 * 
 * @author jiangmingyang
 */
public interface HttpRequester {
	public ResponseFuture request(HttpTask task);

	default public ResponseFuture request(String url, BiConsumer<String, Exception> process) {
		return request(new HttpTask(url, process));
	}

	default public ResponseFuture request(String url, BiConsumer<String, Exception> process, Executor exec) {
		HttpTask task = new HttpTask(url, process);
		task.setExecutor(exec);
		return request(task);
	}

	default public List<ResponseFuture> request(List<HttpTask> tasks) {
		List<ResponseFuture> list = new ArrayList<>();
		if (tasks == null || tasks.size() == 0)
			return list;

		for (HttpTask task : tasks)
			list.add(request(task));

		return list;
	}
}
