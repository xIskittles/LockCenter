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

    private float clockScaleX;
    private float clockScaleY;
    private int dateExtraGapPx;
    private float dateScaleX;
    private float dateScaleY;
    private float largeClockScaleX;
    private float largeClockScaleY;
    private int cardTopMarginPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("lock_center_prefs", Context.MODE_PRIVATE);

        // Загрузка сохранённых значений
        clockScaleX = prefs.getFloat("clock_scale_x", 1.80f);
        clockScaleY = prefs.getFloat("clock_scale_y", 1.50f);
        dateExtraGapPx = prefs.getInt("date_extra_gap_px", 5);
        dateScaleX = prefs.getFloat("date_scale_x", 1.25f);
        dateScaleY = prefs.getFloat("date_scale_y", 1.15f);
        largeClockScaleX = prefs.getFloat("large_clock_scale_x", 1.35f);
        largeClockScaleY = prefs.getFloat("large_clock_scale_y", 1.05f);
        cardTopMarginPx = prefs.getInt("card_top_margin_px", 0);

        LinearLayout list = findViewById(R.id.settings_list);

        // Карточки настроек
        addFloatRow(list, "Ширина маленьких часов", 0.5f, 3.0f, clockScaleX, 0.05f, v -> clockScaleX = v);
        addFloatRow(list, "Высота маленьких часов", 0.5f, 3.0f, clockScaleY, 0.05f, v -> clockScaleY = v);
        addIntRow(list, "Отступ даты от часов (px)", -50, 150, dateExtraGapPx, v -> dateExtraGapPx = v);
        addFloatRow(list, "Ширина даты", 0.5f, 2.5f, dateScaleX, 0.05f, v -> dateScaleX = v);
        addFloatRow(list, "Высота даты", 0.5f, 2.5f, dateScaleY, 0.05f, v -> dateScaleY = v);
        addFloatRow(list, "Ширина больших часов", 0.5f, 3.0f, largeClockScaleX, 0.05f, v -> largeClockScaleX = v);
        addFloatRow(list, "Высота больших часов", 0.5f, 3.0f, largeClockScaleY, 0.05f, v -> largeClockScaleY = v);
        addIntRow(list, "Отступ карточки фонарика от даты (px)", -50, 100, cardTopMarginPx, v -> cardTopMarginPx = v);

        // Кнопка применения
        MaterialButton btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            saveAndBroadcast();
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();
        });

        // Кнопка перезагрузки SystemUI
        MaterialButton btnRestart = findViewById(R.id.btn_restart_sysui);
        btnRestart.setOnClickListener(v -> {
            saveAndBroadcast();
            restartSystemUI();
        });
    }

    private void saveAndBroadcast() {
        prefs.edit()
                .putFloat("clock_scale_x", clockScaleX)
                .putFloat("clock_scale_y", clockScaleY)
                .putInt("date_extra_gap_px", dateExtraGapPx)
                .putFloat("date_scale_x", dateScaleX)
                .putFloat("date_scale_y", dateScaleY)
                .putFloat("large_clock_scale_x", largeClockScaleX)
                .putFloat("large_clock_scale_y", largeClockScaleY)
                .putInt("card_top_margin_px", cardTopMarginPx)
                .apply();

        // Права на файл для XSharedPreferences через root
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "chmod 777 /data/data/com.anton.lockcenter/shared_prefs && chmod 777 /data/data/com.anton.lockcenter/shared_prefs/lock_center_prefs.xml"});
            } catch (Throwable ignored) {}
        }).start();

        // Сигнал на лету
        Intent intent = new Intent("com.anton.lockcenter.UPDATE_SETTINGS");
        intent.putExtra("clock_scale_x", clockScaleX);
        intent.putExtra("clock_scale_y", clockScaleY);
        intent.putExtra("date_extra_gap_px", dateExtraGapPx);
        intent.putExtra("date_scale_x", dateScaleX);
        intent.putExtra("date_scale_y", dateScaleY);
        intent.putExtra("large_clock_scale_x", largeClockScaleX);
        intent.putExtra("large_clock_scale_y", largeClockScaleY);
        intent.putExtra("card_top_margin_px", cardTopMarginPx);
        sendBroadcast(intent);
    }

    private void restartSystemUI() {
        Toast.makeText(this, "Перезагрузка SystemUI...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // Универсальная команда перезагрузки SystemUI для Pixel на Android 16
                Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -f com.android.systemui || killall com.android.systemui"});
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка Root: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
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
