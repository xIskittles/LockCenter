package com.anton.lockcenter;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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

    // ======== РАЗМЕР ЧАСОВ (твои настройки) ========
    private static final float CLOCK_SCALE_X = 1.65f; // ширина
    private static final float CLOCK_SCALE_Y = 1.40f; // высота
    // ===============================================

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final String CLOCK_SUFFIX = ".FlexClockTextView";
    private static final String ROOT_SUFFIX = ".KeyguardRootView";

    private static int placeholderId = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;

        // 1. Разметка экрана блокировки
        try {
            hookKeyguard(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        // 2. Блокировка системного сброса TranslationX у даты
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

    /** Проверка: это сам текст даты (Чт, 8 окт.) */
    private static boolean isDateOnly(View v) {
        if (v == null) return false;
        String name = v.getClass().getName();
        return name.contains("DateSmartspaceView");
    }

    /** Проверка: это карточка «Самое главное» (Фонарик включен, таймер и т.д.) */
    private static boolean isSmartspaceCard(View v) {
        if (v == null) return false;
        String name = v.getClass().getName();
        return name.contains("BcSmartspaceView");
    }

    private static void hookDateLayout(ClassLoader cl) {
        Class<?> viewClass = XposedHelpers.findClass("android.view.View", cl);
        XposedHelpers.findAndHookMethod(viewClass, "layout",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        // Центрируем только текст даты, плашку фонарика не трогаем!
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
                            int targetLeft = targetCenter - (width / 2);

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
                float targetTx = (targetCenterInRoot - w / 2f) - currentLeft;

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

        int dateSmartspaceId = resId(root, "date_smartspace_view");
        int bcSmartspaceId = resId(root, "bc_smartspace_view");

        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            int id = c.getId();

            if (n.endsWith(CLOCK_SUFFIX) && c.getHeight() < 300) {
                clock = c;
            } else if (id == dateSmartspaceId || isDateOnly(c)) {
                dateView = c;
            } else if (id == bcSmartspaceId || isSmartspaceCard(c)) {
                cardView = c;
            }
        }

        if (clock != null) {
            if (clock.getId() == View.NO_ID) {
                clock.setId(View.generateViewId());
            }
            enforceCentered(clock, root, inMeasure);
            attachClockScaler(clock);
        }

        // 1. ДАТА (Чт, 8 окт.) строго ПОД часами по центру
        if (dateView != null && clock != null) {
            if (dateView.getId() == View.NO_ID) {
                dateView.setId(View.generateViewId());
            }

            ViewGroup.LayoutParams lp = dateView.getLayoutParams();
            if (lp != null) {
                int cid = clock.getId();
                setInt(lp, "startToEnd", UNSET);
                setInt(lp, "leftToRight", UNSET);
                setInt(lp, "topToTop", UNSET);
                setInt(lp, "topToBottom", cid); // строго ПОД часами!

                setInt(lp, "startToStart", PARENT);
                setInt(lp, "endToEnd", PARENT);
                setFloat(lp, "horizontalBias", 0.5f);

                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    m.setMarginStart(0);
                    m.setMarginEnd(0);
                    m.leftMargin = 0;
                    m.rightMargin = 0;
                    m.topMargin = 20; // отступ вниз под большими цифрами
                }

                apply(dateView, lp, root, inMeasure);
            }
        }

        // 2. КАРТОЧКА (Фонарик включен) строго ПОД датой
        if (cardView != null && dateView != null) {
            if (cardView.getId() == View.NO_ID) {
                cardView.setId(View.generateViewId());
            }

            ViewGroup.LayoutParams lp = cardView.getLayoutParams();
            if (lp != null) {
                int did = dateView.getId();
                setInt(lp, "startToEnd", UNSET);
                setInt(lp, "leftToRight", UNSET);
                setInt(lp, "topToTop", UNSET);
                setInt(lp, "topToBottom", did); // строго ПОД датой!

                setInt(lp, "startToStart", PARENT);
                setInt(lp, "endToEnd", PARENT);
                setFloat(lp, "horizontalBias", 0.5f);

                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    m.topMargin = 16;
                }

                apply(cardView, lp, root, inMeasure);
            }
        }

        // 3. Уведомления и полка Telegram начинаются под нижней плашкой (под фонариком или под датой)
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

    private static void forceCenterDateAndClock(ViewGroup root) {
        int displayWidth = root.getResources().getDisplayMetrics().widthPixels;
        if (displayWidth <= 0) return;

        float rootShift = root.getLeft() + root.getTranslationX();
        float targetCenterInRoot = (displayWidth / 2f) - rootShift;

        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;

            // Центрируем часы и только текст даты
            if (isDateOnly(v) || v.getClass().getName().endsWith(CLOCK_SUFFIX)) {
                int w = v.getWidth();
                if (w > 0 && w < displayWidth - 100) {
                    int left = v.getLeft();
                    float targetTx = (targetCenterInRoot - w / 2f) - left;
                    if (Math.abs(v.getTranslationX() - targetTx) > 0.5f) {
                        v.setTranslationX(targetTx);
                    }
                }
            }
        }
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

    private static int resId(ViewGroup root, String name) {
        try {
            return root.getResources().getIdentifier(name, "id", "com.android.systemui");
        } catch (Throwable t) {
            return 0;
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
                                        centerShelf((ViewGroup) v, screenWidth);
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

    /** Выравнивает иконки на AOD строго по физическому центру экрана */
    private static void centerAodIcons(ViewGroup c, int screenWidth) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;

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
        float targetCenter = screenWidth / 2f;
        float shift = targetCenter - groupCenterOnScreen;

        if (Math.abs(shift) > 0.5f) {
            for (int i = 0; i < c.getChildCount(); i++) {
                View ch = c.getChildAt(i);
                ch.setTranslationX(ch.getTranslationX() + shift);
            }
        }
    }

    private static void centerShelf(ViewGroup shelf, int screenWidth) {
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
