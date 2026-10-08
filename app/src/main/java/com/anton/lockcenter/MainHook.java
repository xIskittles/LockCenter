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

/**
 * LockCenter v8 (Pixel 7 Pro, Android 16).
 *
 * 1) Small clock + date: before EVERY measure pass of KeyguardRootView the module
 *    re-applies its own ConstraintLayout rules (clock centered with zero margins,
 *    date centered UNDER the clock, "smart_space_barrier_bottom" extended to the
 *    date so notifications start below it). Because it is re-applied each time,
 *    the system cannot silently undo it.
 * 2) Small clock size: scale factors below (width a bit more than height).
 * 3) Compact notification pill on the lock screen (NotificationShelf): centered.
 * 4) Notification icons on AOD (aod_notification_icon_container): centered.
 */
public class MainHook implements IXposedHookLoadPackage {

    // ======== TWEAK HERE ========
    /** Clock size multipliers (1.0 = stock). Width is larger than height on purpose. */
    private static final float CLOCK_SCALE_X = 1.20f;
    private static final float CLOCK_SCALE_Y = 1.12f;
    // ============================

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final String CLOCK_SUFFIX = ".FlexClockTextView";
    private static final String DATE_SUFFIX = ".DateSmartspaceView";
    private static final String ROOT_SUFFIX = ".KeyguardRootView";
    private static int barrierId = 0;
    private static int placeholderId = 0;
    private static int netLogs = 0;
    private static boolean hitApply, hitMeasure, hitDraw, loggedClockLp;

    private static int logCount = 0;
    private static int shelfLogs = 0;
    private static int aodLogs = 0;
    private static int fixLogs = 0;

