package com.aggrepoint.utils.ws;

import java.text.SimpleDateFormat;
import java.util.Date;

public class DateFormat {
	public static SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public static String format(Date dt) {
		if (dt == null)
			return "";
		return SDF.format(dt);
	}

	public static String format(long time) {
		if (time == 0)
			return "";
		return format(new Date(time));
	}
}
