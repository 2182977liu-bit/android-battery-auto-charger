package com.autophonez.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 充电管理台：展示多台上报设备，给每台设备下发「低电量阈值 / 高电量阈值 / 继电器通道」。
 *
 * 数据来自 GET /devices，每 3 秒轮询一次；配置用 POST /devices/config 下发。
 * 服务端可以是 ESP32，也可以是 tools/web_dashboard.py（两者协议一致，方便先用电脑联调）。
 */
public class MainActivity extends Activity {

    private static final long POLL_MS = 3000L;
    private static final String PREF = "manager";

    private EditText etHost;
    private EditText etPort;
    private EditText etToken;
    private TextView tvStatus;
    private TextView tvEmpty;
    private LinearLayout deviceBox;

    private SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Device> devices = new ArrayList<>();

    private String baseUrl = "";
    private String token = "";
    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            pollOnce();
            main.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etToken = findViewById(R.id.etToken);
        tvStatus = findViewById(R.id.tvStatus);
        tvEmpty = findViewById(R.id.tvEmpty);
        deviceBox = findViewById(R.id.deviceBox);

        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        etHost.setText(sp.getString("host", ""));
        etPort.setText(String.valueOf(sp.getInt("port", 8080)));
        etToken.setText(sp.getString("token", ""));