    private static void log(String m) {
        if (logCount++ < 250) XposedBridge.log("[LockCenter] " + m);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        log("v8 started");
        try {
            hookKeyguard(cl);
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

    // ---------------------------------------------------------------- clock + date

    private static boolean isRoot(Object o) {
        return o != null && o.getClass().getName().endsWith(ROOT_SUFFIX);
    }

    /** Three independent entry points; each one is safe to run repeatedly. */
    private static void hookKeyguard(ClassLoader cl) {
        Class<?> constraintSet = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintSet", cl);
        Class<?> constraintLayout = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintLayout", cl);
        Class<?> rootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);
        log("classes: constraintSet=" + (constraintSet != null)
                + " constraintLayout=" + (constraintLayout != null)
                + " root=" + (rootClass != null));

        // 1) right after the system applies its constraint rules
        if (constraintSet != null) {
            XC_MethodHook after = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length >= 1 && isRoot(param.args[0])) {
                            if (!hitApply) { hitApply = true; log("entry applyTo fired"); }
                            fixKeyguard((ViewGroup) param.args[0], false);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            int n = XposedBridge.hookAllMethods(constraintSet, "applyTo", after).size();
            n += XposedBridge.hookAllMethods(constraintSet, "applyToWithoutCheck", after).size();
            log("applyTo hooks: " + n);
        }

        // 2) before every measure pass of the keyguard root
        if (constraintLayout != null) {
            XC_MethodHook before = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (isRoot(param.thisObject)) {
                            if (!hitMeasure) { hitMeasure = true; log("entry onMeasure fired"); }
                            fixKeyguard((ViewGroup) param.thisObject, true);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            int n = XposedBridge.hookAllMethods(constraintLayout, "onMeasure", before).size();
            if (rootClass != null) {
                n += XposedBridge.hookAllMethods(rootClass, "onMeasure", before).size();
            }
            log("onMeasure hooks: " + n);
        }

        // 3) before every frame is drawn (same technique that already works for the pill)
        if (rootClass != null) {
            int n = XposedBridge.hookAllConstructors(rootClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final ViewGroup root = (ViewGroup) param.thisObject;
                    if (!HOOKED.add(root)) return;
                    root.getViewTreeObserver().addOnPreDrawListener(
                            new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    try {
                                        if (!hitDraw) { hitDraw = true; log("entry preDraw fired"); }
                                        if (!fixKeyguard(root, false)) dateSafetyNet(root);
                                    } catch (Throwable t) {
                                        XposedBridge.log(t);
                                    }
                                    return true;
                                }
                            });
                }
            }).size();
            log("root constructor hooks: " + n);
        }
    }

    /** Returns true if it changed any layout rule during this call. */
    private static boolean fixKeyguard(ViewGroup root, boolean inMeasure) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;           // small clock
            else if (n.endsWith(DATE_SUFFIX)) date = c;        // date (+ alarm)
        }
        if (clock == null) return false;

        if (!loggedClockLp && clock.getLayoutParams() != null) {
            loggedClockLp = true;
            ViewGroup.LayoutParams l = clock.getLayoutParams();
            log("clock lp: startToStart=" + getInt(l, "startToStart")
                    + " endToEnd=" + getInt(l, "endToEnd")
                    + " marginStart=" + ((ViewGroup.MarginLayoutParams) l).getMarginStart());
        }
        boolean changed = enforceCentered(clock, root, inMeasure);
        attachClockScaler(clock);
        if (changed && fixLogs++ < 20) log("small clock centered");

        if (date == null) return changed;
        ViewGroup.LayoutParams lp = date.getLayoutParams();
        int cid = clock.getId();
        if (lp == null || cid == View.NO_ID) return changed;

        boolean tiedToClock = isTiedToClock(lp, cid);
        if (!tiedToClock) {
            // large clock mode: leave the date alone and give the notification area back to the system
            return changed | restorePlaceholder(root, date.getId(), inMeasure);
        }

        boolean v = false;
        v |= setInt(lp, "topToTop", UNSET);
        v |= setInt(lp, "bottomToTop", UNSET);
        v |= setInt(lp, "bottomToBottom", UNSET);
        v |= setInt(lp, "topToBottom", cid);
        boolean h = enforceCentered(date, root, inMeasure);
        if (v && !h) apply(date, lp, root, inMeasure);
        if ((v || h) && fixLogs++ < 20) log("date placed under clock and centered");
        changed |= v || h;

        changed |= pointPlaceholderAtDate(root, date.getId(), inMeasure);
        return changed;
    }

    private static boolean isTiedToClock(ViewGroup.LayoutParams lp, int cid) {
        return getInt(lp, "startToEnd") == cid
                || getInt(lp, "topToBottom") == cid
                || getInt(lp, "topToTop") == cid;
    }

    private static int resId(ViewGroup root, String name) {
        return root.getResources().getIdentifier(name, "id", "com.android.systemui");
    }

    /** The notification area normally starts below "smart_space_barrier_bottom"; point it at the date. */
    private static boolean pointPlaceholderAtDate(ViewGroup root, int dateId, boolean inMeasure) {
        if (dateId == View.NO_ID) return false;
        if (placeholderId == 0) placeholderId = resId(root, "nssl_placeholder");
        if (placeholderId == 0) return false;
        View ph = root.findViewById(placeholderId);
        if (ph == null || ph.getLayoutParams() == null) return false;
        ViewGroup.LayoutParams lp = ph.getLayoutParams();
        if (!setInt(lp, "topToBottom", dateId)) return false;
        apply(ph, lp, root, inMeasure);
        if (fixLogs++ < 20) log("notification area now starts below the date");
        return true;
    }

    private static boolean restorePlaceholder(ViewGroup root, int dateId, boolean inMeasure) {
        if (dateId == View.NO_ID || placeholderId == 0) return false;
        View ph = root.findViewById(placeholderId);
        if (ph == null || ph.getLayoutParams() == null) return false;
        ViewGroup.LayoutParams lp = ph.getLayoutParams();
        if (getInt(lp, "topToBottom") != dateId) return false;
        if (barrierId == 0) barrierId = resId(root, "smart_space_barrier_bottom");
        if (barrierId == 0) return false;
        setInt(lp, "topToBottom", barrierId);
        apply(ph, lp, root, inMeasure);
        if (fixLogs++ < 20) log("notification area handed back to the system");
        return true;
    }

    /**
     * Safety net, runs every frame after layout: if the date (small clock mode) is still
     * visibly off-center, no matter why, shift it back. Small AOD burn-in shifts are left alone.
     */
    private static void dateSafetyNet(ViewGroup root) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;
            else if (n.endsWith(DATE_SUFFIX)) date = c;
        }
        if (clock == null || date == null) return;
        ViewGroup.LayoutParams lp = date.getLayoutParams();
        if (lp == null || clock.getId() == View.NO_ID || !isTiedToClock(lp, clock.getId())) return;
        if (date.getVisibility() != View.VISIBLE || date.getWidth() == 0 || root.getWidth() == 0) return;

        float center = date.getLeft() + date.getTranslationX() + date.getWidth() / 2f;
        float err = root.getWidth() / 2f - center;
        if (Math.abs(err) <= 24f) return;
        date.setTranslationX(date.getTranslationX() + err);
        if (netLogs++ < 12) {
            log("date was off-center by " + err + ": left=" + date.getLeft()
                    + " w=" + date.getWidth() + " rootW=" + root.getWidth()
                    + " lp[startToStart=" + getInt(lp, "startToStart")
                    + " endToEnd=" + getInt(lp, "endToEnd")
                    + " startToEnd=" + getInt(lp, "startToEnd")
                    + " topToBottom=" + getInt(lp, "topToBottom")
                    + " marginStart=" + ((ViewGroup.MarginLayoutParams) lp).getMarginStart() + "]");
        }
    }

    private static boolean enforceCentered(View v, ViewGroup parent, boolean inMeasure) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || !lp.getClass().getName().contains("ConstraintLayout")) return false;
        boolean ch = false;
        String[] clear = {"leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
                "startToEnd", "endToStart"};
        for (String f : clear) ch |= setInt(lp, f, UNSET);
        ch |= setInt(lp, "startToStart", PARENT);
        ch |= setInt(lp, "endToEnd", PARENT);
        ch |= setFloat(lp, "horizontalBias", 0.5f);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            if (m.getMarginStart() != 0 || m.getMarginEnd() != 0
                    || m.leftMargin != 0 || m.rightMargin != 0) {
                m.setMarginStart(0);
                m.setMarginEnd(0);
                m.leftMargin = 0;
                m.rightMargin = 0;
                ch = true;
            }
        }
        if (ch) apply(v, lp, parent, inMeasure);
        return ch;
    }

    private static void apply(View v, ViewGroup.LayoutParams lp, ViewGroup parent, boolean inMeasure) {
        if (inMeasure) {
            resolve(v, lp, parent);   // inside onMeasure: no requestLayout...
            v.forceLayout();          // ...but ConstraintLayout must still re-read this child
        } else {
            v.setLayoutParams(lp);
        }
    }

    /** We are inside onMeasure: re-resolve the params directly instead of calling requestLayout. */
    private static void resolve(final View v, final ViewGroup.LayoutParams lp, ViewGroup parent) {
        try {
            lp.getClass().getMethod("resolveLayoutDirection", int.class)
                    .invoke(lp, parent.getLayoutDirection());
        } catch (Throwable t) {
            v.post(new Runnable() {
                @Override
                public void run() {
                    v.setLayoutParams(lp);
                }
            });
        }
    }

    private static int getInt(Object lp, String name) {
        try {
            return lp.getClass().getField(name).getInt(lp);
        } catch (Throwable t) {
            return -2;
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

    // ------------------------------------------------------ clock size

    /**
     * Multiplies whatever scale the system sets (burn-in, transitions) by our factor.
     * Only touches the value when the system changed it, so it never compounds.
     */
    private static void attachClockScaler(final View clock) {
        if (!HOOKED.add(clock)) return;
        clock.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (clock.getVisibility() != View.VISIBLE) return true;
                            clock.setPivotX(clock.getWidth() / 2f);
                            clock.setPivotY(clock.getHeight() / 2f);
                            float cx = clock.getScaleX();
                            if (cx != lastX) {
                                lastX = cx * CLOCK_SCALE_X;
                                clock.setScaleX(lastX);
                            }
                            float cy = clock.getScaleY();
                            if (cy != lastY) {
                                lastY = cy * CLOCK_SCALE_Y;
                                clock.setScaleY(lastY);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                        return true;
                    }
                });
    }

    // ------------------------------------------------ shelf pill + AOD icons

    private static void hookPreDraw(ClassLoader cl, String className, final boolean isShelf) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            log("class not found: " + className);
            return;
        }
        int n = XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
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
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                                return true;
                            }
                        });
            }
        }).size();
        log("constructor hooks for " + className + ": " + n);
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

    private static void centerAodIcons(ViewGroup c) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;
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
        float shift = (c.getWidth() - (maxR - minL)) / 2f - minL;
        if (Math.abs(shift) < 1f) return;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            ch.setTranslationX(ch.getTranslationX() + shift);
        }
        if (aodLogs++ < 6) log("AOD icons shifted by " + shift + " (icons=" + n + ")");
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
            if (shelfLogs++ < 8) {
                log("shelf: full=" + full + " pill=" + pill + " shift=" + target);
            }
        }
    }

    /** Width of the drawn pill: from the background view if possible, else from the icons. */
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
                return w > 0 ? w : 0;       // <=0 means "full width"
            } catch (Throwable ignored) {
                // field is named differently on this build: fall back to icons
            }
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
            if (n > 0) return (int) (maxR + minL);   // symmetric padding on both sides
        }
        return 0;
    }
}
