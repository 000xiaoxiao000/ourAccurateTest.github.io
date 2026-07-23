package com.oAT.web.persistence.entity;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public interface StandardDate {

    String dateFormat = "yyyy-MM-dd HH:mm:ss,SSS";

    default  String currentTimeToString() {
        return new SimpleDateFormat(dateFormat).format(new Date());
    }

    default Date parse(String standardTime) {
        if (standardTime == null) {
            return null;
        }
        String value = standardTime.trim();
        if (value.matches("^-?\\d+$")) {
            long timestamp = Long.parseLong(value);
            if (value.length() == 10) {
                timestamp *= 1000;
            }
            return new Date(timestamp);
        }
        try {
            return new SimpleDateFormat(dateFormat).parse(value);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

}
