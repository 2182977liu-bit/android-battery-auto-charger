package com.autophonez.manager;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 充电管理台 · 继电器测试版
 *
 * 用途：连上 ESP32-S3 后，获取它控制了几路继电器、每路当前通断状态，
 * 然后手动开/关，验证接线、通道映射和固件逻辑是否正确。
 *
 * 充放电阈值逻辑完全在 ESP32 固件里，本 App 只做开关测试，不参与控制策略。
 */
public class MainActivity extends Activity {

    private static final long POLL_MS = 2000L;
    private static final String PREF = "manager";

    private EditText etHost;
    private EditText etPort;
    private EditText etToken;
    private TextView tvStatus;
    private TextView tvEmpty;
    private LinearLayout relayBox;

    private SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Relay> relays = new ArrayList<>();

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
        relayBox = findViewById(R.id.relayBox);

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
        findViewById(R.id.btnAllOn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAll(true);
            }
        });
        findViewById(R.id.btnAllOff).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAll(false);
            }
        });

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

    private void saveAndConnect() {
        String host = etHost.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "先填 ESP32 的 IP", Toast.LENGTH_SHORT).show();
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
        baseUrl = RelayApi.baseUrl(etHost.getText().toString().trim(), port);
        token = etToken.getText().toString().trim();
        setStatus("已配置 " + baseUrl + "，正在连接…");
    }

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
                    final List<Relay> list = RelayApi.list(b, t);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            relays.clear();
                            relays.addAll(list);
                            render();
                            setStatus("已连接 · " + list.size() + " 路继电器 · " + nowText());
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

    private void render() {
        relayBox.removeAllViews();
        tvEmpty.setVisibility(relays.isEmpty() ? View.VISIBLE : View.GONE);
        for (Relay r : relays) {
            relayBox.addView(buildRow(r));
        }
    }

    private View buildRow(final Relay r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        int p = dp(12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(info, ilp);

        TextView name = text(r.name.isEmpty() ? "通道 " + r.id : r.name, 16, R.color.text_main);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);

        String state = (r.on ? "ON · 通电" : "OFF · 断开") + (r.manual ? "（手动）" : "");
        TextView tvState = text(state, 12, r.on ? R.color.ok : R.color.text_sub);
        info.addView(tvState);

        Button btnOn = smallButton("开");
        Button btnOff = smallButton("关");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(64), dp(40));
        row.addView(btnOn, blp);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(dp(64), dp(40));
        blp2.setMarginStart(dp(8));
        row.addView(btnOff, blp2);

        btnOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setOne(r, true);
            }
        });
        btnOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setOne(r, false);
            }
        });

        return row;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(getColor(R.color.text_main));
        b.setBackgroundResource(R.drawable.btn_secondary);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void setOne(final Relay r, final boolean on) {
        if (baseUrl.isEmpty()) {
            Toast.makeText(this, "先连接服务端", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RelayApi.set(baseUrl, token, r.id, on);
                    toastMain("通道 " + r.id + (on ? " 已开" : " 已关"));
                    pollOnce();
                } catch (final Exception e) {
                    toastMain("操作失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void setAll(final boolean on) {
        if (baseUrl.isEmpty()) {
            Toast.makeText(this, "先连接服务端", Toast.LENGTH_SHORT).show();
            return;
        }
        if (relays.isEmpty()) {
            Toast.makeText(this, "还没获取到继电器列表", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Relay> snapshot = new ArrayList<>(relays);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                String lastErr = "";
                for (Relay r : snapshot) {
                    try {
                        RelayApi.set(baseUrl, token, r.id, on);
                        ok++;
                    } catch (Exception e) {
                        lastErr = e.getMessage();
                    }
                }
                final int done = ok;
                final String err = lastErr;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                (on ? "全开" : "全关") + " 成功 " + done + "/" + snapshot.size()
                                        + (err.isEmpty() ? "" : "，最后错误：" + err),
                                Toast.LENGTH_SHORT).show();
                    }
                });
                pollOnce();
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
