package com.aggrepoint.utils.ws;

import java.util.ArrayList;
import java.util.List;

/**
 * 支持多个本地IP地址，每个IP地址建立一个WebSocketClientMonitor对象。将要启动和维持的
 * WebSocketClient平均分批给各个IP地址对应的WebSocketClientMonitor。
 * 
 * 如果只有一个IP地址，等同于直接使用WebSocketClientMonitorSingle。
 */
public class WebSocketClientMonitorGroup implements WebSocketClientMonitor {
	List<String> ips;
	List<WebSocketClientMonitorSingle> monitors = new ArrayList<>();

	public WebSocketClientMonitorGroup(String name, List<String> ips, int connectInterval, int heartBeatInterval,
			int loopInterval) {
		if (ips == null || ips.size() == 0)
			monitors.add(
					new WebSocketClientMonitorSingle(name, null, connectInterval, heartBeatInterval, loopInterval));
		else {
			this.ips = ips;
			for (String ip : ips)
				monitors.add(new WebSocketClientMonitorSingle(name + "@" + ip, ip, connectInterval, heartBeatInterval,
						loopInterval));
		}
	}

	@Override
	public WebSocketClientMonitor startClient(WebSocketClientIntf client) {
		if (ips == null)
			monitors.get(0).startClient(client);
		else {
			WebSocketClientMonitorSingle monitor = monitors.get(0);
			for (int i = 1; i < monitors.size(); i++) {
				WebSocketClientMonitorSingle m = monitors.get(i);
				if (m.getClientCount() < monitor.getClientCount())
					monitor = m;
			}
			monitor.startClient(client);
		}

		return this;
	}
}
