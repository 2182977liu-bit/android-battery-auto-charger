package com.autophonez.batterymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** 接收 PackageInstaller 的安装结果，写进界面日志。 */
public class InstallReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.autophonez.batterymonitor.INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // 系统要求用户确认：把确认界面拉起来
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Exception e) {
                        LogBuf.add("拉起安装确认失败：" + e.getMessage());
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                LogBuf.add("安装完成，重启应用后生效");
                break;
            default:
                LogBuf.add("安装失败 status=" + status + " " + (msg == null ? "" : msg));
                break;
        }
    }
}
