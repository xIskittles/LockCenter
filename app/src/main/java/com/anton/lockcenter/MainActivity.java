package com.anton.lockcenter;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    private SharedPreferences prefs;

    // Твои идеальные значения по умолчанию (экран блокировки)
    private float clockScaleX;
    private float clockScaleY;
    private int dateExtraGapPx;
    private float dateScaleX;
    private float dateScaleY;
    private float largeClockScaleX;
    private float largeClockScaleY;
    private int cardTopMarginPx;
    private int notifTopMarginPx;

    // Док-панель лаунчера (iOS-style подложка)
    private boolean dockEnabled;
    private boolean dockLayerMode;
    private boolean dockKeepOriginalBg;
    private boolean dockIncludeSearch;
    private int dockRadiusPx;
    private int dockSideMarginPx;
    private int dockTopPadPx;
    private int dockBottomPadPx;
    private int dockOffsetYPx;
    private int dockAlpha;
    private int dockColorR;
    private int dockColorG;
    private int dockColorB;
    private boolean dockFrosted;
    private int dockFrostRadius;
    private int dockFrostAlpha;
    private int dockFrostOffsetX;
    private int dockFrostOffsetY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("lock_center_prefs", Context.MODE_PRIVATE);

        clockScaleX = prefs.getFloat("clock_scale_x", 1.60f);
        clockScaleY = prefs.getFloat("clock_scale_y", 1.30f);
        dateExtraGapPx = prefs.getInt("date_extra_gap_px", -20);
        dateScaleX = prefs.getFloat("date_scale_x", 1.30f);
        dateScaleY = prefs.getFloat("date_scale_y", 1.15f);
        largeClockScaleX = prefs.getFloat("large_clock_scale_x", 1.35f);
        largeClockScaleY = prefs.getFloat("large_clock_scale_y", 1.05f);
        cardTopMarginPx = prefs.getInt("card_top_margin_px", -50);
        notifTopMarginPx = prefs.getInt("notif_top_margin_px", 0);

        dockEnabled = prefs.getBoolean("dock_enabled", true);
        dockLayerMode = prefs.getBoolean("dock_layer_mode", false);
        dockKeepOriginalBg = prefs.getBoolean("dock_keep_original_bg", false);
        dockIncludeSearch = prefs.getBoolean("dock_include_search", true);
        dockRadiusPx = prefs.getInt("dock_radius_px", 44);
        dockSideMarginPx = prefs.getInt("dock_side_margin_px", 10);
        dockTopPadPx = prefs.getInt("dock_top_pad_px", 6);
        dockBottomPadPx = prefs.getInt("dock_bottom_pad_px", 6);
        dockOffsetYPx = prefs.getInt("dock_offset_y_px", 0);
        dockAlpha = prefs.getInt("dock_alpha", 70);
        dockColorR = prefs.getInt("dock_color_r", 255);
        dockColorG = prefs.getInt("dock_color_g", 255);
        dockColorB = prefs.getInt("dock_color_b", 255);
        dockFrosted = prefs.getBoolean("dock_frosted", false);
        dockFrostRadius = prefs.getInt("dock_frost_radius", 24);
        dockFrostAlpha = prefs.getInt("dock_frost_alpha", 150);
        dockFrostOffsetX = prefs.getInt("dock_frost_offset_x", 0);
        dockFrostOffsetY = prefs.getInt("dock_frost_offset_y", 0);

        LinearLayout list = findViewById(R.id.settings_list);

        addSectionHeader(list, "Экран блокировки (SystemUI)");
        addFloatRow(list, "Ширина маленьких часов", 0.5f, 3.0f, clockScaleX, 0.05f, v -> clockScaleX = v);
        addFloatRow(list, "Высота маленьких часов", 0.5f, 3.0f, clockScaleY, 0.05f, v -> clockScaleY = v);
        addIntRow(list, "Отступ даты от часов (px)", -50, 150, dateExtraGapPx, v -> dateExtraGapPx = v);
        addFloatRow(list, "Ширина даты", 0.5f, 2.5f, dateScaleX, 0.05f, v -> dateScaleX = v);
        addFloatRow(list, "Высота даты", 0.5f, 2.5f, dateScaleY, 0.05f, v -> dateScaleY = v);
        addFloatRow(list, "Ширина больших часов", 0.5f, 3.0f, largeClockScaleX, 0.05f, v -> largeClockScaleX = v);
        addFloatRow(list, "Высота больших часов", 0.5f, 3.0f, largeClockScaleY, 0.05f, v -> largeClockScaleY = v);
        addIntRow(list, "Отступ плашки фонарика от даты (px)", -80, 80, cardTopMarginPx, v -> cardTopMarginPx = v);
        addIntRow(list, "Отступ уведомлений от даты (px)", -100, 100, notifTopMarginPx, v -> notifTopMarginPx = v);

        addSectionHeader(list, "Док-панель рабочего стола (Pixel Launcher)");
        addSwitchRow(list, "Включить подложку дока", dockEnabled, v -> dockEnabled = v);
        addSwitchRow(list, "Альтернативный режим (свой слой)", dockLayerMode, v -> dockLayerMode = v);
        addSwitchRow(list, "Сохранять родной фон дока", dockKeepOriginalBg, v -> dockKeepOriginalBg = v);
        addSwitchRow(list, "Захватывать строку поиска", dockIncludeSearch, v -> dockIncludeSearch = v);
        addIntRow(list, "Закругление подложки (px)", 0, 200, dockRadiusPx, v -> dockRadiusPx = v);
        addIntRow(list, "Боковой отступ от краёв (px)", 0, 200, dockSideMarginPx, v -> dockSideMarginPx = v);
        addIntRow(list, "Отступ сверху (px)", 0, 200, dockTopPadPx, v -> dockTopPadPx = v);
        addIntRow(list, "Отступ снизу (px)", 0, 200, dockBottomPadPx, v -> dockBottomPadPx = v);
        addIntRow(list, "Сдвиг по вертикали (px)", -250, 250, dockOffsetYPx, v -> dockOffsetYPx = v);

        addSectionHeader(list, "Цвет подложки");
        addIntRow(list, "Прозрачность (0-255)", 0, 255, dockAlpha, v -> dockAlpha = v);
        addIntRow(list, "Красный (R)", 0, 255, dockColorR, v -> dockColorR = v);
        addIntRow(list, "Зелёный (G)", 0, 255, dockColorG, v -> dockColorG = v);
        addIntRow(list, "Синий (B)", 0, 255, dockColorB, v -> dockColorB = v);

        addSectionHeader(list, "Матовое стекло (экспериментально)");
        addSwitchRow(list, "Размытие обоев под подложкой", dockFrosted, v -> dockFrosted = v);
        addIntRow(list, "Сила размытия (px)", 0, 80, dockFrostRadius, v -> dockFrostRadius = v);
        addIntRow(list, "Непрозрачность стекла (0-255)", 0, 255, dockFrostAlpha, v -> dockFrostAlpha = v);
        addIntRow(list, "Смещение обоев по X (px)", -800, 800, dockFrostOffsetX, v -> dockFrostOffsetX = v);
        addIntRow(list, "Смещение обоев по Y (px)", -800, 800, dockFrostOffsetY, v -> dockFrostOffsetY = v);

        MaterialButton btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            new Thread(this::saveConfigSync).start();
            sendSettingsBroadcast();
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();
        });

        MaterialButton btnRestart = findViewById(R.id.btn_restart_sysui);
        btnRestart.setOnClickListener(v -> {
            Toast.makeText(this, "Перезагрузка SystemUI...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                saveConfigSync();
                try {
                    Thread.sleep(150);
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -f com.android.systemui || killall com.android.systemui"});
                    p.waitFor();
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this, "Ошибка Root: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });

        MaterialButton btnRestartLauncher = findViewById(R.id.btn_restart_launcher);
        btnRestartLauncher.setOnClickListener(v -> {
            Toast.makeText(this, "Перезагрузка лаунчера...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                saveConfigSync();
                try {
                    Thread.sleep(150);
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "pkill -f com.google.android.apps.nexuslauncher || killall com.google.android.apps.nexuslauncher"});
                    p.waitFor();
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this, "Ошибка Root: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });

        MaterialButton btnDump = findViewById(R.id.btn_dump_launcher);
        btnDump.setOnClickListener(v -> {
            Intent intent = new Intent("com.anton.lockcenter.UPDATE_SETTINGS");
            intent.setPackage(LAUNCHER_PKG);
            intent.putExtra("dock_debug_dump", true);
            sendBroadcast(intent);
            Toast.makeText(this, "Дамп отправлен. Смотрите логи LSPosed (фильтр LockCenter).", Toast.LENGTH_LONG).show();
        });
    }

    private synchronized void saveConfigSync() {
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
                .putBoolean("dock_layer_mode", dockLayerMode)
                .putBoolean("dock_keep_original_bg", dockKeepOriginalBg)
                .putBoolean("dock_include_search", dockIncludeSearch)
                .putInt("dock_radius_px", dockRadiusPx)
                .putInt("dock_side_margin_px", dockSideMarginPx)
                .putInt("dock_top_pad_px", dockTopPadPx)
                .putInt("dock_bottom_pad_px", dockBottomPadPx)
                .putInt("dock_offset_y_px", dockOffsetYPx)
                .putInt("dock_alpha", dockAlpha)
                .putInt("dock_color_r", dockColorR)
                .putInt("dock_color_g", dockColorG)
                .putInt("dock_color_b", dockColorB)
                .putBoolean("dock_frosted", dockFrosted)
                .putInt("dock_frost_radius", dockFrostRadius)
                .putInt("dock_frost_alpha", dockFrostAlpha)
                .putInt("dock_frost_offset_x", dockFrostOffsetX)
                .putInt("dock_frost_offset_y", dockFrostOffsetY)
                .apply();

        // Безопасный формат через запятые без спецсимволов консоли
        String cfgOneLine = "clock_scale_x=" + clockScaleX
                + ",clock_scale_y=" + clockScaleY
                + ",date_extra_gap_px=" + dateExtraGapPx
                + ",date_scale_x=" + dateScaleX
                + ",date_scale_y=" + dateScaleY
                + ",large_clock_scale_x=" + largeClockScaleX
                + ",large_clock_scale_y=" + largeClockScaleY
                + ",card_top_margin_px=" + cardTopMarginPx
                + ",notif_top_margin_px=" + notifTopMarginPx
                + ",dock_enabled=" + bool(dockEnabled)
                + ",dock_mode=" + bool(dockLayerMode)
                + ",dock_keep_original_bg=" + bool(dockKeepOriginalBg)
                + ",dock_include_search=" + bool(dockIncludeSearch)
                + ",dock_radius_px=" + dockRadiusPx
                + ",dock_side_margin_px=" + dockSideMarginPx
                + ",dock_top_pad_px=" + dockTopPadPx
                + ",dock_bottom_pad_px=" + dockBottomPadPx
                + ",dock_offset_y_px=" + dockOffsetYPx
                + ",dock_alpha=" + dockAlpha
                + ",dock_color_r=" + dockColorR
                + ",dock_color_g=" + dockColorG
                + ",dock_color_b=" + dockColorB
                + ",dock_frosted=" + bool(dockFrosted)
                + ",dock_frost_radius=" + dockFrostRadius
                + ",dock_frost_alpha=" + dockFrostAlpha
                + ",dock_frost_offset_x=" + dockFrostOffsetX
                + ",dock_frost_offset_y=" + dockFrostOffsetY;

        String fileContent = "clock_scale_x=" + clockScaleX + "\n"
                + "clock_scale_y=" + clockScaleY + "\n"
                + "date_extra_gap_px=" + dateExtraGapPx + "\n"
                + "date_scale_x=" + dateScaleX + "\n"
                + "date_scale_y=" + dateScaleY + "\n"
                + "large_clock_scale_x=" + largeClockScaleX + "\n"
                + "large_clock_scale_y=" + largeClockScaleY + "\n"
                + "card_top_margin_px=" + cardTopMarginPx + "\n"
                + "notif_top_margin_px=" + notifTopMarginPx + "\n"
                + "dock_enabled=" + bool(dockEnabled) + "\n"
                + "dock_mode=" + bool(dockLayerMode) + "\n"
                + "dock_keep_original_bg=" + bool(dockKeepOriginalBg) + "\n"
                + "dock_include_search=" + bool(dockIncludeSearch) + "\n"
                + "dock_radius_px=" + dockRadiusPx + "\n"
                + "dock_side_margin_px=" + dockSideMarginPx + "\n"
                + "dock_top_pad_px=" + dockTopPadPx + "\n"
                + "dock_bottom_pad_px=" + dockBottomPadPx + "\n"
                + "dock_offset_y_px=" + dockOffsetYPx + "\n"
                + "dock_alpha=" + dockAlpha + "\n"
                + "dock_color_r=" + dockColorR + "\n"
                + "dock_color_g=" + dockColorG + "\n"
                + "dock_color_b=" + dockColorB + "\n"
                + "dock_frosted=" + bool(dockFrosted) + "\n"
                + "dock_frost_radius=" + dockFrostRadius + "\n"
                + "dock_frost_alpha=" + dockFrostAlpha + "\n"
                + "dock_frost_offset_x=" + dockFrostOffsetX + "\n"
                + "dock_frost_offset_y=" + dockFrostOffsetY + "\n";

        String extra = SYSTEMUI_PKG + " " + LAUNCHER_PKG;

        try {
            // 1. В системную базу Settings.Global
            Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put global lockcenter_cfg \"" + cfgOneLine + "\""}).waitFor();

            // 2. В системный файл каталогов процессов (SystemUI + лаунчер)
            File localFile = new File(getFilesDir(), "lockcenter.conf");
            try (FileWriter fw = new FileWriter(localFile)) {
                fw.write(fileContent);
                fw.flush();
            }
            String src = localFile.getAbsolutePath();
            StringBuilder cmd = new StringBuilder();
            for (String pkg : extra.split(" ")) {
                cmd.append("mkdir -p /data/user_de/0/").append(pkg).append(" && ")
                        .append("cp ").append(src).append(" /data/user_de/0/").append(pkg).append("/lockcenter.conf && ")
                        .append("chmod 666 /data/user_de/0/").append(pkg).append("/lockcenter.conf && ")
                        .append("mkdir -p /data/data/").append(pkg).append(" && ")
                        .append("cp ").append(src).append(" /data/data/").append(pkg).append("/lockcenter.conf && ")
                        .append("chmod 666 /data/data/").append(pkg).append("/lockcenter.conf; ");
            }
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd.toString()}).waitFor();
        } catch (Throwable ignored) {}
    }

    private static String bool(boolean b) {
        return b ? "1" : "0";
    }

    private void sendSettingsBroadcast() {
        for (String pkg : new String[]{SYSTEMUI_PKG, LAUNCHER_PKG}) {
            Intent intent = new Intent("com.anton.lockcenter.UPDATE_SETTINGS");
            intent.setPackage(pkg);
            intent.putExtra("clock_scale_x", clockScaleX);
            intent.putExtra("clock_scale_y", clockScaleY);
            intent.putExtra("date_extra_gap_px", dateExtraGapPx);
            intent.putExtra("date_scale_x", dateScaleX);
            intent.putExtra("date_scale_y", dateScaleY);
            intent.putExtra("large_clock_scale_x", largeClockScaleX);
            intent.putExtra("large_clock_scale_y", largeClockScaleY);
            intent.putExtra("card_top_margin_px", cardTopMarginPx);
            intent.putExtra("notif_top_margin_px", notifTopMarginPx);
            sendBroadcast(intent);
        }
    }

    private interface OnFloatChanged {
        void onVal(float val);
    }

    private interface OnIntChanged {
        void onVal(int val);
    }

    private interface OnBoolChanged {
        void onVal(boolean val);
    }

    private void addSectionHeader(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(8, 24, 8, 12);
        parent.addView(tv);
    }

    private void addSwitchRow(LinearLayout parent, String title, boolean current, OnBoolChanged listener) {
        LinearLayout content = createCard(parent, title);

        SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(current);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onVal(isChecked));
        content.addView(sw);
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
