package com.anton.lockcenter;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

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

    // ======== РАЗМЕР ЧАСОВ ========
    private static final float CLOCK_SCALE_X = 1.65f; // ширина 1.5
    private static final float CLOCK_SCALE_Y = 1.35f; // высота 1.15
    // ==============================

    // ======== ОТСТУП КАРТОЧКИ («Фонарик включен» и т.п.) ОТ ДАТЫ, px ========
    // 0 = сразу под рамкой даты (визуально ~50 px от текста). Увеличь, если хочешь больше воздуха.
    private static final int CARD_TOP_MARGIN_PX = 0;
    // ==========================================================================

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final String CLOCK_SUFFIX = ".FlexClockTextView";
    private static final String ROOT_SUFFIX = ".KeyguardRootView";

    private static int placeholderId = 0;
    private static int cardId = 0;
    private static String lastChildSig = "";
    private static int diagLogs = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;

        // 1. Хук разметки экрана блокировки
        try {
            hookKeyguard(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // 2. Блокировка системного сброса TranslationX у даты (с компенсацией AOD)
        try {
            hookDateTranslation(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // 3. Блокировка 1-секундного улета даты вправо при приходе уведомления
        try {
            hookDateLayout(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // 4. Центрирование компактной иконки уведомления (Shelf) и AOD
        try {
            hookPreDraw(cl, "com.android.systemui.statusbar.NotificationShelf", true);
            hookPreDraw(cl, "com.android.systemui.statusbar.phone.NotificationIconContainer", false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Только настоящая дата. Карточки Smartspace («Фонарик включен» и т.п.) сюда не входят. */
    private static boolean isDateView(View v) {
        return v != null && v.getClass().getName().endsWith(".DateSmartspaceView");
    }

    /**
     * Текст даты стоит у левого края своей рамки, а справа в рамке есть пустое место.
     * Возвращает, на сколько px нужно сдвинуть рамку вправо (+) или влево (-),
     * чтобы по центру экрана оказался именно текст.
     */
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

    /** Устраняет 1-секундный улет даты вправо при анимации прихода уведомления */
    private static void hookDateLayout(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedHelpers.findAndHookMethod(viewClass, "layout",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (!isDateView(v)) return;

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
                if (!isDateView(v)) return;

                View parent = (View) v.getParent();
                if (parent == null || !parent.getClass().getName().endsWith(ROOT_SUFFIX)) return;

                int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
                int w = v.getWidth();
                if (w <= 0 || w >= screenWidth - 100) return;

                // Учитываем сдвиг AOD
                float rootShift = parent.getLeft() + parent.getTranslationX();
                float targetCenterInRoot = (screenWidth / 2f) - rootShift;
                int currentLeft = v.getLeft();
                float targetTx = (targetCenterInRoot - w / 2f + dateContentDelta(v)) - currentLeft;

                param.args[0] = targetTx;
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
                    if (!HOOKED.add(root)) return;
                    root.getViewTreeObserver().addOnPreDrawListener(
                            new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    try {
                                        fixKeyguard(root, false);
                                        forceCenterDateAndClock(root);
                                        logChildren(root);
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
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;
            else if (isDateView(c)) date = c;
        }

        if (clock != null) {
            enforceCentered(clock, root, inMeasure);
            attachClockScaler(clock);
        }

        if (date != null && clock != null) {
            ViewGroup.LayoutParams lp = date.getLayoutParams();
            if (lp != null) {
                int cid = clock.getId();
                setInt(lp, "startToEnd", UNSET);
                setInt(lp, "leftToRight", UNSET);
                setInt(lp, "topToTop", UNSET);

                if (cid != View.NO_ID) {
                    setInt(lp, "topToBottom", cid);
                }

                setInt(lp, "startToStart", PARENT);
                setInt(lp, "endToEnd", PARENT);
                setFloat(lp, "horizontalBias", 0.5f);

                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    m.setMarginStart(0);
                    m.setMarginEnd(0);
                    m.leftMargin = 0;
                    m.rightMargin = 0;
                    m.topMargin = 24; // отступ вниз, чтобы увеличенные часы не налезали на дату
                }

                apply(date, lp, root, inMeasure);
            }

            int anchorId = date.getId();
            View card = findCard(root);
            if (card != null && card.getVisibility() == View.VISIBLE
                    && card.getId() != View.NO_ID && date.getId() != View.NO_ID) {
                ViewGroup.LayoutParams clp = card.getLayoutParams();
                if (clp != null && clp.getClass().getName().contains("ConstraintLayout")) {
                    boolean ch = false;
                    ch |= setInt(clp, "topToTop", UNSET);
                    ch |= setInt(clp, "topToBottom", date.getId());
                    if (clp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams cm = (ViewGroup.MarginLayoutParams) clp;
                        if (cm.topMargin != CARD_TOP_MARGIN_PX) {
                            cm.topMargin = CARD_TOP_MARGIN_PX;
                            ch = true;
                        }
                    }
                    if (ch) apply(card, clp, root, inMeasure);
                    anchorId = card.getId();   // уведомления начинаются под карточкой
                }
            }
            pointPlaceholderAt(root, anchorId, inMeasure);
        }
    }

    /** Центрирует часы и дату с компенсацией сдвига AOD */
    private static void forceCenterDateAndClock(ViewGroup root) {
        int displayWidth = root.getResources().getDisplayMetrics().widthPixels;
        if (displayWidth <= 0) return;

        float rootShift = root.getLeft() + root.getTranslationX();
        float targetCenterInRoot = (displayWidth / 2f) - rootShift;

        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;

            if (isDateView(v) || v.getClass().getName().endsWith(CLOCK_SUFFIX)) {
                int w = v.getWidth();
                if (w > 0 && w < displayWidth - 100) {
                    int left = v.getLeft();
                    float targetTx = (targetCenterInRoot - w / 2f
                            + (isDateView(v) ? dateContentDelta(v) : 0f)) - left;
                    if (Math.abs(v.getTranslationX() - targetTx) > 0.5f) {
                        v.setTranslationX(targetTx);
                    }
                }
            }
        }
    }

    /** Карточка Smartspace под часами («Фонарик включен», погода и т.п.). */
    private static View findCard(ViewGroup root) {
        View v = null;
        if (cardId == 0) {
            cardId = root.getResources().getIdentifier("bc_smartspace_view", "id", "com.android.systemui");
        }
        if (cardId != 0) v = root.findViewById(cardId);
        if (v == null) {
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                String n = c.getClass().getName();
                if (n.contains("Smartspace") && !n.endsWith(".DateSmartspaceView")
                        && !n.contains("Weather")) {
                    v = c;
                    break;
                }
            }
        }
        if (v == null) return null;
        View top = v;
        while (top.getParent() != root) {
            ViewParent p = top.getParent();
            if (!(p instanceof View)) return null;
            top = (View) p;
        }
        return top;
    }

    private static String idName(View v, int id) {
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "#" + id;
        }
    }

    private static String ruleOf(ViewGroup.LayoutParams lp, View v, String f) {
        try {
            int val = lp.getClass().getField(f).getInt(lp);
            if (val == -1) return "";
            return " " + f + "->" + (val == 0 ? "PARENT" : idName(v, val));
        } catch (Throwable t) {
            return "";
        }
    }

    /** Диагностика: пишет в лог список видимых элементов экрана блокировки, когда он меняется. */
    private static void logChildren(ViewGroup root) {
        if (diagLogs >= 8) return;
        StringBuilder sig = new StringBuilder();
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0 || c.getHeight() == 0) continue;
            String cls = c.getClass().getName();
            String id = c.getId() != View.NO_ID ? idName(c, c.getId()) : "-";
            sig.append(cls).append('#').append(id).append(';');
            full.append("\n   ").append(cls).append(" id=").append(id)
                    .append(" [x=").append(c.getLeft()).append(" y=").append(c.getTop())
                    .append(" ").append(c.getWidth()).append('x').append(c.getHeight()).append(']');
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp != null && lp.getClass().getName().contains("ConstraintLayout")) {
                full.append(ruleOf(lp, c, "topToBottom")).append(ruleOf(lp, c, "topToTop"))
                        .append(ruleOf(lp, c, "bottomToBottom")).append(ruleOf(lp, c, "bottomToTop"))
                        .append(ruleOf(lp, c, "startToStart")).append(ruleOf(lp, c, "startToEnd"))
                        .append(ruleOf(lp, c, "endToEnd"));
            }
        }
        String sg = sig.toString();
        if (sg.equals(lastChildSig)) return;
        lastChildSig = sg;
        diagLogs++;
        XposedBridge.log("[LockCenter] keyguard children:" + full);
    }

    private static void attachClockScaler(final View clock) {
        if (!HOOKED.add(clock)) return;
        clock.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (clock.getVisibility() != View.VISIBLE || clock.getWidth() == 0) return true;
                            clock.setPivotX(clock.getWidth() / 2f);
                            clock.setPivotY(clock.getHeight() / 2f);
                            if (Math.abs(clock.getScaleX() - CLOCK_SCALE_X) > 0.01f) {
                                clock.setScaleX(CLOCK_SCALE_X);
                            }
                            if (Math.abs(clock.getScaleY() - CLOCK_SCALE_Y) > 0.01f) {
                                clock.setScaleY(CLOCK_SCALE_Y);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
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
        if (setInt(lp, "topToBottom", anchorId)) {
            apply(ph, lp, root, inMeasure);
        }
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
            } catch (Throwable ignored) {
            }
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
                                    if (isShelf) {
                                        centerShelf((ViewGroup) v);
                                    } else if (isAodContainer(v)) {
                                        centerAodIcons((ViewGroup) v);
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

    /** Выравнивает иконки на AOD строго по физическому центру экрана */
    private static void centerAodIcons(ViewGroup c) {
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

        float currentCenterOnScreen = cLoc[0] + minL + (maxR - minL) / 2f;
        float targetCenter = displayWidth / 2f;
        float shift = targetCenter - currentCenterOnScreen;

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
