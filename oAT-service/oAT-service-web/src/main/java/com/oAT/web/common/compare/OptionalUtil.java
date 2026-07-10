package com.oAT.web.common.compare;

public class OptionalUtil {
    public static <T>T orElse(T value,T elseValue){return value!=null ? value : elseValue;}
}
