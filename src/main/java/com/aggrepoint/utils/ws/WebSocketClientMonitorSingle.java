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
	List<WebSocketClientIntf> clients = new ArrayList<>();
	long nextPrintTime = System.currentTimeMillis() + REPORT_INTERVAL;

	public WebSocketClientMonitorSingle(String name, String bindIp, int connectInterval, int heartBeatInterval,
			int checkInterval) {
		this.name = name;
		this.bindIp = StringUtils.isEmpty(bindIp) ? null : bindIp;
		this.connectInterval = connectInterval;
		this.heartBeatInterval = heartBeatInterval;
		this.checkInterval = checkInterval;
	}

	void printStatus(List<WebSocketClientIntf> cls) {
		if (System.currentTimeMillis() > nextPrintTime) {
			nextPrintTime = System.currentTimeMillis() + REPORT_INTERVAL;
			if (cls.size() > 0) {
				for (WebSocketClientIntf client : cls)
					client.logStats();
			} else
				logger.info("{} 没有WebSocket连接", name);
		}
	}

	void startMonitor() {
		if (monitorThread == null) {
			monitorThread = new Thread(() -> {
				List<WebSocketClientIntf> cls = new ArrayList<>();

				while (Thread.currentThread() == monitorThread) {
					cls.clear();

					synchronized (clients) {
						for (Iterator<WebSocketClientIntf> it = clients.iterator(); it.hasNext();) {
							WebSocketClientIntf client = it.next();
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
					List<WebSocketClientIntf> toOpen = new ArrayList<>();
					for (WebSocketClientIntf client : cls) {
						if (client.isConnecting())
							continue;

						if (!client.isChannelConnected())
							toOpen.add(client);
						else if (client.disconnectIfInactive()) {
							logger.info(name + "[" + client.getKey() + "] 因为没有接收到数据" + client.getInactiveTime()
									+ "毫秒被停止，等待重启");
							toOpen.add(client);
						} else if (client.disconnectIfReconnect()) {
							logger.info(name + "[" + client.getKey() + "] 定时停止，等待重启");
							toOpen.add(client);
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
					for (WebSocketClientIntf client : toOpen) {
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
	public WebSocketClientMonitor startClient(WebSocketClientIntf client) {
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
