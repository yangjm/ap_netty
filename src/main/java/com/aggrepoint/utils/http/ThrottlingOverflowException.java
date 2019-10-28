package com.aggrepoint.utils.http;

/**
 * 用于限速请求：等待队列已满，无法加入等待
 */
public class ThrottlingOverflowException extends Exception {
	private static final long serialVersionUID = 1L;
}
