package com.autophonez.batterymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启：仅在配置有效且用户勾选了「开机自启」时拉起服务。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Config cfg = Config.load(context);
        if (!cfg.bootStart || !cfg.isValid()) {
            return;
        }
        Intent svc = new Intent(context, BatteryMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            LogBuf.add("boot start failed: " + e);
        }
    }
}
