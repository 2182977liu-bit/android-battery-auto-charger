package com.autophonez.batterymonitor;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 进程内环形日志，Activity 每秒刷新一次显示，方便在手机上直接排查。 */
public final class LogBuf {

    private static final int MAX = 40;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private LogBuf() {
    }

    public static synchronized void add(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LINES.addLast(t + "  " + msg);
        while (LINES.size() > MAX) {
            LINES.removeFirst();
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String last() {
        return LINES.isEmpty() ? "" : LINES.peekLast();
    }
}
