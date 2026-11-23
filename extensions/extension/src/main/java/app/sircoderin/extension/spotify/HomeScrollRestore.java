package app.sircoderin.extension.spotify;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restores Spotify Home's vertical and horizontal (carousel) scroll position after Back.
 *
 * Only Android framework View APIs and reflection are used (Spotify's androidx is obfuscated). All entry points are
 * called on the main thread from patched Spotify code and never throw.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class HomeScrollRestore {
    private static final String TAG = "SpotifyHomeRestore";
    private static final int ROW_SLACK = 5;
    private static final long DEADLINE_MS = 2500;
    private static final long SECOND_PASS_MS = 300;
    private static final int TOP_TOLERANCE_PX = 4;
    private static final long SAVE_BURST_MS = 1000;

    private static long lastNonEmptySave;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static int lastSize = -1;
    private static Field listField;
    private static Class<?> listFieldOwner;
    private static Method offsetMethod;

    /** Horizontal offsets saved on leave: row top (px) to carousel offset (px). */
    private static Map<Integer, Integer> savedOffsets = Collections.emptyMap();

    // Pending vertical restore.
    private static WeakReference<View> pendingList;
    private static SparseArray<Parcelable> pendingState;
    private static int pendingTarget;
    private static Runnable deadline;
    private static View.OnAttachStateChangeListener detachListener;

    private HomeScrollRestore() {}

    /** Called right after getInt("viewDataHoldersSize"). Returns the count Spotify should use. */
    public static int onRestore(View list, Bundle saved, int savedCount) {
        try {
            int target = savedCount - ROW_SLACK;
            if (target <= lastSize) {
                Log.d(TAG, "onRestore saved=" + savedCount + " lastSize=" + lastSize + ": enough rows, normal path");
                // Spotify restores the vertical position itself once its adapter has the rows; apply carousel
                // offsets after that (applyHorizontal also runs a second pass 300 ms later).
                if (!savedOffsets.isEmpty()) {
                    final View l = list;
                    HANDLER.postDelayed(() -> afterLayout(l, () -> applyHorizontal(l)), 100);
                }
                return savedCount;
            }
            SparseArray<Parcelable> state = saved.getSparseParcelableArray("view_state");
            clearPending(list, false);
            pendingList = new WeakReference<>(list);
            pendingState = state;
            pendingTarget = target;
            list.setAlpha(0f);
            final View l = list;
            deadline = () -> {
                Log.d(TAG, "deadline: restoring best effort");
                finishVertical(l);
            };
            HANDLER.postDelayed(deadline, DEADLINE_MS);
            detachListener = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}

                @Override public void onViewDetachedFromWindow(View v) {
                    Log.d(TAG, "list detached: cancel pending restore");
                    clearPending(v, true);
                }
            };
            list.addOnAttachStateChangeListener(detachListener);
            Log.d(TAG, "onRestore saved=" + savedCount + " lastSize=" + lastSize + ": pending, target=" + target
                    + ", state=" + (state != null));
            nudge(list);
            return 0;
        } catch (Throwable t) {
            Log.d(TAG, "onRestore failed", t);
            try {
                clearPending(list, true);
            } catch (Throwable ignored) {
            }
            return savedCount;
        }
    }

    /** Called at the start of every Home update. */
    public static void onEmission(View list, Object update) {
        try {
            int size = sizeOf(update);
            lastSize = size;
            View pending = pendingList == null ? null : pendingList.get();
            if (pending != null && pending == list) {
                if (!list.isAttachedToWindow()) {
                    clearPending(list, true);
                } else if (size >= pendingTarget) {
                    Log.d(TAG, "onEmission size=" + size + " >= target=" + pendingTarget + ": restoring");
                    finishVertical(list);
                } else {
                    // The pump started by onRestore keeps scrolling; nothing to do until enough rows are ready.
                    Log.d(TAG, "onEmission size=" + size + " < target=" + pendingTarget + ": waiting");
                }
                return;
            }
            // Horizontal offsets are applied only after a vertical restore (see onRestore/finishVertical): applying
            // them here would run on the first update after Back, before any rows exist, and waste the saved map.
        } catch (Throwable t) {
            Log.d(TAG, "onEmission failed", t);
        }
    }

    /** Called when Home serializes its state (leaving Home). */
    public static void onSave(View list) {
        try {
            if (!(list instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) list;
            Map<Integer, Integer> offsets = new HashMap<>();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                View scroller = findHorizontalScroller(child, true);
                if (scroller == null) continue;
                int offset = offsetOf(scroller);
                if (offset != 0) offsets.put(child.getTop(), offset);
            }
            // Spotify serializes several times in a row while leaving Home; the later calls see a torn-down list.
            // Don't let those empty results overwrite a real save from the same burst.
            long now = SystemClock.uptimeMillis();
            if (offsets.isEmpty() && !savedOffsets.isEmpty() && now - lastNonEmptySave < SAVE_BURST_MS) {
                Log.d(TAG, "onSave: 0 carousel offsets, keeping the earlier save from this burst");
                return;
            }
            if (!offsets.isEmpty()) lastNonEmptySave = now;
            savedOffsets = offsets;
            Log.d(TAG, "onSave: " + offsets.size() + " carousel offsets");
        } catch (Throwable t) {
            Log.d(TAG, "onSave failed", t);
        }
    }

    // ---- vertical ----

    /**
     * While a restore is pending, scrolls the (invisible) list down by one screen every frame whenever it can move.
     * Each scroll moves Spotify's visible range, which makes its runtime ready the next rows and emit an update.
     * A single scroll isn't enough: it often runs before rows are laid out and then does nothing.
     */
    private static void nudge(final View list) {
        list.postOnAnimation(new Runnable() {
            @Override public void run() {
                try {
                    View pending = pendingList == null ? null : pendingList.get();
                    if (pending != list) return; // restore finished or cancelled
                    if (list.canScrollVertically(1)) list.scrollBy(0, list.getHeight());
                    list.postOnAnimation(this);
                } catch (Throwable t) {
                    Log.d(TAG, "nudge failed", t);
                }
            }
        });
    }

    private static void finishVertical(final View list) {
        final SparseArray<Parcelable> state = pendingState;
        clearPending(list, false);
        list.post(() -> {
            try {
                if (state != null) list.restoreHierarchyState(state);
                afterLayout(list, () -> {
                    list.setAlpha(1f);
                    applyHorizontal(list);
                });
            } catch (Throwable t) {
                Log.d(TAG, "finishVertical failed", t);
                list.setAlpha(1f);
            }
        });
    }

    private static void clearPending(View list, boolean restoreAlpha) {
        if (deadline != null) HANDLER.removeCallbacks(deadline);
        if (detachListener != null && list != null) list.removeOnAttachStateChangeListener(detachListener);
        deadline = null;
        detachListener = null;
        pendingList = null;
        pendingState = null;
        if (restoreAlpha && list != null) list.setAlpha(1f);
    }

    // ---- horizontal ----

    /** Applies and clears the saved offsets: once now, and again after a delay for rows bound late. */
    private static void applyHorizontal(final View list) {
        final Map<Integer, Integer> offsets = savedOffsets;
        if (offsets.isEmpty()) return;
        savedOffsets = Collections.emptyMap();
        applyPass(list, offsets);
        HANDLER.postDelayed(() -> {
            try {
                applyPass(list, offsets);
            } catch (Throwable t) {
                Log.d(TAG, "second pass failed", t);
            }
        }, SECOND_PASS_MS);
    }

    private static void applyPass(View list, Map<Integer, Integer> offsets) {
        if (!(list instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) list;
        int applied = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Integer saved = null;
            for (Map.Entry<Integer, Integer> e : offsets.entrySet()) {
                if (Math.abs(e.getKey() - child.getTop()) <= TOP_TOLERANCE_PX) {
                    saved = e.getValue();
                    break;
                }
            }
            if (saved == null) continue;
            View scroller = findHorizontalScroller(child, true);
            if (scroller == null) continue;
            try {
                int delta = saved - offsetOf(scroller);
                if (delta != 0) {
                    scroller.scrollBy(delta, 0);
                    applied++;
                }
            } catch (Throwable t) {
                Log.d(TAG, "horizontal apply failed", t);
            }
        }
        Log.d(TAG, "horizontal pass: scrolled " + applied + " of " + offsets.size() + " carousels");
    }

    private static View findHorizontalScroller(View view, boolean isRoot) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c.canScrollHorizontally(1) || c.canScrollHorizontally(-1)) return c;
            View found = findHorizontalScroller(c, false);
            if (found != null) return found;
        }
        return null;
    }

    private static int offsetOf(View v) throws Exception {
        if (offsetMethod == null) {
            Method m = View.class.getDeclaredMethod("computeHorizontalScrollOffset");
            m.setAccessible(true);
            offsetMethod = m;
        }
        return (Integer) offsetMethod.invoke(v);
    }

    // ---- helpers ----

    /** Runs r after the next layout pass of list (forcing one). */
    private static void afterLayout(final View list, final Runnable r) {
        final ViewTreeObserver observer = list.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                ViewTreeObserver vto = list.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.d(TAG, "afterLayout failed", t);
                }
            }
        });
        list.requestLayout();
    }

    private static int sizeOf(Object update) throws Exception {
        Class<?> cls = update.getClass();
        if (listField == null || listFieldOwner != cls) {
            Field found = null;
            for (Field f : cls.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    found = f;
                    break;
                }
            }
            if (found == null) throw new IllegalStateException("no List field on " + cls.getName());
            found.setAccessible(true);
            listField = found;
            listFieldOwner = cls;
        }
        List<?> list = (List<?>) listField.get(update);
        return list == null ? 0 : list.size();
    }
}
