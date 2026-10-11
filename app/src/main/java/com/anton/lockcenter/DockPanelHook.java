package com.anton.lockcenter;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LockCenter — док-панель рабочего стола (Pixel Launcher / Launcher3).
 *
 * Рисует настраиваемую полупрозрачную подложку под доком и, по желанию,
 * под строкой поиска рабочего стола, с лёгкой обводкой и «системным»
 * матовым стеклом.
 *
 * Два режима (переключаются в приложении):
 *   0 — «Фон дока» (по умолчанию): подложка ставится фоном самой View Hotseat.
 *       Так делает Lawnchair: фон рисуется до детей, поэтому оказывается под
 *       иконками и под строкой поиска (она — ребёнок Hotseat), и автоматически
 *       наследует все системные анимации дока (alpha, scale, translationY).
 *   1 — «Свой слой»: подложка рисуется в display list DragLayer перед его
 *       детьми. Даёт свободную геометрию (расширение за границы дока, сдвиг),
 *       геометрия пересчитывается каждый кадр в OnPreDraw.
 *
 * Отрисовка: скруглённый прямоугольник + опциональная обводка (PanelDrawable
 * / drawPanel). Цвет — либо вручную (R/G/B), либо из системной палитры
 * Material You (android.R.color.system_accent1_* / system_neutral1_*).
 * «Матовое стекло» — сэмпл размытых обоев с регулируемой насыщенностью,
 * поверх которого рисуется цветная «поверхность» (как системная прозрачность).
 * Настоящий backdrop-blur окна лаунчера недоступен: setBlurBehindRadius размыл
 * бы весь экран за окном, а View.setBackgroundBlurRadius в публичном API нет.
 */
public class DockPanelHook implements IXposedHookLoadPackage {

    private static final String TAG = "[LockCenter/Dock] ";
    private static final String ACTION = "com.anton.lockcenter.UPDATE_SETTINGS";
    private static final String CFG_KEY = "lockcenter_cfg";

    private static final int MODE_BACKGROUND = 0;
    private static final int MODE_LAYER = 1;

    // ------------------------------------------------------------------ настройки

    static volatile boolean enabled = true;
    static volatile int mode = MODE_BACKGROUND;
    static volatile boolean keepOriginalBg = false;
    static volatile boolean includeSearch = true;
    static volatile int radiusPx = 44;
    static volatile int sideMarginPx = 10;
    static volatile int topPadPx = 6;
    static volatile int bottomPadPx = 6;
    static volatile int offsetYPx = 0;
    static volatile int alpha = 70;
    static volatile int colorMode = 0;          // 0 = вручную, 1..6 = системные цвета Material You
    static volatile int colorR = 255;
    static volatile int colorG = 255;
    static volatile int colorB = 255;
    static volatile int strokeWidthPx = 1;      // толщина обводки, 0 = выключена
    static volatile int strokeAlpha = 40;       // прозрачность обводки
    static volatile boolean strokeLighten = true;
    static volatile boolean frosted = false;
    static volatile int frostRadius = 24;
    static volatile int frostAlpha = 150;
    static volatile int frostSaturation = 130;
    static volatile int frostOffsetX = 0;
    static volatile int frostOffsetY = 0;

    /** Нужно пересобрать размытые обои. */
    static volatile boolean frostDirty = true;
    /** Запрошен дамп дерева View в лог. */
    static volatile boolean dumpRequested = false;

    private static volatile String currentPackage = "com.google.android.apps.nexuslauncher";

    // ------------------------------------------------------------------ хуки

    private static final Set<String> LAUNCHER_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3"));

    private static final String[] HOTSEAT_CLASS_CANDIDATES = {
            "com.android.launcher3.Hotseat",
            "com.android.launcher3.hotseat.Hotseat",
            "com.google.android.apps.nexuslauncher.hotseat.Hotseat"
    };

    /** Поля Hotseat, в которых лежит строка поиска рабочего стола. */
    private static final String[] QSB_FIELD_CANDIDATES = {
            "mQsb", "mSearchBar", "mSearchView", "mSearchContainer"
    };

    /** Кэш доступа к строке поиска (Method/Field), чтобы не кидать исключения каждый кадр. */
    private static final Object NO_QSB = new Object();
    private static final Map<View, Object> QSB_ACCESSOR =
            Collections.synchronizedMap(new WeakHashMap<View, Object>());

    private static final Set<View> WATCHED_HOTSEATS =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final Set<String> HOOKED_DRAW_CLASSES =
            Collections.synchronizedSet(new HashSet<String>());

    private static final Map<ViewGroup, PanelState> STATES =
            Collections.synchronizedMap(new WeakHashMap<ViewGroup, PanelState>());

    private static final Set<String> LOGGED_ONCE =
            Collections.synchronizedSet(new HashSet<String>());

    private static boolean receiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        if (pkg == null || !LAUNCHER_PACKAGES.contains(pkg)) return;
        currentPackage = pkg;

        ClassLoader cl = lpparam.classLoader;
        Class<?> hotseatClass = null;
        for (int i = 0; i < HOTSEAT_CLASS_CANDIDATES.length; i++) {
            hotseatClass = XposedHelpers.findClassIfExists(HOTSEAT_CLASS_CANDIDATES[i], cl);
            if (hotseatClass != null) break;
        }
        if (hotseatClass == null) {
            log("класс Hotseat не найден в " + pkg + " — док-панель отключена");
            return;
        }
        log("модуль активен в " + pkg + ", Hotseat = " + hotseatClass.getName());

