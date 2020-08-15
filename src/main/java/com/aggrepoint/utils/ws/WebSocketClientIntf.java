package com.aggrepoint.utils.ws;

public interface WebSocketClientIntf {
	public String getKey();

	/** 状态设置为被监管 */
	public void setMonitored();

	public void logStats();

	/** 返回false表示被主动关停，不需要继续监控 */
	boolean beMonitored();

	/** 返回true表示正在连接中 */
	boolean isConnecting();

	/** 返回true表示已经物理连接建立成功 */
	boolean isChannelConnected();

	/** 如果已经连接成功并且超过限时没有活动，断开连接，并返回true */
	boolean disconnectIfInactive();

	/** 获取未活动的时间 */
	long getInactiveTime();

	/** 如果已经连接成功并且重新连接的时间到了，就断开连接并返回true */
	boolean disconnectIfReconnect();

	/** 发送心跳的时间间隔 */
	Long getHeartBeatTime();

	/** 发送心跳 */
	void heartBeat();

	/** 建立连接 */
	boolean open(String localIp) throws Exception;
}
