package com.oAT.web.common;

import java.util.Date;

public class DateUtil {

    // 计算距离当前时间
    public static String timeDifference(Date begin, Date end) {
        long time = end.getTime() - begin.getTime();
        time = time / (1000 * 60);// 将差距换算成分钟
        long day = time / (60 * 24); // 1 天
        time = time % (60 * 24);
        long hour = time / 60; // 小时
        long minute = time % 60;// 分钟
        StringBuilder result = new StringBuilder();
        if (day > 0) {
            result.append(day);
            result.append("天");
        } else if (hour > 0) {
            result.append(hour);
            result.append("小时");
        } else {
            minute = Math.max(minute, 1);
            result.append(minute);
            result.append("分钟");
        }
        return result.toString();
    }

    public static String timeDifference(Date begin){
        return timeDifference(begin,new Date());
    }

}
