package com.anton.lockcenter;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    // Экран блокировки
    private float clockScaleX;
    private float clockScaleY;
    private int dateExtraGapPx;
    private float dateScaleX;
    private float dateScaleY;
    private float largeClockScaleX;
    private float largeClockScaleY;
    private int cardTopMarginPx;
    private int notifTopMarginPx;

    // Док-панель (Pixel Launcher)
    private boolean dockEnabled;
    private int dockRadius;
    private int dockMarginH;
    private int dockPaddingTop;
    private int dockPaddingBottom;
    private int dockAlpha;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("lock_center_prefs", Context.MODE_PRIVATE);

        // Твои идеальные значения по умолчанию
        clockScaleX = prefs.getFloat("clock_scale_x", 1.60f);
        clockScaleY = prefs.getFloat("clock_scale_y", 1.30f);
        dateExtraGapPx = prefs.getInt("date_extra_gap_px", -20);
        dateScaleX = prefs.getFloat("date_scale_x", 1.30f);
        dateScaleY = prefs.getFloat("date_scale_y", 1.15f);
        largeClockScaleX = prefs.getFloat("large_clock_scale_x", 1.35f);
        largeClockScaleY = prefs.getFloat("large_clock_scale_y", 1.05f);
        cardTopMarginPx = prefs.getInt("card_top_margin_px", -50);
        notifTopMarginPx = prefs.getInt("notif_top_margin_px", 0);

        // Док
        dockEnabled = prefs.getBoolean("dock_enabled", true);
        dockRadius = prefs.getInt("dock_radius", 80);
        dockMarginH = prefs.getInt("dock_margin_h", 35);
        dockPaddingTop = prefs.getInt("dock_padding_top", 15);
        dockPaddingBottom = prefs.getInt("dock_padding_bottom", 15);
        dockAlpha = prefs.getInt("dock_alpha", 35);

        LinearLayout list = findViewById(R.id.settings_list);

        // Блок экрана блокировки
        addHeader(list, "🔒 Экран блокировки");
        addFloatRow(list, "Ширина маленьких часов", 0.5f, 3.0f, clockScaleX, 0.05f, v -> clockScaleX = v);
        addFloatRow(list, "Высота маленьких часов", 0.5f, 3.0f, clockScaleY, 0.05f, v -> clockScaleY = v);
        addIntRow(list, "Отступ даты от часов (px)", -50, 150, dateExtraGapPx, v -> dateExtraGapPx = v);
        addFloatRow(list, "Ширина даты", 0.5f, 2.5f, dateScaleX, 0.05f, v -> dateScaleX = v);
        addFloatRow(list, "Высота даты", 0.5f, 2.5f, dateScaleY, 0.05f, v -> dateScaleY = v);
        addFloatRow(list, "Ширина больших часов", 0.5f, 3.0f, largeClockScaleX, 0.05f, v -> largeClockScaleX = v);
        addFloatRow(list, "Высота больших часов", 0.5f, 3.0f, largeClockScaleY, 0.05f, v -> largeClockScaleY = v);
        addIntRow(list, "Отступ плашки фонарика от даты (px)", -80, 80, cardTopMarginPx, v -> cardTopMarginPx = v);
        addIntRow(list, "Отступ уведомлений от даты (px)", -100, 100, notifTopMarginPx, v -> notifTopMarginPx = v);

        // Блок док-панели рабочего стола
        addHeader(list, "🍏 Док-панель (Pixel Launcher)");
        addIntRow(list, "Закругление углов дока (px)", 0, 150, dockRadius, v -> dockRadius = v);
        addIntRow(list, "Отступ дока по бокам (px)", 0, 120, dockMarginH, v -> dockMarginH = v);
        addIntRow(list, "Отступ сверху (над иконками, px)", -20, 80, dockPaddingTop, v -> dockPaddingTop = v);
        addIntRow(list, "Отступ снизу (под поиском, px)", -20, 80, dockPaddingBottom, v -> dockPaddingBottom = v);
        addIntRow(list, "Прозрачность подложки (%)", 5, 95, dockAlpha, v -> dockAlpha = v);

        // Кнопка перезагрузки лаунчера
        MaterialButton btnRestartLauncher = new MaterialButton(this);
        btnRestartLauncher.setText("Перезагрузить Pixel Launcher (Root)");
        btnRestartLauncher.setIconResource(android.R.drawable.ic_menu_rotate);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 16, 0, 24);
        btnRestartLauncher.setLayoutParams(blp);
        btnRestartLauncher.setOnClickListener(v -> {
            saveConfigToSystem();
            Toast.makeText(this, "Перезагрузка Pixel Launcher...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -f com.google.android.apps.nexuslauncher"}).waitFor();
                } catch (Throwable ignored) {}
            }).start();
        });
        list.addView(btnRestartLauncher);

        // Кнопки верхнего блока
        MaterialButton btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            saveConfigToSystem();
            sendSettingsBroadcast();
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();
        });

        MaterialButton btnRestart = findViewById(R.id.btn_restart_sysui);
        btnRestart.setOnClickListener(v -> {
            Toast.makeText(this, "Перезагрузка SystemUI...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                saveConfigToSystemSync();
                try {
                    Thread.sleep(150);
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -f com.android.systemui || killall com.android.systemui"});
                    p.waitFor();
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this, "Ошибка Root: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private void addHeader(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(18f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(8, 28, 8, 16);
        parent.addView(tv);
    }

    private String buildConfigString() {
        return "clock_scale_x=" + clockScaleX
                + ",clock_scale_y=" + clockScaleY
                + ",date_extra_gap_px=" + dateExtraGapPx
                + ",date_scale_x=" + dateScaleX
                + ",date_scale_y=" + dateScaleY
                + ",large_clock_scale_x=" + largeClockScaleX
                + ",large_clock_scale_y=" + largeClockScaleY
                + ",card_top_margin_px=" + cardTopMarginPx
                + ",notif_top_margin_px=" + notifTopMarginPx
                + ",dock_enabled=" + dockEnabled
                + ",dock_radius=" + dockRadius
                + ",dock_margin_h=" + dockMarginH
                + ",dock_padding_top=" + dockPaddingTop
                + ",dock_padding_bottom=" + dockPaddingBottom
                + ",dock_alpha=" + dockAlpha;
    }

    private void saveConfigToSystem() {
        prefs.edit()
                .putFloat("clock_scale_x", clockScaleX)
                .putFloat("clock_scale_y", clockScaleY)
                .putInt("date_extra_gap_px", dateExtraGapPx)
                .putFloat("date_scale_x", dateScaleX)
                .putFloat("date_scale_y", dateScaleY)
                .putFloat("large_clock_scale_x", largeClockScaleX)
                .putFloat("large_clock_scale_y", largeClockScaleY)
                .putInt("card_top_margin_px", cardTopMarginPx)
                .putInt("notif_top_margin_px", notifTopMarginPx)
                .putBoolean("dock_enabled", dockEnabled)
                .putInt("dock_radius", dockRadius)
                .putInt("dock_margin_h", dockMarginH)
                .putInt("dock_padding_top", dockPaddingTop)
                .putInt("dock_padding_bottom", dockPaddingBottom)
                .putInt("dock_alpha", dockAlpha)
                .apply();

        new Thread(this::saveConfigToSystemSync).start();
    }

    private synchronized void saveConfigToSystemSync() {
        String cfgOneLine = buildConfigString();

        String fileContent = "clock_scale_x=" + clockScaleX + "\n"
                + "clock_scale_y=" + clockScaleY + "\n"
                + "date_extra_gap_px=" + dateExtraGapPx + "\n"
                + "date_scale_x=" + dateScaleX + "\n"
                + "date_scale_y=" + dateScaleY + "\n"
                + "large_clock_scale_x=" + largeClockScaleX + "\n"
                + "large_clock_scale_y=" + largeClockScaleY + "\n"
                + "card_top_margin_px=" + cardTopMarginPx + "\n"
                + "notif_top_margin_px=" + notifTopMarginPx + "\n"
                + "dock_enabled=" + dockEnabled + "\n"
                + "dock_radius=" + dockRadius + "\n"
                + "dock_margin_h=" + dockMarginH + "\n"
                + "dock_padding_top=" + dockPaddingTop + "\n"
                + "dock_padding_bottom=" + dockPaddingBottom + "\n"
                + "dock_alpha=" + dockAlpha + "\n";

        try {
            // 1. Запись в системную базу Settings.Global
            Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put global lockcenter_cfg \"" + cfgOneLine + "\""}).waitFor();

            // 2. Запись в системный каталог SystemUI
            File localFile = new File(getFilesDir(), "lockcenter.conf");
            try (FileWriter fw = new FileWriter(localFile)) {
                fw.write(fileContent);
                fw.flush();
            }
            String src = localFile.getAbsolutePath();
            String cmd = "mkdir -p /data/user_de/0/com.android.systemui && "
                    + "cp " + src + " /data/user_de/0/com.android.systemui/lockcenter.conf && "
                    + "chmod 666 /data/user_de/0/com.android.systemui/lockcenter.conf && "
                    + "mkdir -p /data/data/com.android.systemui && "
                    + "cp " + src + " /data/data/com.android.systemui/lockcenter.conf && "
                    + "chmod 666 /data/data/com.android.systemui/lockcenter.conf";
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
        } catch (Throwable ignored) {}
    }

    private void sendSettingsBroadcast() {
        Intent intent = new Intent("com.anton.lockcenter.UPDATE_SETTINGS");
        intent.putExtra("clock_scale_x", clockScaleX);
        intent.putExtra("clock_scale_y", clockScaleY);
        intent.putExtra("date_extra_gap_px", dateExtraGapPx);
        intent.putExtra("date_scale_x", dateScaleX);
        intent.putExtra("date_scale_y", dateScaleY);
        intent.putExtra("large_clock_scale_x", largeClockScaleX);
        intent.putExtra("large_clock_scale_y", largeClockScaleY);
        intent.putExtra("card_top_margin_px", cardTopMarginPx);
        intent.putExtra("notif_top_margin_px", notifTopMarginPx);
        intent.putExtra("dock_enabled", dockEnabled);
        intent.putExtra("dock_radius", dockRadius);
        intent.putExtra("dock_margin_h", dockMarginH);
        intent.putExtra("dock_padding_top", dockPaddingTop);
        intent.putExtra("dock_padding_bottom", dockPaddingBottom);
        intent.putExtra("dock_alpha", dockAlpha);

        // В SystemUI
        Intent intentSys = new Intent(intent);
        intentSys.setPackage("com.android.systemui");
        sendBroadcast(intentSys);

        // В Pixel Launcher
        Intent intentLauncher = new Intent(intent);
        intentLauncher.setPackage("com.google.android.apps.nexuslauncher");
        sendBroadcast(intentLauncher);
    }

    private interface OnFloatChanged {
        void onVal(float val);
    }

    private interface OnIntChanged {
        void onVal(int val);
    }

    private void addFloatRow(LinearLayout parent, String title, float min, float max, float current, float step, OnFloatChanged listener) {
        LinearLayout content = createCard(parent, title);

        EditText input = new EditText(this);
        input.setText(String.format(Locale.US, "%.2f", current));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

        Slider slider = new Slider(this);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        float safeCurrent = Math.max(min, Math.min(max, current));
        slider.setValue(safeCurrent);

        final boolean[] internal = {false};

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                internal[0] = true;
                input.setText(String.format(Locale.US, "%.2f", value));
                listener.onVal(value);
                internal[0] = false;
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (internal[0]) return;
                try {
                    float v = Float.parseFloat(s.toString());
                    if (v >= min && v <= max) {
                        slider.setValue(v);
                        listener.onVal(v);
                    }
                } catch (Throwable ignored) {}
            }
        });

        content.addView(input);
        content.addView(slider);
    }

    private void addIntRow(LinearLayout parent, String title, int min, int max, int current, OnIntChanged listener) {
        LinearLayout content = createCard(parent, title);

        EditText input = new EditText(this);
        input.setText(String.valueOf(current));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);

        Slider slider = new Slider(this);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(1f);
        float safeCurrent = Math.max(min, Math.min(max, (float) current));
        slider.setValue(safeCurrent);

        final boolean[] internal = {false};

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) {
                internal[0] = true;
                int val = Math.round(value);
                input.setText(String.valueOf(val));
                listener.onVal(val);
                internal[0] = false;
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (internal[0]) return;
                try {
                    int v = Integer.parseInt(s.toString());
                    if (v >= min && v <= max) {
                        slider.setValue((float) v);
                        listener.onVal(v);
                    }
                } catch (Throwable ignored) {}
            }
        });

        content.addView(input);
        content.addView(slider);
    }

    private LinearLayout createCard(LinearLayout parent, String title) {
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, 24);
        card.setLayoutParams(clp);
        card.setCardElevation(2f);
        card.setRadius(28f);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 24, 32, 16);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(tv);

        card.addView(content);
        parent.addView(card);
        return content;
    }
}
