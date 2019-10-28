package com.aggrepoint.utils.ws;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * <pre>
 * 启动一个线程，检查一组WebSocketClient的运行状态，如果有WebSocketClient被断开了，负责重新建立
 * 连接，如果WebSocketClient连接正常，则触发心跳方法。
 * 
 * 在建立连接的时候，可以在连接之间保持一定时间间隔，避免一瞬间建立大量的连接
 * 
 * 如果连接被主动关停，将连接移出管理列表
 * 
 * WebSocketClient可以脱离WebSocketClientMonitor来运行。
 * </pre>
 */
public class WebSocketClientMonitorSingle implements WebSocketClientMonitor {
	private static final Logger logger = LoggerFactory.getLogger(WebSocketClientMonitorSingle.class);

	// 每分钟打印一次连接状态
	static final long REPORT_INTERVAL = 60 * 1000;

	String name;
	/** 要绑定的本地IP */
	String bindIp;
	/** 建立连接时间间隔 */
	int connectInterval;
	/** 发送心跳时间间隔 */
	int heartBeatInterval;
	/** 每轮检查之间的时间间隔 */
	int checkInterval;

	Thread monitorThread;
	List<WebSocketClient<?, ?>> clients = new ArrayList<>();
	long nextPrintTime = System.currentTimeMillis() + REPORT_INTERVAL;

	public WebSocketClientMonitorSingle(String name, String bindIp, int connectInterval, int heartBeatInterval,
			int checkInterval) {
		this.name = name;
		this.bindIp = StringUtils.isEmpty(bindIp) ? null : bindIp;
		this.connectInterval = connectInterval;
		this.heartBeatInterval = heartBeatInterval;
		this.checkInterval = checkInterval;
	}

	void printStatus(List<WebSocketClient<?, ?>> cls) {
		if (System.currentTimeMillis() > nextPrintTime) {
			nextPrintTime = System.currentTimeMillis() + REPORT_INTERVAL;
			if (cls.size() > 0) {
				StringBuffer sb = new StringBuffer();
				for (WebSocketClient<?, ?> client : cls)
					sb.append("\r\n").append("[").append(client.getKey()).append("] Channel连接：")
							.append(client.isChannelConnected()).append(" WS连接：").append(client.isConnected())
							.append(" 活跃时间：").append(client.getInactiveTime());
				logger.info("{}连接状态: {}", name, sb.toString());
			} else
				logger.info("{} 没有WebSocket连接", name);
		}
	}

	void startMonitor() {
		if (monitorThread == null) {
			monitorThread = new Thread(() -> {
				List<WebSocketClient<?, ?>> cls = new ArrayList<>();

				while (Thread.currentThread() == monitorThread) {
					cls.clear();

					synchronized (clients) {
						for (Iterator<WebSocketClient<?, ?>> it = clients.iterator(); it.hasNext();) {
							WebSocketClient<?, ?> client = it.next();
							if (!client.beMonitored()) {
								logger.info(name + "[" + client.getKey() + "] 被主动关停，不再监控");
								it.remove();
								clients.notify();
								continue;
							}
							cls.add(client);
						}
					}

					// 所有需要重连的client
					List<WebSocketClient<?, ?>> toOpen = new ArrayList<>();
					for (WebSocketClient<?, ?> client : cls) {
						if (client.isConnecting())
							continue;

						if (!client.isChannelConnected())
							toOpen.add(client);
						else if (client.stopIfInactive()) {
							logger.info(
									name + "[" + client.getKey() + "] 因为没有接收到数据" + client.getInactiveTime() + "毫秒被关闭");
						} else {
							Long heartBeatTime = client.getHeartBeatTime();
							if (heartBeatTime != null && heartBeatTime + heartBeatInterval < System.currentTimeMillis())
								try {
									client.heartBeat();
								} catch (Exception e) {
									logger.error(name + "[" + client.getKey() + "] 发送心跳失败", e);
								}
						}
					}

					printStatus(cls);

					// 重连
					boolean first = true;
					for (WebSocketClient<?, ?> client : toOpen) {
						try {
							if (!first && connectInterval > 0) // 连接时间间隔
								TimeUnit.MILLISECONDS.sleep(connectInterval);

							client.open(bindIp);
							first = false;
						} catch (Exception e) {
							logger.error(name + "[" + client.getKey() + "] 无法建立连接", e);
						}

						printStatus(cls);
					}

					try {
						Thread.sleep(checkInterval);
					} catch (Exception e) {
					}
				}
			});
			monitorThread.start();
		}
	}

	@Override
	public WebSocketClientMonitor startClient(WebSocketClient<?, ?> client) {
		startMonitor();
		client.setMonitored();
		if (!clients.contains(client))
			synchronized (clients) {
				clients.add(client);
				logger.info(name + "[" + client.getKey() + "] 开始监控");
			}
		return this;
	}

	public int getClientCount() {
		synchronized (clients) {
			return clients.size();
		}
	}

	/**
	 * 等待所有client被终止运行
	 */
	public void sync() {
		while (true)
			synchronized (clients) {
				if (clients.size() == 0)
					return;
				try {
					clients.wait();
				} catch (InterruptedException e) {
				}
			}
	}
}