        XposedBridge.hookAllConstructors(hotseatClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    if (self instanceof View) onHotseatCreated((View) self);
                } catch (Throwable t) {
                    log("Hotseat ctor: " + t);
                }
            }
        });

        // resetLayout() пересоздаёт содержимое дока (смена сетки/поворота) — вернём фон
        try {
            XposedBridge.hookAllMethods(hotseatClass, "resetLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (self instanceof View) {
                            APPLIED_BG_SIG.remove(self);
                            updateHotseatBackground((View) self);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("hook resetLayout: " + t);
        }
    }

    private static void onHotseatCreated(final View hotseat) {
        try {
            loadConfig(hotseat.getContext());
            registerReceiver(hotseat.getContext());
            logSettings("настройки");
        } catch (Throwable t) {
            log("onHotseatCreated config: " + t);
        }

        if (!WATCHED_HOTSEATS.add(hotseat)) return;
        hotseat.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                attach(v);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                detach(v);
            }
        });
        if (hotseat.isAttachedToWindow()) attach(hotseat);
    }

    private static void attach(View hotseat) {
        // режим 0 — подложка ставится фоном самого Hotseat
        if (mode == MODE_BACKGROUND) {
            updateHotseatBackground(hotseat);
        }

        ViewGroup container = findContainer(hotseat);
        if (container == null) {
            logOnce("контейнер DragLayer не найден");
            return;
        }
        PanelState state;
        synchronized (STATES) {
            state = STATES.get(container);
            if (state == null) {
                state = new PanelState(container);
                STATES.put(container, state);
            }
        }
        state.setHotseat(hotseat);
        state.start();
        hookDraw(container);
        log("подложка привязана: container=" + container.getClass().getName()
                + " hotseat=" + hotseat.getClass().getName());
    }

    /**
     * Режим «свой слой»: подложка рисуется в dispatchDraw контейнера — то есть
     * до его детей. Хукаем только метод, объявленный в самом контейнере; если
     * его нет — используем ViewGroup#dispatchDraw (колбэк вызывается для всех
     * ViewGroup, но мгновенно выходит по отсутствию состояния).
     */
    private static void hookDraw(final ViewGroup container) {
        final Class<?> cls = container.getClass();
        if (!HOOKED_DRAW_CLASSES.add(cls.getName())) return;

        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    if (self == null) return;
                    PanelState st = STATES.get(self);
                    if (st == null) return;
                    Object arg = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                    if (arg instanceof Canvas) st.drawOn((Canvas) arg);
                } catch (Throwable t) {
                    logOnce("drawHook: " + t);
                }
            }
        };

        try {
            java.lang.reflect.Method own = XposedHelpers.findMethodExactIfExists(cls, "dispatchDraw", Canvas.class);
            if (own != null) {
                XposedBridge.hookMethod(own, callback);
                log("хук отрисовки: " + cls.getName() + "#dispatchDraw");
            } else {
                Class<?> vg = XposedHelpers.findClass("android.view.ViewGroup", cls.getClassLoader());
                java.lang.reflect.Method base = XposedHelpers.findMethodExactIfExists(vg, "dispatchDraw", Canvas.class);
                if (base != null) {
                    XposedBridge.hookMethod(base, callback);
                    log("хук отрисовки: android.view.ViewGroup#dispatchDraw (для " + cls.getName() + ")");
                } else {
                    log("не удалось повесить хук отрисовки на " + cls.getName());
                }
            }
        } catch (Throwable t) {
            log("hookDraw: " + t);
        }
    }

    private static void detach(View hotseat) {
        synchronized (STATES) {
            for (PanelState s : STATES.values()) {
                View hs = s.hotseatRef.get();
                if (hs == null || hs == hotseat) s.stop();
            }
        }
    }

    /** Ищем DragLayer: в режиме «свой слой» рисуем именно в нём. */
    private static ViewGroup findContainer(View hotseat) {
        ViewParent p = hotseat.getParent();
        ViewGroup best = null;
        int guard = 0;
        while (p instanceof ViewGroup && guard++ < 40) {
            ViewGroup g = (ViewGroup) p;
            String n = g.getClass().getName();
            if (n.endsWith("DecorView")) break;
            if (n.contains("DragLayer")) return g;
            best = g;
            p = g.getParent();
        }
        return best;
    }

    /** Строка поиска: сначала публичный getQsb()/поле mQsb, потом поиск по дереву. */
    static View readSearchView(View hotseat) {
        if (hotseat == null) return null;
        Object accessor = QSB_ACCESSOR.get(hotseat);
        if (accessor == null) {
            accessor = resolveQsbAccessor(hotseat);
            QSB_ACCESSOR.put(hotseat, accessor);
        }
        if (accessor == NO_QSB) return null;
        try {
            if (accessor instanceof java.lang.reflect.Method) {
                Object o = ((java.lang.reflect.Method) accessor).invoke(hotseat);
                if (o instanceof View) return (View) o;
            } else if (accessor instanceof java.lang.reflect.Field) {
                Object o = ((java.lang.reflect.Field) accessor).get(hotseat);
                if (o instanceof View) return (View) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object resolveQsbAccessor(View hotseat) {
        Class<?> c = hotseat.getClass();
        try {
            java.lang.reflect.Method m = c.getMethod("getQsb");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
        }
        for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
            for (int i = 0; i < QSB_FIELD_CANDIDATES.length; i++) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField(QSB_FIELD_CANDIDATES[i]);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return NO_QSB;
    }

    // ------------------------------------------------------------------ цвета (Material You + вручную)

    /** Цвет из системной палитры (Material You): те же цвета, что использует система. */
    private static int systemColor(Context ctx, int cmode) {
        if (ctx == null || cmode <= 0) return 0;
        int id;
        switch (cmode) {
            case 1: id = android.R.color.system_accent1_100; break;
            case 2: id = android.R.color.system_accent1_200; break;
            case 3: id = android.R.color.system_accent1_500; break;
            case 4: id = android.R.color.system_neutral1_100; break;
            case 5: id = android.R.color.system_neutral1_500; break;
            case 6: id = android.R.color.system_accent3_100; break;
            default: return 0;
        }
        try {
            return ctx.getResources().getColor(id, ctx.getTheme());
        } catch (Throwable t) {
            logOnce("systemColor: " + t);
            return 0;
        }
    }

    private static int baseColor(Context ctx) {
        int c = systemColor(ctx, colorMode);
        if (c != 0) return c;
        return Color.rgb(clamp(colorR, 0, 255), clamp(colorG, 0, 255), clamp(colorB, 0, 255));
    }

    private static int lighten(int channel) {
        return clamp(channel + Math.round((255 - channel) * 0.45f), 0, 255);
    }

    private static int panelColor(Context ctx) {
        int c = baseColor(ctx);
        int a = frosted ? clamp(frostAlpha, 0, 255) : clamp(alpha, 0, 255);
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int strokeColor(Context ctx) {
        int c = baseColor(ctx);
        if (strokeLighten) {
            c = Color.rgb(lighten(Color.red(c)), lighten(Color.green(c)), lighten(Color.blue(c)));
        }
        return Color.argb(clamp(strokeAlpha, 0, 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    // ------------------------------------------------------------------ примитив отрисовки

    private static final Paint FILL_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF STROKE_RECT = new RectF();
    private static final Matrix SHADER_MATRIX = new Matrix();

    static {
        FILL_PAINT.setStyle(Paint.Style.FILL);
        STROKE_PAINT.setStyle(Paint.Style.STROKE);
    }

    /** Отрисовка подложки: стекло (если включено) → цветная поверхность → обводка. */
    private static void drawPanel(Canvas canvas, RectF r, View anchor, Context ctx) {
        if (r.width() <= 0.5f || r.height() <= 0.5f) return;
        float rad = Math.min(radiusPx, Math.min(r.width(), r.height()) / 2f);

        if (frosted) {
            BitmapShader shader = frostShader(ctx, anchor);
            if (shader != null) {
                float f = Math.max(1f, frostFactor);
                int[] loc = new int[2];
                if (anchor != null && anchor.isAttachedToWindow()) anchor.getLocationInWindow(loc);
                SHADER_MATRIX.setScale(1f / f, 1f / f);
                SHADER_MATRIX.postTranslate((loc[0] + frostOffsetX) / f, (loc[1] + frostOffsetY) / f);
                shader.setLocalMatrix(SHADER_MATRIX);
                FILL_PAINT.setShader(shader);
                FILL_PAINT.setColorFilter(saturationFilter());
                FILL_PAINT.setAlpha(255);
                canvas.drawRoundRect(r, rad, rad, FILL_PAINT);
            }
        }

        FILL_PAINT.setShader(null);
        FILL_PAINT.setColorFilter(null);
        FILL_PAINT.setColor(panelColor(ctx));
        canvas.drawRoundRect(r, rad, rad, FILL_PAINT);

        if (strokeWidthPx > 0 && strokeAlpha > 0) {
            float inset = strokeWidthPx / 2f;
            STROKE_RECT.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
            if (STROKE_RECT.width() > 0.5f && STROKE_RECT.height() > 0.5f) {
                STROKE_PAINT.setStrokeWidth(strokeWidthPx);
                STROKE_PAINT.setColor(strokeColor(ctx));
                float srad = Math.max(0f, rad - inset);
                canvas.drawRoundRect(STROKE_RECT, srad, srad, STROKE_PAINT);
            }
        }
    }

    // ------------------------------------------------------------------ матовое стекло (сэмпл обоев)

    private static Bitmap frostBitmap;
    private static BitmapShader frostShader;
    private static float frostFactor = 24f;
    private static boolean frostWarned = false;

    private static ColorFilter saturationFilter;
    private static int saturationFilterKey = -1;

    /** Насыщенность размытого сэмпла обоев (как «vibrancy» в iOS). */
    private static ColorFilter saturationFilter() {
        int sat = clamp(frostSaturation, 0, 200);
        if (sat == 100) return null;
        if (saturationFilter != null && saturationFilterKey == sat) return saturationFilter;
        try {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(sat / 100f);
            saturationFilter = new ColorMatrixColorFilter(cm);
            saturationFilterKey = sat;
        } catch (Throwable t) {
            saturationFilter = null;
            saturationFilterKey = -1;
        }
        return saturationFilter;
    }

    /**
     * Сэмпл обоев, размытый в несколько проходов (каждый проход — уменьшение
     * вдвое с билинейной фильтрацией). Результат — мягкое размытие, похожее на
     * системную прозрачность, без «странных» резких пятен.
     */
    private static synchronized BitmapShader frostShader(Context ctx, View anchor) {
        if (!frostDirty && frostShader != null) return frostShader;
        frostDirty = false;
        try {
            if (ctx == null || anchor == null) return frostShader;

            Drawable d = WallpaperManager.getInstance(ctx).getDrawable();
            Bitmap src = null;
            if (d instanceof BitmapDrawable) {
                src = ((BitmapDrawable) d).getBitmap();
            } else if (d != null) {
                int w = d.getIntrinsicWidth();
                int h = d.getIntrinsicHeight();
                if (w <= 0 || h <= 0) {
                    w = 1080;
                    h = 2400;
                }
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, w, h);
                d.draw(c);
                src = b;
            }
            if (src == null || src.getWidth() < 8 || src.getHeight() < 8) {
                if (!frostWarned) {
                    frostWarned = true;
                    log("обои недоступны (возможно live wallpaper) — использую обычный цвет");
                }
                frostShader = null;
                return null;
            }

            int dispW = anchor.getResources().getDisplayMetrics().widthPixels;
            int dispH = anchor.getResources().getDisplayMetrics().heightPixels;
            if (dispW <= 0 || dispH <= 0) return frostShader;

            float cover = Math.max(dispW / (float) src.getWidth(), dispH / (float) src.getHeight());
            float shownW = src.getWidth() * cover;
            float shownH = src.getHeight() * cover;
            float originX = (dispW - shownW) / 2f;
            float originY = (dispH - shownH) / 2f;

            // хотим примерно frostRadius пикселей экрана на один сэмпл
            float total = Math.max(16f, (float) Math.max(0, frostRadius));
            float f0 = Math.max(4f, total / 4f);
            int bw = Math.max(2, Math.round(dispW / f0));
            int bh = Math.max(2, Math.round(dispH / f0));

            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            Bitmap cur = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(cur);
            Matrix m = new Matrix();
            m.setScale(cover / f0, cover / f0);
            m.postTranslate(originX / f0, originY / f0);
            c.drawBitmap(src, m, p);

            float factor = f0;
            for (int pass = 0; pass < 2; pass++) {
                int prevW = cur.getWidth();
                int nw = Math.max(2, prevW / 2);
                int nh = Math.max(2, cur.getHeight() / 2);
                if (nw < 2 || nh < 2 || nw == prevW) break;
                Bitmap half = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
                Canvas hc = new Canvas(half);
                hc.drawBitmap(cur, null, new Rect(0, 0, nw, nh), p);
                cur = half;
                factor *= (float) prevW / (float) nw;
            }

            frostBitmap = cur;
            frostFactor = factor;
            frostShader = new BitmapShader(cur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            log("матовое стекло: обои " + src.getWidth() + "x" + src.getHeight()
                    + " -> сэмпл " + cur.getWidth() + "x" + cur.getHeight()
                    + " (шаг " + Math.round(factor) + "px, насыщенность " + frostSaturation + "%)");
        } catch (Throwable t) {
            frostShader = null;
            logOnce("frostShader: " + t);
        }
        return frostShader;
    }

    // ------------------------------------------------------------------ режим «фон дока»

    private static final Map<View, Drawable> ORIGINAL_BG =
            Collections.synchronizedMap(new WeakHashMap<View, Drawable>());
    private static final Map<View, Drawable> APPLIED_BG =
            Collections.synchronizedMap(new WeakHashMap<View, Drawable>());
    private static final Map<View, Integer> APPLIED_BG_SIG =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private static final Set<View> ORIGINAL_BG_SAVED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    static void updateHotseatBackground(View hotseat) {
        if (hotseat == null) return;
        if (mode != MODE_BACKGROUND || !enabled) {
            restoreHotseatBackground(hotseat);
            return;
        }
        if (!hotseat.isAttachedToWindow()) return;

        int sig = backgroundSignature(hotseat);
        Integer prevSig = APPLIED_BG_SIG.get(hotseat);
        Drawable applied = APPLIED_BG.get(hotseat);
        if (prevSig != null && applied != null && prevSig.intValue() == sig
                && hotseat.getBackground() == applied) {
            return;
        }

        if (!ORIGINAL_BG_SAVED.contains(hotseat)) {
            ORIGINAL_BG_SAVED.add(hotseat);
            ORIGINAL_BG.put(hotseat, hotseat.getBackground());
        }

        // отрицательные отступы = подложка выходит за границы дока (расширение)
        int topInset = topPadPx + Math.max(0, offsetYPx);
        int bottomInset = bottomPadPx + Math.max(0, -offsetYPx);

        if (!includeSearch) {
            // подложка должна начинаться под строкой поиска (она — ребёнок Hotseat)
            View qsb = readSearchView(hotseat);
            if (qsb != null && qsb.getHeight() > 0) {
                int qsbBottom = Math.round(qsb.getTop() + qsb.getTranslationY()) + qsb.getHeight();
                if (qsbBottom > 0) topInset += qsbBottom;
            }
        }

        int sideInset = sideMarginPx;

        // Свой Drawable вместо InsetDrawable: InsetDrawable отдаёт свои отступы как
        // padding фона, а View добавил бы их к своим — иконки дока могли бы съехать.
        PanelDrawable panel = new PanelDrawable(hotseat, sideInset, topInset, sideInset, bottomInset);
        Drawable result = panel;
        if (keepOriginalBg) {
            Drawable original = ORIGINAL_BG.get(hotseat);
            if (original != null) {
                result = new LayerDrawable(new Drawable[]{original, result});
            }
        }

        try {
            hotseat.setBackground(result);
            APPLIED_BG.put(hotseat, result);
            APPLIED_BG_SIG.put(hotseat, sig);
            log("фон дока применён: insets=" + sideInset + "/" + topInset + "/" + sideInset + "/" + bottomInset
                    + " radius=" + radiusPx + " stroke=" + strokeWidthPx + "/" + strokeAlpha
                    + " color=" + describeColorMode() + " alpha=" + alpha + " frost=" + frosted);
        } catch (Throwable t) {
            logOnce("setBackground: " + t);
        }
    }

    private static void restoreHotseatBackground(View hotseat) {
        Drawable applied = APPLIED_BG.remove(hotseat);
        APPLIED_BG_SIG.remove(hotseat);
        if (applied == null) return;
        try {
            if (hotseat.getBackground() == applied) {
                hotseat.setBackground(ORIGINAL_BG.get(hotseat));
                log("родной фон дока восстановлен");
            }
        } catch (Throwable ignored) {
        }
    }

    private static int backgroundSignature(View hotseat) {
        int h = 17;
        h = 31 * h + radiusPx + 3 * sideMarginPx + 5 * topPadPx + 7 * bottomPadPx + 11 * offsetYPx;
        h = 31 * h + alpha + 7 * colorR + 13 * colorG + 29 * colorB + 37 * colorMode;
        h = 31 * h + 41 * strokeWidthPx + 43 * strokeAlpha + (strokeLighten ? 47 : 0);
        h = 31 * h + (frosted ? 1 : 0) + 3 * frostAlpha + 5 * frostRadius
                + 7 * frostOffsetX + 11 * frostOffsetY + 53 * frostSaturation;
        h = 31 * h + (includeSearch ? 1 : 0) + (keepOriginalBg ? 2 : 0);
        h = 31 * h + hotseat.getWidth() + 3 * hotseat.getHeight() + 5 * hotseat.getPaddingTop();
        View qsb = readSearchView(hotseat);
        if (qsb != null) {
            h = 31 * h + qsb.getHeight() + 3 * qsb.getTop() + 5 * Math.round(qsb.getTranslationY());
        }
        return h;
    }

    // ------------------------------------------------------------------ состояние окна лаунчера

    private static final class PanelState implements ViewTreeObserver.OnPreDrawListener {

        final ViewGroup container;
        WeakReference<View> hotseatRef = new WeakReference<>(null);
        WeakReference<View> searchRef = new WeakReference<>(null);

        final RectF rect = new RectF();
        final RectF lastDrawn = new RectF();

        boolean registered = false;
        boolean visible = false;
        boolean lastDrawnValid = false;
        boolean haveSignature = false;
        int lastSignature = 0;
        int lastMode = -1;

        float pendingAlpha = 1f;
        long lastSearchScan = 0L;
        long lastConfigCheck = 0L;

        PanelState(ViewGroup container) {
            this.container = container;
        }

        void setHotseat(View v) {
            hotseatRef = new WeakReference<>(v);
            View qsb = readSearchView(v);
            searchRef = new WeakReference<>(qsb);
            haveSignature = false;
            lastSearchScan = 0L;
            if (qsb != null) {
                log("строка поиска найдена: " + describe(qsb));
            }
        }

        void start() {
            if (registered) return;
            try {
                container.getViewTreeObserver().addOnPreDrawListener(this);
                registered = true;
            } catch (Throwable t) {
                log("start: " + t);
            }
        }

        void stop() {
            if (!registered) return;
            try {
                container.getViewTreeObserver().removeOnPreDrawListener(this);
            } catch (Throwable ignored) {
            }
            registered = false;
        }

        void forceRedraw() {
            haveSignature = false;
            try {
                container.invalidate();
            } catch (Throwable ignored) {
            }
        }

        // ---------------------------------------------------------- кадр

        @Override
        public boolean onPreDraw() {
            try {
                long now = System.currentTimeMillis();
                if (now - lastConfigCheck > 4000L) {
                    lastConfigCheck = now;
                    loadConfig(container.getContext());
                }
                if (dumpRequested) {
                    dumpRequested = false;
                    dumpTree(container);
                }

                boolean modeChanged = (lastMode != mode);
                if (modeChanged) {
                    lastMode = mode;
                    haveSignature = false;
                }

                if (mode != MODE_LAYER) {
                    // режим «фон дока»: подложка живёт фоном Hotseat
                    View hs = hotseatRef.get();
                    if (hs != null) updateHotseatBackground(hs);
                    visible = false;
                    lastDrawnValid = false;
                    if (modeChanged) container.invalidate();
                    return true;
                }

                visible = computeRect(rect);

                int sig = visible ? signature() : 0;
                if (!haveSignature || sig != lastSignature) {
                    haveSignature = true;
                    lastSignature = sig;
                    invalidatePanel();
                }
            } catch (Throwable t) {
                logOnce("onPreDraw: " + t);
            }
            return true;
        }

        private void invalidatePanel() {
            RectF damage = new RectF();
            boolean has = false;
            if (lastDrawnValid) {
                damage.set(lastDrawn);
                has = true;
            }
            if (visible) {
                if (has) damage.union(rect);
                else {
                    damage.set(rect);
                    has = true;
                }
            }
            if (!has) return;
            damage.inset(-12f, -12f);
            try {
                container.invalidate((int) Math.floor(damage.left), (int) Math.floor(damage.top),
                        (int) Math.ceil(damage.right), (int) Math.ceil(damage.bottom));
            } catch (Throwable ignored) {
            }
        }

        private int signature() {
            int h = 17;
            h = 31 * h + Math.round(rect.left);
            h = 31 * h + Math.round(rect.top);
            h = 31 * h + Math.round(rect.right);
            h = 31 * h + Math.round(rect.bottom);
            h = 31 * h + radiusPx;
            h = 31 * h + Math.round(pendingAlpha * 100f);
            h = 31 * h + alpha + 7 * colorR + 13 * colorG + 29 * colorB + 37 * colorMode;
            h = 31 * h + 41 * strokeWidthPx + 43 * strokeAlpha + (strokeLighten ? 47 : 0);
            h = 31 * h + (frosted ? 1 : 0) + 3 * frostAlpha + 5 * frostRadius
                    + 7 * frostOffsetX + 11 * frostOffsetY + 53 * frostSaturation;
            return h;
        }

        /** Рисуется в контейнер до его детей: в режиме «свой слой» — под всем контентом. */
        void drawOn(Canvas canvas) {
            try {
                if (mode != MODE_LAYER || !visible || rect.width() <= 0.5f || rect.height() <= 0.5f) {
                    lastDrawnValid = false;
                    return;
                }
                int a = clamp(Math.round(alpha * pendingAlpha), 0, 255);
                if (a <= 0 && !frosted && strokeWidthPx <= 0) {
                    lastDrawnValid = false;
                    return;
                }
                drawPanel(canvas, rect, container, container.getContext());
                lastDrawn.set(rect);
                lastDrawnValid = true;
            } catch (Throwable t) {
                logOnce("drawOn: " + t);
            }
        }

        // ---------------------------------------------------------- геометрия (режим «свой слой»)

        private boolean computeRect(RectF out) {
            pendingAlpha = 1f;
            if (!enabled) return false;
            if (!container.isAttachedToWindow()) return false;

            View hs = hotseatRef.get();
            if (hs == null || !hs.isAttachedToWindow()) return false;
            if (hs.getVisibility() != View.VISIBLE) return false;

            int W = container.getWidth();
            int H = container.getHeight();
            if (W <= 0 || H <= 0) return false;

            float a = effectiveAlpha(hs);
            if (a < 0.05f) return false;

            View search = findSearchView();

            RectF dockBox = new RectF();
            if (!unionChildren(hs, search, dockBox)) {
                if (!visualBounds(hs, dockBox)) return false;
            }
            if (dockBox.width() <= 1f || dockBox.height() <= 1f) return false;
            if (dockBox.centerY() < H * 0.55f) return false;   // док должен быть в нижней части экрана

            // отступы могут быть отрицательными — тогда подложка расширяется за границы дока
            float top = dockBox.top - topPadPx;
            float bottom = dockBox.bottom + bottomPadPx;

            if (includeSearch && search != null) {
                RectF sBox = new RectF();
                if (search.getVisibility() == View.VISIBLE
                        && visualBounds(search, sBox)
                        && sBox.height() > 1f
                        && sBox.bottom <= dockBox.top + 32f
                        && sBox.centerY() > H * 0.45f) {
                    top = Math.min(top, sBox.top - topPadPx);
                }
            }

            top += offsetYPx;
            bottom += offsetYPx;

            float margin = clamp(sideMarginPx, -200, Math.max(0, W / 2 - 8));
            float left = margin;
            float right = W - margin;

            top = Math.max(top, 0f);
            bottom = Math.min(bottom, H);
            if (right - left < 16f || bottom - top < 8f) return false;

            out.set(left, top, right, bottom);
            pendingAlpha = a * iconsAlpha(hs);
            if (pendingAlpha < 0.05f) return false;
            return true;
        }

        /** Объединение видимых детей (иконки дока). Строка поиска исключается. */
        private boolean unionChildren(View v, View exclude, RectF out) {
            if (!(v instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) v;
            boolean found = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE) continue;
                if (exclude != null && isSameOrDescendant(ch, exclude)) continue;
                RectF r = new RectF();
                if (!visualBounds(ch, r)) continue;
                if (r.width() <= 1f || r.height() <= 1f) continue;
                if (!found) {
                    out.set(r);
                    found = true;
                } else {
                    out.union(r);
                }
            }
            return found;
        }

        private boolean visualBounds(View v, RectF out) {
            if (!v.isAttachedToWindow() || !container.isAttachedToWindow()) return false;
            try {
                int[] a = new int[2];
                int[] b = new int[2];
                v.getLocationInWindow(a);
                container.getLocationInWindow(b);
                float w = Math.abs(v.getWidth() * v.getScaleX());
                float h = Math.abs(v.getHeight() * v.getScaleY());
                if (w <= 0f || h <= 0f) return false;
                float l = a[0] - b[0];
                float t = a[1] - b[1];
                out.set(l, t, l + w, t + h);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /** Итоговая прозрачность с учётом всех родителей (док плавно исчезает — панель тоже). */
        private float effectiveAlpha(View v) {
            float a = alphaFrom(v, container);
            if (!v.isShown()) return 0f;
            return clampF(a, 0f, 1f);
        }

        /** Иконки иногда гасят по отдельности (setIconsAlpha) — учитываем и это. */
        private float iconsAlpha(View hotseat) {
            if (!(hotseat instanceof ViewGroup)) return 1f;
            ViewGroup g = (ViewGroup) hotseat;
            View search = searchRef.get();
            float best = -1f;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE) continue;
                if (search != null && isSameOrDescendant(ch, search)) continue;
                float a = alphaFrom(ch, hotseat);
                if (ch instanceof ViewGroup) {
                    ViewGroup cg = (ViewGroup) ch;
                    for (int j = 0; j < cg.getChildCount(); j++) {
                        View leaf = cg.getChildAt(j);
                        if (leaf.getVisibility() != View.VISIBLE) continue;
                        a = Math.max(a, alphaFrom(leaf, hotseat));
                    }
                }
                best = Math.max(best, a);
            }
            return best < 0f ? 1f : clampF(best, 0f, 1f);
        }

        private float alphaFrom(View v, View stopBefore) {
            float a = 1f;
            View cur = v;
            int guard = 0;
            while (cur != null && cur != stopBefore && guard++ < 60) {
                if (cur.getVisibility() != View.VISIBLE) return 0f;
                a *= cur.getAlpha();
                ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
            return clampF(a, 0f, 1f);
        }

        // ---------------------------------------------------------- строка поиска

        private View findSearchView() {
            View cached = searchRef.get();
            if (cached != null && cached.isAttachedToWindow() && cached.getVisibility() == View.VISIBLE) {
                return cached;
            }
            long now = System.currentTimeMillis();
            if (now - lastSearchScan < 2000L) return null;
            lastSearchScan = now;

            View hs = hotseatRef.get();
            if (hs != null) {
                View qsb = readSearchView(hs);
                if (qsb != null && qsb.isAttachedToWindow()) {
                    searchRef = new WeakReference<>(qsb);
                    return qsb;
                }
            }

            List<View> candidates = new ArrayList<>();
            collectSearchCandidates(container, candidates, 0, new int[]{0});

            int W = Math.max(1, container.getWidth());
            int H = Math.max(1, container.getHeight());
            RectF dockBox = new RectF();
            boolean haveDock = hs != null && visualBounds(hs, dockBox);

            View best = null;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < candidates.size(); i++) {
                View c = candidates.get(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                if (c.getWidth() < W * 0.25f) continue;
                if (c.getHeight() > H * 0.35f) continue;
                RectF r = new RectF();
                if (!visualBounds(c, r)) continue;
                if (r.centerY() < H * 0.45f) continue;
                float score = haveDock ? Math.abs(dockBox.top - r.bottom) : -r.centerY();
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }

            if (best != cached && (best == null || cached == null)) {
                log("строка поиска: " + (best == null ? "не найдена (подложка только под доком)" : describe(best)));
            }
            searchRef = new WeakReference<>(best);
            return best;
        }

        private void collectSearchCandidates(View v, List<View> out, int depth, int[] counter) {
            if (depth > 14 || counter[0] > 600 || out.size() > 12) return;
            counter[0]++;
            if (isSearchCandidate(v)) out.add(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    collectSearchCandidates(g.getChildAt(i), out, depth + 1, counter);
                }
            }
        }
    }

    /** Фон Hotseat: рисует ту же подложку, но в своих границах. */
    private static final class PanelDrawable extends Drawable {

        private final View anchor;
        private final int insetLeft;
        private final int insetTop;
        private final int insetRight;
        private final int insetBottom;
        private final RectF r = new RectF();

        PanelDrawable(View anchor, int insetLeft, int insetTop, int insetRight, int insetBottom) {
            this.anchor = anchor;
            this.insetLeft = insetLeft;
            this.insetTop = insetTop;
            this.insetRight = insetRight;
            this.insetBottom = insetBottom;
        }

        @Override
        public void draw(Canvas canvas) {
            try {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                r.set(b.left + insetLeft, b.top + insetTop, b.right - insetRight, b.bottom - insetBottom);
                drawPanel(canvas, r, anchor, anchor != null ? anchor.getContext() : null);
            } catch (Throwable t) {
                logOnce("PanelDrawable: " + t);
            }
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ поиск строки поиска

    private static boolean isSearchCandidate(View v) {
        String cn = v.getClass().getName().toLowerCase(Locale.US);
        if (cn.contains("qsb") || cn.contains("searchbar") || cn.contains("search_bar")
                || cn.contains("searchcontainer") || cn.contains("search_container")
                || cn.contains("homesearch") || cn.contains("hotseatsearch")) {
            return true;
        }
        int id = v.getId();
        if (id != View.NO_ID) {
            try {
                String en = v.getResources().getResourceEntryName(id).toLowerCase(Locale.US);
                if (en.contains("qsb") || en.contains("search")) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isSameOrDescendant(View v, View ancestor) {
        View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 60) {
            if (cur == ancestor) return true;
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private static String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getName());
        int id = v.getId();
        if (id != View.NO_ID) {
            try {
                sb.append('#').append(v.getResources().getResourceEntryName(id));
            } catch (Throwable ignored) {
            }
        }
        sb.append(" [").append(v.getWidth()).append('x').append(v.getHeight()).append(']');
        return sb.toString();
    }

    private static String describeColorMode() {
        switch (colorMode) {
            case 1: return "системный accent1-100";
            case 2: return "системный accent1-200";
            case 3: return "системный accent1-500";
            case 4: return "системный neutral1-100";
            case 5: return "системный neutral1-500";
            case 6: return "системный accent3-100";
            default: return "вручную " + colorR + "," + colorG + "," + colorB;
        }
    }

    // ------------------------------------------------------------------ дамп дерева (диагностика)

    private static void dumpTree(ViewGroup root) {
        try {
            List<String> lines = new ArrayList<>();
            collectDump(root, 0, lines);
            log("================ ДАМП ДЕРЕВА ЛАУНЧЕРА (" + lines.size() + " строк) ================");
            for (int i = 0; i < lines.size(); i++) {
                XposedBridge.log(TAG + lines.get(i));
            }
            log("================ КОНЕЦ ДАМПА ================");
        } catch (Throwable t) {
            log("dumpTree: " + t);
        }
    }

    private static void collectDump(View v, int depth, List<String> out) {
        if (out.size() > 500 || depth > 14) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        int id = v.getId();
        if (id != View.NO_ID) {
            try {
                sb.append('#').append(v.getResources().getResourceEntryName(id));
            } catch (Throwable ignored) {
            }
        }
        sb.append(" vis=").append(v.getVisibility())
                .append(" a=").append(fmt(v.getAlpha()))
                .append(" size=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" pos=").append(v.getLeft()).append(',').append(v.getTop())
                .append(" tr=").append(fmt(v.getTranslationX())).append(',').append(fmt(v.getTranslationY()))
                .append(" sc=").append(fmt(v.getScaleX())).append(',').append(fmt(v.getScaleY()));
        if (v.isAttachedToWindow()) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            sb.append(" win=").append(loc[0]).append(',').append(loc[1]);
        }
        if (isSearchCandidate(v)) sb.append("   <== КАНДИДАТ В СТРОКУ ПОИСКА");
        out.add(sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectDump(g.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static String fmt(float f) {
        return String.format(Locale.US, "%.2f", f);
    }

    // ------------------------------------------------------------------ конфиг

    static void loadConfig(Context ctx) {
        boolean fromGlobal = false;
        if (ctx != null) {
            try {
                String cfg = Settings.Global.getString(ctx.getContentResolver(), CFG_KEY);
                if (cfg != null && !cfg.isEmpty()) {
                    parseConfig(cfg);
                    fromGlobal = true;
                }
            } catch (Throwable t) {
                logOnce("Settings.Global: " + t);
            }
        }
        if (fromGlobal) return;

        String[] paths = {
                "/data/user_de/0/" + currentPackage + "/lockcenter.conf",
                "/data/data/" + currentPackage + "/lockcenter.conf"
        };
        for (int i = 0; i < paths.length; i++) {
            try {
                File f = new File(paths[i]);
                if (!f.exists() || !f.canRead()) continue;
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line;
                    int n = 0;
                    while ((line = r.readLine()) != null && n++ < 200) {
                        int eq = line.indexOf('=');
                        if (eq <= 0) continue;
                        applyKeyValue(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
                return;
            } catch (Throwable t) {
                logOnce("conf " + paths[i] + ": " + t);
            }
        }
    }

    private static void parseConfig(String cfg) {
        String[] pairs = cfg.contains(",") ? cfg.split(",") : cfg.split(";");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq <= 0) continue;
            applyKeyValue(pairs[i].substring(0, eq).trim(), pairs[i].substring(eq + 1).trim());
        }
    }

    private static void applyKeyValue(String k, String v) {
        try {
            if ("dock_enabled".equals(k)) enabled = bool(v);
            else if ("dock_mode".equals(k)) mode = clamp(intOf(v, mode), 0, 1);
            else if ("dock_keep_original_bg".equals(k)) keepOriginalBg = bool(v);
            else if ("dock_include_search".equals(k)) includeSearch = bool(v);
            else if ("dock_radius_px".equals(k)) radiusPx = clamp(intOf(v, radiusPx), 0, 200);
            else if ("dock_side_margin_px".equals(k)) sideMarginPx = clamp(intOf(v, sideMarginPx), -200, 200);
            else if ("dock_top_pad_px".equals(k)) topPadPx = clamp(intOf(v, topPadPx), -200, 200);
            else if ("dock_bottom_pad_px".equals(k)) bottomPadPx = clamp(intOf(v, bottomPadPx), -200, 200);
            else if ("dock_offset_y_px".equals(k)) offsetYPx = clamp(intOf(v, offsetYPx), -400, 400);
            else if ("dock_alpha".equals(k)) alpha = clamp(intOf(v, alpha), 0, 255);
            else if ("dock_color_mode".equals(k)) colorMode = clamp(intOf(v, colorMode), 0, 6);
            else if ("dock_color_r".equals(k)) colorR = clamp(intOf(v, colorR), 0, 255);
            else if ("dock_color_g".equals(k)) colorG = clamp(intOf(v, colorG), 0, 255);
            else if ("dock_color_b".equals(k)) colorB = clamp(intOf(v, colorB), 0, 255);
            else if ("dock_stroke_width_px".equals(k)) strokeWidthPx = clamp(intOf(v, strokeWidthPx), 0, 16);
            else if ("dock_stroke_alpha".equals(k)) strokeAlpha = clamp(intOf(v, strokeAlpha), 0, 255);
            else if ("dock_stroke_lighten".equals(k)) strokeLighten = bool(v);
            else if ("dock_frosted".equals(k)) {
                boolean nv = bool(v);
                if (nv != frosted) frostDirty = true;
                frosted = nv;
            } else if ("dock_frost_radius".equals(k)) {
                int nv = clamp(intOf(v, frostRadius), 0, 80);
                if (nv != frostRadius) frostDirty = true;
                frostRadius = nv;
            } else if ("dock_frost_alpha".equals(k)) frostAlpha = clamp(intOf(v, frostAlpha), 0, 255);
            else if ("dock_frost_saturation".equals(k)) frostSaturation = clamp(intOf(v, frostSaturation), 0, 200);
            else if ("dock_frost_offset_x".equals(k)) frostOffsetX = clamp(intOf(v, frostOffsetX), -1000, 1000);
            else if ("dock_frost_offset_y".equals(k)) frostOffsetY = clamp(intOf(v, frostOffsetY), -1000, 1000);
        } catch (Throwable ignored) {
        }
    }

    private static boolean bool(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.US);
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static int intOf(String v, int def) {
        try {
            return (int) Float.parseFloat(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clampF(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------ приём настроек на лету

    private static void registerReceiver(Context ctx) {
        if (receiverRegistered || ctx == null) return;
        receiverRegistered = true;
        try {
            IntentFilter filter = new IntentFilter(ACTION);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        loadConfig(c);
                        if (intent != null && intent.getBooleanExtra("dock_debug_dump", false)) {
                            dumpRequested = true;
                        }
                        invalidateAll();
                        logSettings("настройки обновлены");
                    } catch (Throwable t) {
                        log("onReceive: " + t);
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            log("registerReceiver: " + t);
        }
    }

    private static void invalidateAll() {
        frostDirty = true;
        APPLIED_BG_SIG.clear();
        synchronized (STATES) {
            for (PanelState s : STATES.values()) {
                s.forceRedraw();
                View hs = s.hotseatRef.get();
                if (hs != null) updateHotseatBackground(hs);
            }
        }
    }

    // ------------------------------------------------------------------ лог

    private static void logSettings(String prefix) {
        log(prefix + ": режим=" + (mode == MODE_BACKGROUND ? "фон дока" : "свой слой")
                + " enabled=" + enabled + " radius=" + radiusPx + " stroke=" + strokeWidthPx + "/" + strokeAlpha
                + " margin=" + sideMarginPx + " pads=" + topPadPx + "/" + bottomPadPx
                + " alpha=" + alpha + " color=" + describeColorMode()
                + " frost=" + frosted + "/" + frostRadius + "/" + frostAlpha + "/" + frostSaturation
                + " search=" + includeSearch);
    }

    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logOnce(String msg) {
        try {
            if (LOGGED_ONCE.size() < 300 && LOGGED_ONCE.add(msg)) log(msg);
        } catch (Throwable ignored) {
        }
    }
}