        findViewById(R.id.btnConnect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndConnect();
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseUrl.isEmpty()) {
                    saveAndConnect();
                } else {
                    pollOnce();
                }
            }
        });

        // 有已保存的配置就直接开始轮询
        if (!etHost.getText().toString().trim().isEmpty()) {
            applyConnection();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        main.removeCallbacks(pollRunnable);
        if (!baseUrl.isEmpty()) {
            main.postDelayed(pollRunnable, POLL_MS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        main.removeCallbacks(pollRunnable);
    }

    // ---------------------------------------------------------- 连接

    private void saveAndConnect() {
        String host = etHost.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "先填服务端 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            port = 8080;
        }
        sp.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("token", etToken.getText().toString().trim())
                .apply();
        applyConnection();
        pollOnce();
    }

    private void applyConnection() {
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            port = 8080;
        }
        baseUrl = DeviceApi.baseUrl(etHost.getText().toString().trim(), port);
        token = etToken.getText().toString().trim();
        setStatus("已配置 " + baseUrl + "，正在连接…");
    }

    // ---------------------------------------------------------- 轮询

    private void pollOnce() {
        final String b = baseUrl;
        final String t = token;
        if (b.isEmpty()) {
            setStatus("未配置服务端");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Device> list = DeviceApi.list(b, t);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            devices.clear();
                            devices.addAll(list);
                            render();
                            setStatus("已连接 · " + list.size() + " 台设备 · " + nowText());
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("连接失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private String nowText() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.getDefault());
        return f.format(new java.util.Date());
    }

    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    // ---------------------------------------------------------- 渲染

    private void render() {
        deviceBox.removeAllViews();
        tvEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        for (Device d : devices) {
            deviceBox.addView(buildCard(d));
        }
    }

    private View buildCard(final Device d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = dp(14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        // 第一行：设备 ID + 机型，右侧电量
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text(d.id + " · " + d.model, 15, R.color.text_main);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row1.addView(title, tlp);

        int levelColor;
        if (d.level >= 0 && d.level <= d.lowTh) {
            levelColor = R.color.warn;
        } else if (d.level >= d.highTh) {
            levelColor = R.color.ok;
        } else {
            levelColor = R.color.primary;
        }
        String levelText = d.level < 0 ? "--%" : d.level + "%";
        TextView tvLevel = text(levelText, 18, levelColor);
        tvLevel.setTypeface(null, android.graphics.Typeface.BOLD);
        row1.addView(tvLevel);
        card.addView(row1);

        // 电量条
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(Math.max(0, d.level));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        blp.topMargin = dp(8);
        blp.bottomMargin = dp(8);
        card.addView(bar, blp);

        // 第二行：充电状态 + 阈值 + 通道
        String line2 = (d.charging ? "充电中" : "未充电")
                + " · 阈值 " + d.lowTh + "/" + d.highTh
                + " · " + d.relayText();
        TextView tv2 = text(line2, 12, R.color.text_sub);
        card.addView(tv2);

        // 第三行：最后上报 + 报文数 + 来源 IP + 在线状态
        String line3 = "最后上报 " + d.ageText()
                + " · " + (d.online ? "在线" : "离线")
                + " · 报文 " + d.count
                + " · " + d.lastIp;
        TextView tv3 = text(line3, 11, R.color.text_sub);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l3.topMargin = dp(4);
        card.addView(tv3, l3);

        // 设置按钮
        Button btn = new Button(this);
        btn.setText("设置阈值 / 通道");
        btn.setTextSize(13);
        btn.setTextColor(getColor(R.color.text_main));
        btn.setBackgroundResource(R.drawable.btn_secondary);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        btnLp.topMargin = dp(10);
        card.addView(btn, btnLp);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigDialog(d);
            }
        });

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigDialog(d);
            }
        });

        return card;
    }

    // ---------------------------------------------------------- 配置对话框

    private void showConfigDialog(final Device d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, dp(8), p, 0);

        box.addView(text("低电量阈值（≤ 此值开始充电）", 12, R.color.text_sub));
        final EditText etLow = new EditText(this);
        etLow.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etLow.setText(String.valueOf(d.lowTh));
        etLow.setTextSize(15);
        box.addView(etLow);

        box.addView(text("高电量阈值（≥ 此值断开充电）", 12, R.color.text_sub));
        final EditText etHigh = new EditText(this);
        etHigh.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etHigh.setText(String.valueOf(d.highTh));
        etHigh.setTextSize(15);
        box.addView(etHigh);

        box.addView(text("继电器通道（每台手机独立一路）", 12, R.color.text_sub));
        final Spinner spRelay = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"不控制", "通道 0", "通道 1", "通道 2", "通道 3"});
        spRelay.setAdapter(ad);
        spRelay.setSelection(d.relay < 0 ? 0 : Math.min(d.relay + 1, 4));
        box.addView(spRelay);

        // 手动覆盖
        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(12);
        manualRow.setLayoutParams(mlp);

        Button btnOn = smallButton("强制通电");
        Button btnOff = smallButton("强制断电");
        Button btnAuto = smallButton("恢复自动");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(38), 1);
        blp.setMarginStart(0);
        manualRow.addView(btnOn, blp);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, dp(38), 1);
        blp2.setMarginStart(dp(6));
        manualRow.addView(btnOff, blp2);
        LinearLayout.LayoutParams blp3 = new LinearLayout.LayoutParams(0, dp(38), 1);
        blp3.setMarginStart(dp(6));
        manualRow.addView(btnAuto, blp3);
        box.addView(manualRow);

        btnOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualRelay(d, true);
            }
        });
        btnOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualRelay(d, false);
            }
        });
        btnAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoRelay(d);
            }
        });

        TextView note = text("阈值下发后立刻生效；ESP32 断网时仍按最后一次配置自动控制。", 11,
                R.color.text_sub);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        box.addView(note, nlp);

        new AlertDialog.Builder(this)
                .setTitle(d.id + " 的配置")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        saveConfig(d, etLow, etHigh, spRelay);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(getColor(R.color.text_main));
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void saveConfig(final Device d, EditText etLow, EditText etHigh, Spinner spRelay) {
        int low;
        int high;
        try {
            low = Integer.parseInt(etLow.getText().toString().trim());
            high = Integer.parseInt(etHigh.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "阈值必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (low < 5 || low > 60 || high < 50 || high > 100) {
            Toast.makeText(this, "低阈值 5~60，高阈值 50~100", Toast.LENGTH_SHORT).show();
            return;
        }
        if (low >= high) {
            Toast.makeText(this, "低阈值必须小于高阈值", Toast.LENGTH_SHORT).show();
            return;
        }
        final int relay = spRelay.getSelectedItemPosition() - 1;
        final int fl = low;
        final int fh = high;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DeviceApi.setConfig(baseUrl, token, d.id, fl, fh, relay);
                    toastMain("已下发：" + d.id + " " + fl + "/" + fh
                            + (relay < 0 ? " 不控制" : " 通道" + relay));
                    pollOnce();
                } catch (final Exception e) {
                    toastMain("下发失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void manualRelay(final Device d, final boolean on) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DeviceApi.setRelay(baseUrl, token, d.id, on, false);
                    toastMain(d.id + (on ? " 强制通电" : " 强制断电"));
                    pollOnce();
                } catch (final Exception e) {
                    toastMain("操作失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void autoRelay(final Device d) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DeviceApi.setRelay(baseUrl, token, d.id, false, true);
                    toastMain(d.id + " 恢复自动");
                    pollOnce();
                } catch (final Exception e) {
                    toastMain("操作失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void toastMain(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------------------------------------------------- 小工具

    private TextView text(String content, int sizeSp, int colorRes) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(sizeSp);
        t.setTextColor(getColor(colorRes));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
