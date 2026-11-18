package com.carwireless.toolbox.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 反射调用统一入口：把各种系统异常翻译成中文原因，方便界面直接展示。 */
public final class Reflect {

    private Reflect() { }

    public static Method find(Class<?> owner, String name, Class<?>... types) {
        try {
            Method method = owner.getMethod(name, types);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调用并返回结果；任何失败都抛异常，由调用方翻译。 */
    public static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = find(target.getClass(), name, types);
        if (method == null) throw new NoSuchMethodException(name);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }

    public static String describe(Throwable error) {
        if (error == null) return "未知错误";
        if (error instanceof NoSuchMethodException) return "本机 ROM 未提供此接口";
        if (error instanceof SecurityException) return "系统拒绝访问（权限不足）";
        if (error instanceof IllegalAccessException) return "接口不可访问";
        if (error instanceof UnsupportedOperationException) return "本机不支持此操作";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.length() == 0 ? "" : "：" + message);
    }
}
