package com.anton.lockcenter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // ======== НАСТРОЙКИ ЭКРАНА БЛОКИРОВКИ ========
    public static volatile float clockScaleX = 1.60f;
    public static volatile float clockScaleY = 1.30f;
    public static volatile int dateExtraGapPx = -20;
    public static volatile float dateScaleX = 1.30f;
    public static volatile float dateScaleY = 1.15f;
    public static volatile float largeClockScaleX = 1.35f;
    public static volatile float largeClockScaleY = 1.05f;
    public static volatile int cardTopMarginPx = -50;
    public static volatile int notifTopMarginPx = 0;

    // ======== НАСТРОЙКИ ДОК-ПАНЕЛИ (PIXEL LAUNCHER) ========
    public static volatile boolean dockEnabled = true;
    public static volatile int dockRadius = 80;
    public static volatile int dockMarginH = 35;
    public static volatile int dockPaddingTop = 15;
    public static volatile int dockPaddingBottom = 15;
    public static volatile int dockAlpha = 35; // прозрачность в % (10 - 90)

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final String CLOCK_SUFFIX = ".FlexClockTextView";
    private static final String LARGE_CLOCK_SUFFIX = ".FlexClockViewGroup";
    private static final String ROOT_SUFFIX = ".KeyguardRootView";

    private static int placeholderId = 0;
    private static int cardId = 0;
    private static boolean receiverRegistered = false;
    private static long lastCheckTime = 0;

    // Кисти для отрисовки дока
    private static final Paint dockBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint dockStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF dockRect = new RectF();

    public static void loadConfig(Context context) {
        if (context != null) {
            try {
                String cfg = Settings.Global.getString(context.getContentResolver(), "lockcenter_cfg");
                if (cfg != null && !cfg.isEmpty()) {
                    parseConfig(cfg);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        File f = new File("/data/user_de/0/com.android.systemui/lockcenter.conf");
        if (!f.exists()) f = new File("/data/data/com.android.systemui/lockcenter.conf");
        if (f.exists() && f.canRead()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        applyKeyValue(parts[0].trim(), parts[1].trim());
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void parseConfig(String cfg) {
        String[] pairs = cfg.contains(",") ? cfg.split(",") : cfg.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                applyKeyValue(kv[0].trim(), kv[1].trim());
            }
        }
    }

    private static void applyKeyValue(String k, String v) {
        try {
            if ("clock_scale_x".equals(k)) clockScaleX = Float.parseFloat(v);
            else if ("clock_scale_y".equals(k)) clockScaleY = Float.parseFloat(v);
            else if ("date_extra_gap_px".equals(k)) dateExtraGapPx = Integer.parseInt(v);
            else if ("date_scale_x".equals(k)) dateScaleX = Float.parseFloat(v);
            else if ("date_scale_y".equals(k)) dateScaleY = Float.parseFloat(v);
            else if ("large_clock_scale_x".equals(k)) largeClockScaleX = Float.parseFloat(v);
            else if ("large_clock_scale_y".equals(k)) largeClockScaleY = Float.parseFloat(v);
            else if ("card_top_margin_px".equals(k)) cardTopMarginPx = Integer.parseInt(v);
            else if ("notif_top_margin_px".equals(k)) notifTopMarginPx = Integer.parseInt(v);
            else if ("dock_enabled".equals(k)) dockEnabled = Boolean.parseBoolean(v);
            else if ("dock_radius".equals(k)) dockRadius = Integer.parseInt(v);
            else if ("dock_margin_h".equals(k)) dockMarginH = Integer.parseInt(v);
            else if ("dock_padding_top".equals(k)) dockPaddingTop = Integer.parseInt(v);
            else if ("dock_padding_bottom".equals(k)) dockPaddingBottom = Integer.parseInt(v);
            else if ("dock_alpha".equals(k)) dockAlpha = Integer.parseInt(v);
        } catch (Throwable ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.systemui".equals(lpparam.packageName)) {
            initSystemUI(lpparam);
        } else if ("com.google.android.apps.nexuslauncher".equals(lpparam.packageName)
                || "com.android.launcher3".equals(lpparam.packageName)) {
            initLauncher(lpparam);
        }
    }

    // =========================================================================
    //                            PIXEL LAUNCHER (DOCK)
    // =========================================================================

    private static void initLauncher(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        Class<?> hotseatClass = XposedHelpers.findClassIfExists("com.android.launcher3.Hotseat", cl);
        if (hotseatClass == null) return;

        // Включаем отрисовку и слушаем настройки
        XposedBridge.hookAllConstructors(hotseatClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final View hotseat = (View) param.thisObject;
                hotseat.setWillNotDraw(false);
                loadConfig(hotseat.getContext());

                try {
                    IntentFilter filter = new IntentFilter("com.anton.lockcenter.UPDATE_SETTINGS");
                    hotseat.getContext().registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            loadConfig(context);
                            hotseat.invalidate();
                        }
                    }, filter, Context.RECEIVER_EXPORTED);
                } catch (Throwable ignored) {}
            }
        });

        // Рисуем подложку ДО отрисовки иконок и поисковой строки
        XposedHelpers.findAndHookMethod(hotseatClass, "dispatchDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!dockEnabled) return;
                Canvas canvas = (Canvas) param.args[0];
                ViewGroup hotseat = (ViewGroup) param.thisObject;
                drawIosDock(canvas, hotseat);
            }
        });
    }

    private static void drawIosDock(Canvas canvas, ViewGroup hotseat) {
        int width = hotseat.getWidth();
        int height = hotseat.getHeight();
        if (width <= 0 || height <= 0 || width < height) return; // Пропускаем ландшафт

        int childCount = hotseat.getChildCount();
        int minTop = Integer.MAX_VALUE;
        int maxBottom = Integer.MIN_VALUE;

        // Находим реальные границы иконок и строки поиска внутри Hotseat
        for (int i = 0; i < childCount; i++) {
            View child = hotseat.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getHeight() > 0) {
                minTop = Math.min(minTop, child.getTop());
                maxBottom = Math.max(maxBottom, child.getBottom());
            }
        }

        if (minTop >= maxBottom) {
            minTop = hotseat.getPaddingTop();
            maxBottom = height - hotseat.getPaddingBottom();
        }

        float left = dockMarginH;
        float right = width - dockMarginH;
        float top = minTop - dockPaddingTop;
        float bottom = maxBottom + dockPaddingBottom;

        if (left >= right || top >= bottom) return;

        dockRect.set(left, top, right, bottom);

        boolean isDark = (hotseat.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int alpha255 = Math.round((dockAlpha / 100f) * 255);

        // Матовый полупрозрачный фон (как на iOS)
        dockBgPaint.setStyle(Paint.Style.FILL);
        if (isDark) {
            dockBgPaint.setColor(Color.argb(alpha255, 38, 42, 46));
        } else {
            dockBgPaint.setColor(Color.argb(alpha255, 255, 255, 255));
        }
        canvas.drawRoundRect(dockRect, dockRadius, dockRadius, dockBgPaint);

        // Тонкий стеклянный кант по контуру
        dockStrokePaint.setStyle(Paint.Style.STROKE);
        dockStrokePaint.setStrokeWidth(2f);
        if (isDark) {
            dockStrokePaint.setColor(Color.argb(Math.min(255, alpha255 + 25), 255, 255, 255));
        } else {
            dockStrokePaint.setColor(Color.argb(Math.min(255, alpha255 + 20), 0, 0, 0));
        }
        canvas.drawRoundRect(dockRect, dockRadius, dockRadius, dockStrokePaint);
    }

    // =========================================================================
    //                            SYSTEM UI (LOCKSCREEN)
    // =========================================================================

    private static void initSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        loadConfig(null);

        try {
            hookKeyguard(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookDateTranslation(cl);
            hookDateTranslationY(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookDateLayout(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookPreDraw(cl, "com.android.systemui.statusbar.NotificationShelf", true);
            hookPreDraw(cl, "com.android.systemui.statusbar.phone.NotificationIconContainer", false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void registerReceiverIfNeeded(Context context) {
        if (receiverRegistered || context == null) return;
        try {
            IntentFilter filter = new IntentFilter("com.anton.lockcenter.UPDATE_SETTINGS");
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent != null) {
                        loadConfig(ctx);
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private static boolean isDateOnly(View v) {
        return v != null && v.getClass().getName().contains("DateSmartspaceView");
    }

    private static boolean isSmartspaceCard(View v) {
        return v != null && v.getClass().getName().contains("BcSmartspaceView");
    }

    private static View findSmallClock(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c.getClass().getName().endsWith(CLOCK_SUFFIX)) {
                return c;
            }
        }
        return null;
    }

    private static float dateContentDelta(View date) {
        if (!(date instanceof ViewGroup) || date.getWidth() <= 0) return 0f;
        float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE};
        collectTextBounds((ViewGroup) date, 0f, b);
        if (b[1] < b[0]) return 0f;
        return date.getWidth() / 2f - (b[0] + b[1]) / 2f;
    }

    private static void collectTextBounds(ViewGroup g, float offX, float[] b) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) continue;
            float l = offX + c.getLeft();
            if (c instanceof TextView) {
                b[0] = Math.min(b[0], l);
                b[1] = Math.max(b[1], l + c.getWidth());
            } else if (c instanceof ViewGroup) {
                collectTextBounds((ViewGroup) c, l, b);
            }
        }
    }

    private static void hookDateLayout(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedHelpers.findAndHookMethod(viewClass, "layout",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (!isDateOnly(v)) return;

                        View parent = (View) v.getParent();
                        if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                        int l = (Integer) param.args[0];
                        int r = (Integer) param.args[2];
                        int width = r - l;
                        int screenWidth = v.getResources().getDisplayMetrics().widthPixels;

                        if (width > 0 && width < screenWidth - 100) {
                            float rootShift = parent.getLeft() + parent.getTranslationX();
                            int targetCenter = (int) ((screenWidth / 2f) - rootShift);
                            int targetLeft = targetCenter - (width / 2) + Math.round(dateContentDelta(v));

                            param.args[0] = targetLeft;
                            param.args[2] = targetLeft + width;
                        }
                    }
                });
    }

    private static void hookDateTranslation(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedBridge.hookAllMethods(viewClass, "setTranslationX", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (!isDateOnly(v)) return;

                View parent = (View) v.getParent();
                if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
                int w = v.getWidth();
                if (w <= 0 || w >= screenWidth - 100) return;

                float rootShift = parent.getLeft() + parent.getTranslationX();
                float targetCenterInRoot = (screenWidth / 2f) - rootShift;
                int currentLeft = v.getLeft();
                float targetTx = (targetCenterInRoot - w / 2f + dateContentDelta(v)) - currentLeft;

                param.args[0] = targetTx;
            }
        });
    }

    private static void hookDateTranslationY(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedBridge.hookAllMethods(viewClass, "setTranslationY", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                if (!isDateOnly(v)) return;

                View parent = (View) v.getParent();
                if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                ViewGroup root = (ViewGroup) parent;
                View smallClock = findSmallClock(root);

                if (smallClock != null && smallClock.getVisibility() == View.VISIBLE && smallClock.getAlpha() > 0.05f) {
                    float clockTy = smallClock.getTranslationY();
                    param.args[0] = clockTy + (float) dateExtraGapPx;
                }
            }
        });
    }

    private static void hookKeyguard(ClassLoader cl) {
        Class<?> constraintLayout = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintLayout", cl);
        Class<?> rootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);

        if (constraintLayout != null) {
            XC_MethodHook before = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null && param.thisObject.getClass().getName().endsWith(ROOT_SUFFIX)) {
                        fixKeyguard((ViewGroup) param.thisObject, true);
                    }
                }
            };
            XposedBridge.hookAllMethods(constraintLayout, "onMeasure", before);
            if (rootClass != null) {
                XposedBridge.hookAllMethods(rootClass, "onMeasure", before);
            }
        }

        if (rootClass != null) {
            XposedBridge.hookAllConstructors(rootClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final ViewGroup root = (ViewGroup) param.thisObject;
                    loadConfig(root.getContext());
                    registerReceiverIfNeeded(root.getContext());
                    if (!HOOKED.add(root)) return;
                    root.getViewTreeObserver().addOnPreDrawListener(
                            new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    try {
                                        long now = System.currentTimeMillis();
                                        if (now - lastCheckTime > 2000) {
                                            lastCheckTime = now;
                                            loadConfig(root.getContext());
                                        }

                                        fixKeyguard(root, false);
                                        forceCenterDateAndClock(root);
                                    } catch (Throwable ignored) {}
                                    return true;
                                }
                            });
                }
            });
        }
    }

    private static void fixKeyguard(ViewGroup root, boolean inMeasure) {
        View clock = null;
        View dateView = null;
        View cardView = null;
        View largeClock = null;

        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;
            else if (n.endsWith(LARGE_CLOCK_SUFFIX)) largeClock = c;
            else if (isDateOnly(c)) dateView = c;
            else if (isSmartspaceCard(c)) cardView = c;
        }

        if (largeClock != null) {
            attachLargeClockScaler(largeClock);
        }

        boolean isSmallClockActive = (clock != null && clock.getVisibility() == View.VISIBLE && clock.getAlpha() > 0.05f);

        if (clock != null) {
            if (clock.getId() == View.NO_ID) {
                clock.setId(View.generateViewId());
            }
            enforceCentered(clock, root, inMeasure);
            attachClockScaler(clock);
        }

        if (dateView != null) {
            attachDateScaler(dateView);

            if (dateView.getId() == View.NO_ID) {
                dateView.setId(View.generateViewId());
            }

            if (isSmallClockActive && clock != null) {
                ViewGroup.LayoutParams lp = dateView.getLayoutParams();
                if (lp != null) {
                    int cid = clock.getId();

                    setInt(lp, "baselineToBaseline", UNSET);
                    setInt(lp, "baselineToTop", UNSET);
                    setInt(lp, "baselineToBottom", UNSET);

                    setInt(lp, "startToEnd", UNSET);
                    setInt(lp, "leftToRight", UNSET);
                    setInt(lp, "topToTop", UNSET);
                    setInt(lp, "bottomToTop", UNSET);
                    setInt(lp, "bottomToBottom", UNSET);

                    setInt(lp, "topToBottom", cid);

                    setInt(lp, "startToStart", PARENT);
                    setInt(lp, "endToEnd", PARENT);
                    setFloat(lp, "horizontalBias", 0.5f);

                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                        m.setMarginStart(0);
                        m.setMarginEnd(0);
                        m.leftMargin = 0;
                        m.rightMargin = 0;
                        m.topMargin = 20;
                    }

                    apply(dateView, lp, root, inMeasure);
                }
            }
        }

        if (cardView != null && dateView != null) {
            if (cardView.getId() == View.NO_ID) {
                cardView.setId(View.generateViewId());
            }

            ViewGroup.LayoutParams lp = cardView.getLayoutParams();
            if (lp != null && isSmallClockActive) {
                int did = dateView.getId();

                setInt(lp, "baselineToBaseline", UNSET);
                setInt(lp, "startToEnd", UNSET);
                setInt(lp, "leftToRight", UNSET);
                setInt(lp, "topToTop", UNSET);
                setInt(lp, "topToBottom", did);

                setInt(lp, "startToStart", PARENT);
                setInt(lp, "endToEnd", PARENT);
                setFloat(lp, "horizontalBias", 0.5f);

                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    m.topMargin = 12 + cardTopMarginPx;
                }

                apply(cardView, lp, root, inMeasure);
            }
        }

        if (isSmallClockActive) {
            int anchorId = View.NO_ID;
            if (cardView != null && cardView.getVisibility() == View.VISIBLE && cardView.getHeight() > 0) {
                anchorId = cardView.getId();
            } else if (dateView != null) {
                anchorId = dateView.getId();
            }

            if (anchorId != View.NO_ID) {
                pointPlaceholderAt(root, anchorId, inMeasure);
            }
        }
    }

    private static void forceCenterDateAndClock(ViewGroup root) {
        int displayWidth = root.getResources().getDisplayMetrics().widthPixels;
        if (displayWidth <= 0) return;

        float rootShift = root.getLeft() + root.getTranslationX();
        float targetCenterInRoot = (displayWidth / 2f) - rootShift;

        View clock = null;
        View dateView = null;
        View cardView = null;
        View largeClock = null;

        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;

            String n = v.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = v;
            else if (n.endsWith(LARGE_CLOCK_SUFFIX)) largeClock = v;
            else if (isDateOnly(v)) dateView = v;
            else if (isSmartspaceCard(v)) cardView = v;

            if (isDateOnly(v) || n.endsWith(CLOCK_SUFFIX)) {
                int w = v.getWidth();
                if (w > 0 && w < displayWidth - 100) {
                    int left = v.getLeft();
                    float targetTx = (targetCenterInRoot - w / 2f
                            + (isDateOnly(v) ? dateContentDelta(v) : 0f)) - left;
                    if (Math.abs(v.getTranslationX() - targetTx) > 0.5f) {
                        v.setTranslationX(targetTx);
                    }
                }
            }
        }

        boolean isSmallClockActive = (clock != null && clock.getVisibility() == View.VISIBLE && clock.getAlpha() > 0.05f);

        if (isSmallClockActive) {
            float clockTy = clock.getTranslationY();

            if (dateView != null && dateView.getVisibility() == View.VISIBLE) {
                float desiredDateTy = clockTy + (float) dateExtraGapPx;
                if (Math.abs(dateView.getTranslationY() - desiredDateTy) > 0.5f) {
                    dateView.setTranslationY(desiredDateTy);
                }
            }

            if (cardView != null && cardView.getVisibility() == View.VISIBLE) {
                float desiredCardTy = clockTy + (float) (dateExtraGapPx + cardTopMarginPx);
                if (Math.abs(cardView.getTranslationY() - desiredCardTy) > 0.5f) {
                    cardView.setTranslationY(desiredCardTy);
                }
            }

            if (placeholderId != 0) {
                View ph = root.findViewById(placeholderId);
                if (ph != null) {
                    float baseOffset = clockTy + dateExtraGapPx + (cardView != null && cardView.getVisibility() == View.VISIBLE ? cardTopMarginPx : 0);
                    float desiredPhTy = baseOffset + notifTopMarginPx;
                    if (Math.abs(ph.getTranslationY() - desiredPhTy) > 0.5f) {
                        ph.setTranslationY(desiredPhTy);
                    }
                }
            }
        } else {
            // Режим больших часов: отодвигаем дату вниз под увеличенные цифры
            if (dateView != null && dateView.getVisibility() == View.VISIBLE) {
                float desiredDateTy = 0f;
                if (largeClock != null && largeClock.getVisibility() == View.VISIBLE && largeClock.getHeight() > 0) {
                    float largeExpandDown = (largeClock.getHeight() * (largeClockScaleY - 1f)) / 2f;
                    desiredDateTy = largeClock.getTranslationY() + Math.max(0f, largeExpandDown) + 20f;
                }
                if (Math.abs(dateView.getTranslationY() - desiredDateTy) > 0.5f) {
                    dateView.setTranslationY(desiredDateTy);
                }
            }
        }
    }

    private static void attachLargeClockScaler(final View large) {
        if (!HOOKED.add(large)) return;
        large.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (large.getVisibility() != View.VISIBLE || large.getWidth() == 0) return true;
                            large.setPivotX(large.getWidth() / 2f);
                            large.setPivotY(large.getHeight() / 2f);
                            float cx = large.getScaleX();
                            if (cx != lastX || Math.abs(lastX - largeClockScaleX) > 0.01f) {
                                lastX = largeClockScaleX;
                                large.setScaleX(lastX);
                            }
                            float cy = large.getScaleY();
                            if (cy != lastY || Math.abs(lastY - largeClockScaleY) > 0.01f) {
                                lastY = largeClockScaleY;
                                large.setScaleY(lastY);
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
    }

    private static void attachDateScaler(final View date) {
        if (!HOOKED.add(date)) return;
        date.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (date.getVisibility() != View.VISIBLE || date.getWidth() == 0) return true;
                            date.setPivotX(date.getWidth() / 2f - dateContentDelta(date));
                            date.setPivotY(date.getHeight() / 2f);
                            float cx = date.getScaleX();
                            if (cx != lastX || Math.abs(lastX - dateScaleX) > 0.01f) {
                                lastX = dateScaleX;
                                date.setScaleX(lastX);
                            }
                            float cy = date.getScaleY();
                            if (cy != lastY || Math.abs(lastY - dateScaleY) > 0.01f) {
                                lastY = dateScaleY;
                                date.setScaleY(lastY);
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
    }

    private static void attachClockScaler(final View clock) {
        if (!HOOKED.add(clock)) return;
        clock.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (clock.getVisibility() != View.VISIBLE || clock.getWidth() == 0) return true;
                            clock.setPivotX(clock.getWidth() / 2f);
                            clock.setPivotY(clock.getHeight() / 2f);
                            float cx = clock.getScaleX();
                            if (cx != lastX || Math.abs(lastX - clockScaleX) > 0.01f) {
                                lastX = clockScaleX;
                                clock.setScaleX(lastX);
                            }
                            float cy = clock.getScaleY();
                            if (cy != lastY || Math.abs(lastY - clockScaleY) > 0.01f) {
                                lastY = clockScaleY;
                                clock.setScaleY(lastY);
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                });
    }

    private static void pointPlaceholderAt(ViewGroup root, int anchorId, boolean inMeasure) {
        if (anchorId == View.NO_ID) return;
        if (placeholderId == 0) {
            placeholderId = root.getResources().getIdentifier("nssl_placeholder", "id", "com.android.systemui");
        }
        if (placeholderId == 0) return;
        View ph = root.findViewById(placeholderId);
        if (ph == null || ph.getLayoutParams() == null) return;
        ViewGroup.LayoutParams lp = ph.getLayoutParams();
        setInt(lp, "topToBottom", anchorId);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.topMargin = notifTopMarginPx;
        }
        apply(ph, lp, root, inMeasure);
    }

    private static void enforceCentered(View v, ViewGroup parent, boolean inMeasure) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || !lp.getClass().getName().contains("ConstraintLayout")) return;
        String[] clear = {"leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
                "startToEnd", "endToStart"};
        for (String f : clear) setInt(lp, f, UNSET);
        setInt(lp, "startToStart", PARENT);
        setInt(lp, "endToEnd", PARENT);
        setFloat(lp, "horizontalBias", 0.5f);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMarginStart(0);
            m.setMarginEnd(0);
            m.leftMargin = 0;
            m.rightMargin = 0;
        }
        apply(v, lp, parent, inMeasure);
    }

    private static void apply(View v, ViewGroup.LayoutParams lp, ViewGroup parent, boolean inMeasure) {
        if (inMeasure) {
            try {
                lp.getClass().getMethod("resolveLayoutDirection", int.class)
                        .invoke(lp, parent.getLayoutDirection());
            } catch (Throwable ignored) {}
            v.forceLayout();
        } else {
            v.setLayoutParams(lp);
        }
    }

    private static boolean setInt(Object lp, String name, int value) {
        try {
            Field f = lp.getClass().getField(name);
            if (f.getInt(lp) == value) return false;
            f.setInt(lp, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setFloat(Object lp, String name, float value) {
        try {
            Field f = lp.getClass().getField(name);
            if (f.getFloat(lp) == value) return false;
            f.setFloat(lp, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------ shelf pill + AOD icons

    private static void hookPreDraw(ClassLoader cl, String className, final boolean isShelf) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) return;
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final View v = (View) param.thisObject;
                if (!HOOKED.add(v)) return;
                v.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                try {
                                    int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
                                    if (isShelf) {
                                        centerShelf((ViewGroup) v);
                                    } else if (isAodContainer(v)) {
                                        centerAodIcons((ViewGroup) v, screenWidth);
                                    }
                                } catch (Throwable ignored) {}
                                return true;
                            }
                        });
            }
        });
    }

    private static boolean isAodContainer(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        try {
            return "aod_notification_icon_container".equals(
                    v.getResources().getResourceEntryName(id));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void centerAodIcons(ViewGroup c, int screenWidth) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;

        int displayWidth = c.getResources().getDisplayMetrics().widthPixels;
        int[] cLoc = new int[2];
        c.getLocationOnScreen(cLoc);

        float minL = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0
                    || ch.getAlpha() < 0.05f) continue;
            float l = ch.getLeft() + ch.getTranslationX();
            minL = Math.min(minL, l);
            maxR = Math.max(maxR, l + ch.getWidth());
            n++;
        }
        if (n == 0) return;

        float groupCenterOnScreen = cLoc[0] + minL + (maxR - minL) / 2f;
        float targetCenter = displayWidth / 2f;
        float shift = targetCenter - groupCenterOnScreen;

        if (Math.abs(shift) > 0.5f) {
            for (int i = 0; i < c.getChildCount(); i++) {
                View ch = c.getChildAt(i);
                ch.setTranslationX(ch.getTranslationX() + shift);
            }
        }
    }

    private static void centerShelf(ViewGroup shelf) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getWidth() == 0
                || shelf.getAlpha() < 0.01f) return;
        View parent = (View) shelf.getParent();
        if (parent == null) return;

        int full = shelf.getWidth();
        int pill = pillWidth(shelf);
        float target = 0f;
        if (pill > 0 && full - pill > 100) {
            target = (parent.getWidth() - pill) / 2f - shelf.getLeft();
        }
        if (Math.abs(shelf.getTranslationX() - target) > 1f) {
            shelf.setTranslationX(target);
        }
    }

    private static int pillWidth(ViewGroup shelf) {
        View bg = null;
        ViewGroup icons = null;
        for (int i = 0; i < shelf.getChildCount(); i++) {
            View c = shelf.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith("NotificationShelfBackgroundView")) bg = c;
            else if (n.endsWith("NotificationShelfIconContainer") && c instanceof ViewGroup) {
                icons = (ViewGroup) c;
            }
        }
        if (bg != null) {
            try {
                int w = XposedHelpers.getIntField(bg, "mActualWidth");
                if (w > 0) return w;
            } catch (Throwable ignored) {}
        }
        if (icons != null) {
            float minL = Float.MAX_VALUE;
            float maxR = -Float.MAX_VALUE;
            int n = 0;
            for (int i = 0; i < icons.getChildCount(); i++) {
                View ch = icons.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0) continue;
                float l = ch.getLeft() + ch.getTranslationX();
                minL = Math.min(minL, l);
                maxR = Math.max(maxR, l + ch.getWidth());
                n++;
            }
            if (n > 0) return (int) (maxR + minL);
        }
        return 0;
    }
}
