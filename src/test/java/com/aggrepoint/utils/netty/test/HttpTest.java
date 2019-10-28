package com.aggrepoint.utils.netty.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aggrepoint.utils.http.HttpChannel;
import com.aggrepoint.utils.http.HttpRepeat;
import com.aggrepoint.utils.http.HttpTask;
import com.aggrepoint.utils.http.HttpThrottling;
import com.aggrepoint.utils.http.ResponseFuture;

public class HttpTest {
	private static final Logger logger = LoggerFactory.getLogger(HttpTest.class);
	ExecutorService executor = Executors.newFixedThreadPool(10);
	HttpChannel channel = new HttpChannel(executor, null, 1000, 1000, 1000, true, null, null);

	private void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
		}
	}

	@Test
	public void testClientChannel() {
		channel.request(new HttpTask("https://www.google.com", (content, exp) -> {
			if (exp != null)
				logger.error(exp.toString());
			else
				logger.info(content);
		})).sync();

		// 等待打印完成
		sleep(1000);
	}

	@Test
	public void testThrottling() {
		// 限速5秒执行一个，队列容量为3
		HttpThrottling trunner = new HttpThrottling(new HttpChannel[] { channel }, 3000, 3);

		List<ResponseFuture> list = new ArrayList<>();
		Runnable submit = () -> {
			ResponseFuture tf = trunner.request(new HttpTask("https://www.google.com", (content, exp) -> {
				if (exp != null)
					logger.error("异常：{}", exp.toString());
				else
					logger.info(content);
			}));

			if (tf != null)
				list.add(tf);
		};

		// 提交任务
		for (int i = 0; i < 5; i++)
			submit.run();

		// 等待所有限速任务执行完毕
		list.get(list.size() - 1).sync();

		// 再提交一批任务
		for (int i = 0; i < 5; i++)
			submit.run();

		// 等待所有限速任务执行完毕
		list.get(list.size() - 1).sync();

		// 等待打印完成
		sleep(1000);
	}

	@Test
	public void testRepeat() {
		HttpRepeat repeat = new HttpRepeat(channel, 5000);
		repeat.addTask(new HttpTask("https://www.google.com", (content, exp) -> {
			if (exp != null)
				logger.error(exp.toString());
			else
				logger.info(content);
		}));
		repeat.addTask(new HttpTask("https://www.baidu.com", (content, exp) -> {
			if (exp != null)
				logger.error(exp.toString());
			else
				logger.info(content);
		}));

		sleep(120 * 1000);
	}

	@Test
	public void testRepeatOnThrottling() {
		HttpThrottling trunner = new HttpThrottling(new HttpChannel[] { channel }, 2000, 3);
		HttpRepeat repeat = new HttpRepeat(trunner, 5000);
		repeat.addTask(new HttpTask("https://www.google.com", (content, exp) -> {
			if (exp != null)
				logger.error(exp.toString());
			else
				logger.info(content);
		}));
		repeat.addTask(new HttpTask("https://www.baidu.com", (content, exp) -> {
			if (exp != null)
				logger.error(exp.toString());
			else
				logger.info(content);
		}));

		sleep(120 * 1000);
	}
}
