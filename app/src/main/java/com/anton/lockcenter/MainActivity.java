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

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    // Твои идеальные значения по умолчанию
    private float clockScaleX;
    private float clockScaleY;
    private int dateExtraGapPx;
    private float dateScaleX;
    private float dateScaleY;
    private float largeClockScaleX;
    private float largeClockScaleY;
    private int cardTopMarginPx;
    private int notifTopMarginPx;

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

        LinearLayout list = findViewById(R.id.settings_list);

        addFloatRow(list, "Ширина маленьких часов", 0.5f, 3.0f, clockScaleX, 0.05f, v -> clockScaleX = v);
        addFloatRow(list, "Высота маленьких часов", 0.5f, 3.0f, clockScaleY, 0.05f, v -> clockScaleY = v);
        addIntRow(list, "Отступ даты от часов (px)", -50, 150, dateExtraGapPx, v -> dateExtraGapPx = v);
        addFloatRow(list, "Ширина даты", 0.5f, 2.5f, dateScaleX, 0.05f, v -> dateScaleX = v);
        addFloatRow(list, "Высота даты", 0.5f, 2.5f, dateScaleY, 0.05f, v -> dateScaleY = v);
        addFloatRow(list, "Ширина больших часов", 0.5f, 3.0f, largeClockScaleX, 0.05f, v -> largeClockScaleX = v);
        addFloatRow(list, "Высота больших часов", 0.5f, 3.0f, largeClockScaleY, 0.05f, v -> largeClockScaleY = v);
        addIntRow(list, "Отступ плашки фонарика от даты (px)", -80, 80, cardTopMarginPx, v -> cardTopMarginPx = v);
        addIntRow(list, "Отступ уведомлений от даты (px)", -100, 100, notifTopMarginPx, v -> notifTopMarginPx = v);

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
                    Thread.sleep(100);
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -f com.android.systemui || killall com.android.systemui"});
                    p.waitFor();
                } catch (Throwable t) {
                    runOnUiThread(() -> Toast.makeText(this, "Ошибка Root: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private String buildConfigString() {
        return "clock_scale_x=" + clockScaleX
                + ";clock_scale_y=" + clockScaleY
                + ";date_extra_gap_px=" + dateExtraGapPx
                + ";date_scale_x=" + dateScaleX
                + ";date_scale_y=" + dateScaleY
                + ";large_clock_scale_x=" + largeClockScaleX
                + ";large_clock_scale_y=" + largeClockScaleY
                + ";card_top_margin_px=" + cardTopMarginPx
                + ";notif_top_margin_px=" + notifTopMarginPx;
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
                .apply();

        new Thread(this::saveConfigToSystemSync).start();
    }

    /** Записывает параметры в системную базу Settings.Global через Root */
    private synchronized void saveConfigToSystemSync() {
        String cfg = buildConfigString();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put global lockcenter_cfg '" + cfg + "'"});
            p.waitFor();
        } catch (Throwable ignored) {}
    }

    private void sendSettingsBroadcast() {
        Intent intent = new Intent("com.anton.lockcenter.UPDATE_SETTINGS");
        intent.setPackage("com.android.systemui");
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
