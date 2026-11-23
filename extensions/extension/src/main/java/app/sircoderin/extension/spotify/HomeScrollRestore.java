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
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private static final long SAVE_BURST_MS = 1000;

    private static long lastSaveTime;

    /**
     * Ready rows of the latest Home update, and of the update shown when Home was left. Each row carries its section
     * URI ("spotify:section:<id>|<type>"), which identifies it across the cached and network versions of Home.
     */
    private static List<?> lastList = Collections.emptyList();
    private static List<?> savedList = Collections.emptyList();
    private static Field rowKeyField;
    private static Class<?> rowKeyOwner;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static int lastSize = -1;
    private static Field listField;
    private static Class<?> listFieldOwner;

    /** Horizontal offsets saved on leave: row top (px) to carousel offset (px). */
    /**
     * Carousel scroll states, keyed by section id. Spotify's carousel LayoutManager saves into such a map on detach
     * and restores from it on attach, but Spotify creates the map per view, so Back (which rebuilds Home's views)
     * lost every carousel position. The patch makes all carousels share this one instead (see carouselStates).
     */
    private static final LinkedHashMap<Object, Object> CAROUSEL_STATES = new LinkedHashMap<Object, Object>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
            return size() > 200;
        }
    };

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
                // Spotify applies this state itself; correct its row position for the rebuilt Home first.
                if (savedCount > 0 && pendingList == null) fixAnchor(saved.getSparseParcelableArray("view_state"));
                // Spotify restores the vertical position itself once its adapter has the rows; settle after that.
                // Spotify can restore a second, empty page right after Home (saved=0): nothing to do for it.
                if (savedCount > 0 && pendingList == null) {
                    final View l = list;
                    HANDLER.postDelayed(() -> afterLayout(l, () -> {
                        applyBottom(l);
                        beginSettle(l);
                    }), 100);
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
            if (pending == null) keepTopRow(list, lastList);
            if (pending != null && pending == list) {
                if (!list.isAttachedToWindow()) {
                    clearPending(list, true);
                } else if (size >= pendingTarget && savedTopReady(pendingState)) {
                    Log.d(TAG, "onEmission size=" + size + " >= target=" + pendingTarget + ": restoring");
                    finishVertical(list);
                } else if (size >= pendingTarget) {
                    // The nudge keeps building rows; the deadline restores best effort if the section never comes.
                    Log.d(TAG, "onEmission size=" + size + ": saved top section not built yet, waiting");
                } else {
                    // The pump started by onRestore keeps scrolling; nothing to do until enough rows are ready.
                    Log.d(TAG, "onEmission size=" + size + " < target=" + pendingTarget + ": waiting");
                }
                return;
            }
        } catch (Throwable t) {
            Log.d(TAG, "onEmission failed", t);
        }
    }

    /** Called when Home serializes its state (leaving Home). */
    public static void onSave(View list) {
        try {
            if (!(list instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) list;
            long now = SystemClock.uptimeMillis();
            boolean firstOfBurst = now - lastSaveTime > SAVE_BURST_MS;
            lastSaveTime = now;
            // Keep the rows Home showed when it was left (the first save of a burst still sees them intact).
            if (firstOfBurst && group.getChildCount() > 0) {
                savedList = lastList;
                savedAtBottom = !list.canScrollVertically(1);
                Log.d(TAG, "onSave: " + savedList.size() + " rows" + (savedAtBottom ? ", at the end" : "")
                        + ", " + CAROUSEL_STATES.size() + " carousel states");
            }
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
        fixAnchor(state);
        clearPending(list, false);
        list.post(() -> {
            try {
                if (state != null) list.restoreHierarchyState(state);
                afterLayout(list, () -> {
                    applyBottom(list);
                    beginSettle(list);
                    list.setAlpha(1f);
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

    /**
     * Spotify's saved state stores the top row as an adapter position. The Home rebuilt on Back (from cache) can order
     * and count rows differently, so that position can point at another section. Finds the saved top row's section
     * URI in the current rows and rewrites the position to its new index (the pixel offset stays valid). If that
     * section is gone, uses its nearest saved neighbour that still exists, shifted by their distance. Consumes the
     * saved rows.
     */
    private static void fixAnchor(SparseArray<Parcelable> state) {
        List<?> oldRows = savedList;
        savedList = Collections.emptyList();
        settleKey = null;
        try {
            Object layout = anchorLayout(state);
            Field position = layout == null ? null : anchorField(layout);
            if (position == null || oldRows.isEmpty()) return;
            int pos = position.getInt(layout);
            if (pos < 0 || pos >= oldRows.size()) return;
            List<?> rows = lastList;
            if (rows.isEmpty()) return;
            int target = indexOf(rows, rowKey(oldRows.get(pos)));
            if (target == pos) {
                Log.d(TAG, "fixAnchor: row " + pos + " unchanged");
            } else if (target >= 0) {
                Log.d(TAG, "fixAnchor: top row moved from " + pos + " to " + target);
            } else {
                for (int d = 1; d < oldRows.size() && target < 0; d++) {
                    int before = pos - d >= 0 ? indexOf(rows, rowKey(oldRows.get(pos - d))) : -1;
                    int after = pos + d < oldRows.size() ? indexOf(rows, rowKey(oldRows.get(pos + d))) : -1;
                    if (before >= 0) target = before + d;
                    else if (after >= 0) target = after - d;
                    if (target >= 0 || before >= 0 || after >= 0) {
                        target = Math.max(0, Math.min(rows.size() - 1, target));
                        Log.d(TAG, "fixAnchor: top section gone, using a neighbour " + d + " row(s) away: " + pos + " -> " + target);
                    }
                }
                if (target < 0) {
                    target = Math.min(pos, rows.size() - 1);
                    Log.d(TAG, "fixAnchor: no saved section among " + rows.size() + " rows, using " + target);
                }
            }
            position.setInt(layout, target);
            Field offset = offsetField(layout);
            if (offset != null) {
                settleKey = rowKey(rows.get(target));
                settleOffset = offset.getInt(layout);
            }
        } catch (Throwable t) {
            Log.d(TAG, "fixAnchor failed", t);
        }
    }

    /** LinearLayoutManager.SavedState's anchor offset: its second int instance field. */
    private static Field offsetField(Object layout) {
        boolean first = true;
        for (Field f : layout.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            if (first) { first = false; continue; }
            f.setAccessible(true);
            return f;
        }
        return null;
    }

    /**
     * Whether the pending restore can run: Spotify builds Home's rows progressively as the list scrolls, and the
     * saved top section (for example the last row of Home) can come later than the saved row count suggests.
     */
    private static boolean savedTopReady(SparseArray<Parcelable> state) {
        try {
            Object layout = anchorLayout(state);
            Field position = layout == null ? null : anchorField(layout);
            if (position == null) return true;
            int pos = position.getInt(layout);
            if (pos < 0 || pos >= savedList.size()) return true;
            String key = rowKey(savedList.get(pos));
            return key == null || indexOf(lastList, key) >= 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** The layout manager's saved state in a view_state array, or null. */
    private static Object anchorLayout(SparseArray<Parcelable> state) throws Exception {
        return state == null || state.size() == 0 ? null : layoutState(state.valueAt(0));
    }

    /** LinearLayoutManager.SavedState's anchor position: its first int instance field. */
    private static Field anchorField(Object layout) {
        for (Field f : layout.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static int indexOf(List<?> rows, String key) throws Exception {
        if (key == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rowKey(rows.get(i)))) return i;
        }
        return -1;
    }

    // Pin of the top section across a Home update (see keepTopRow).
    private static WeakReference<View> hookedList = new WeakReference<>(null);
    /** The rows as of the list's last drawn layout: child positions on screen index into these. */
    private static List<?> drawnRows = Collections.emptyList();
    private static String pinKey;
    private static int pinOffset;
    private static long pinUntil;
    private static int pinCorrections;
    /**
     * Whether Home was left scrolled to its end. Right after Back the last rows are still loading and short, so the
     * list fills the gap by moving up; the restore then keeps the list at its end until they settle.
     */
    private static boolean savedAtBottom;
    private static long stickBottomUntil;
    private static int stickCorrections;
    private static final long STICK_BOTTOM_MS = 1200;
    /**
     * The restored top section and its offset. Near the end of Home the rows below are still loading and short right
     * after Back, so the list moves up to fill the gap; for SETTLE_MS the restore keeps this section in place.
     */
    private static String settleKey;
    private static int settleOffset;
    private static long settleUntil;
    private static long settleTried;
    private static final long SETTLE_MS = 1500;

    /**
     * Spotify replaces Home's rows wholesale when fresher content arrives (for example the network version a few
     * seconds after a cold start, with sections added or reordered), and the list keeps only the row number, so the
     * content under the user jumps by however many sections changed above. Like a well-behaved feed, pin the section
     * at the top of the screen instead: note it and its offset now, before Spotify applies the new rows, and fix the
     * position before the next frame is drawn (see onListPreDraw). Follow-up updates keep the same pin.
     */
    private static void keepTopRow(final View list, List<?> newRows) {
        try {
            if (!(list instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) list;
            hookPreDraw(list);
            List<?> oldRows = drawnRows;
            if (oldRows.isEmpty() || newRows.isEmpty() || oldRows == newRows) return;
            String head = "update " + oldRows.size() + " -> " + newRows.size() + " rows: ";
            if (stickBottomUntil != 0) {
                Log.d(TAG, head + "keeping Home at its end");
                return;
            }
            if (settleKey != null && SystemClock.uptimeMillis() < settleUntil) {
                Log.d(TAG, head + "settling the restore");
                return;
            }
            if (pinKey != null && SystemClock.uptimeMillis() < pinUntil) {
                Log.d(TAG, head + "pin pending, keeping it");
                return;
            }
            if (group.getChildCount() == 0 || !list.isAttachedToWindow()) { Log.d(TAG, head + "list not shown"); return; }
            int pos = adapterPosition(group, 0);
            if (pos < 0 || pos >= oldRows.size()) { Log.d(TAG, head + "top row position unknown (" + pos + ")"); return; }
            String key = rowKey(oldRows.get(pos));
            if (key == null) { Log.d(TAG, head + "top row " + pos + " has no section id"); return; }
            if (pos < newRows.size() && key.equals(rowKey(newRows.get(pos)))) {
                Log.d(TAG, head + "top row " + pos + " unchanged");
                return;
            }
            pinKey = key;
            pinOffset = group.getChildAt(0).getTop() - list.getPaddingTop();
            pinUntil = SystemClock.uptimeMillis() + 1500;
            pinCorrections = 0;
            Log.d(TAG, head + "top row " + pos + " moved, pinning " + key);
        } catch (Throwable t) {
            Log.d(TAG, "keepTopRow failed", t);
        }
    }

    /** If Home was left at its end, scrolls there and keeps it there for STICK_BOTTOM_MS. */
    private static void applyBottom(View list) {
        if (!savedAtBottom) return;
        savedAtBottom = false;
        int n = scrollToEnd(list);
        stickBottomUntil = SystemClock.uptimeMillis() + STICK_BOTTOM_MS;
        stickCorrections = 0;
        pinKey = null;
        hookPreDraw(list);
        Log.d(TAG, "bottom: Home was left at its end, scrolled " + n + " step(s) to it");
    }

    private static void beginSettle(View list) {
        if (settleKey == null) return;
        if (stickBottomUntil != 0) {
            settleKey = null; // the end of Home is the better anchor
            return;
        }
        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;
        settleTried = 0;
        hookPreDraw(list);
    }

    /** Layout fingerprint: top position and offset, child count and the last child's bottom. */
    private static long layoutSignature(ViewGroup group, int top, int offset) {
        int n = group.getChildCount();
        int bottom = n == 0 ? 0 : group.getChildAt(n - 1).getBottom();
        return ((long) top << 48) ^ ((long) (offset & 0xFFFF) << 32) ^ ((long) n << 20) ^ (bottom & 0xFFFFF);
    }

    private static int scrollToEnd(View list) {
        int n = 0;
        while (n < 10 && list.canScrollVertically(1)) {
            list.scrollBy(0, Math.max(1, list.getHeight()));
            n++;
        }
        return n;
    }

    private static void hookPreDraw(final View list) {
        if (hookedList.get() == list) return;
        hookedList = new WeakReference<>(list);
        list.getViewTreeObserver().addOnPreDrawListener(() -> onListPreDraw(list));
    }

    /**
     * Runs after each layout of the list and before it is drawn. With a pin set, checks that the pinned section is
     * at the top at its old offset; if not, re-anchors the list and returns false, which cancels this draw and lays
     * out again, so the jumped frame is never shown.
     */
    private static boolean onListPreDraw(View list) {
        if (hookedList.get() != list) return true;
        List<?> rows = lastList;
        try {
            if (stickBottomUntil != 0) {
                if (SystemClock.uptimeMillis() > stickBottomUntil || stickCorrections >= 8) {
                    Log.d(TAG, "bottom: done after " + stickCorrections + " correction(s)");
                    stickBottomUntil = 0;
                } else if (list.canScrollVertically(1)) {
                    stickCorrections++;
                    scrollToEnd(list);
                    return false;
                }
            }
            if (settleKey != null) {
                ViewGroup g = (ViewGroup) list;
                int target = indexOf(rows, settleKey);
                if (SystemClock.uptimeMillis() > settleUntil || target < 0 || g.getChildCount() == 0) {
                    settleKey = null;
                } else {
                    int p = adapterPosition(g, 0);
                    int off = g.getChildAt(0).getTop() - list.getPaddingTop();
                    if (p != target || Math.abs(off - settleOffset) > 2) {
                        long sig = layoutSignature(g, p, off);
                        if (sig != settleTried) { // a correction at this exact layout already failed: draw, retry later
                            settleTried = sig;
                            Log.d(TAG, "settle: top is row " + p + " at " + off + ", putting row " + target + " back at " + settleOffset);
                            scrollToRow(list, target, settleOffset);
                            return false;
                        }
                    }
                }
            }
            if (pinKey == null) return true;
            ViewGroup group = (ViewGroup) list;
            if (SystemClock.uptimeMillis() > pinUntil) {
                Log.d(TAG, "pin: expired");
                pinKey = null;
                return true;
            }
            int target = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (pinKey.equals(rowKey(rows.get(i)))) {
                    target = i;
                    break;
                }
            }
            if (target < 0 || group.getChildCount() == 0) {
                Log.d(TAG, "pin: section gone, dropping");
                pinKey = null;
                return true;
            }
            int p = adapterPosition(group, 0);
            int offset = group.getChildAt(0).getTop() - list.getPaddingTop();
            // After one correction, the right section on top is enough: the user may still be scrolling.
            if (p == target && (pinCorrections > 0 || Math.abs(offset - pinOffset) <= 2)) {
                Log.d(TAG, "pin: section at row " + p + " after " + pinCorrections + " correction(s)");
                pinKey = null;
                return true;
            }
            if (pinCorrections >= 3) {
                Log.d(TAG, "pin: giving up, top is row " + p + " offset " + offset + ", wanted " + target);
                pinKey = null;
                return true;
            }
            pinCorrections++;
            Log.d(TAG, "pin: top is row " + p + ", moving to row " + target);
            scrollToRow(list, target, pinOffset);
            return false;
        } catch (Throwable t) {
            Log.d(TAG, "pin failed", t);
            pinKey = null;
            return true;
        } finally {
            drawnRows = rows;
        }
    }

    /**
     * Scrolls the list so adapter row {@code position} starts {@code offset} px below the top, by handing the
     * layout manager a saved state with that anchor (Spotify's androidx is obfuscated, so the scroll method can't be
     * called by name; its saved state is the same mechanism scrollToPositionWithOffset uses).
     */
    private static void scrollToRow(View list, int position, int offset) {
        try {
            SparseArray<Parcelable> state = new SparseArray<>();
            list.saveHierarchyState(state);
            if (state.size() == 0) return;
            Object layout = layoutState(state.valueAt(0));
            if (layout == null) return;
            Field pos = null, off = null;
            for (Field f : layout.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                if (pos == null) pos = f; else if (off == null) off = f;
            }
            if (pos == null || off == null) return;
            pos.setAccessible(true);
            off.setAccessible(true);
            pos.setInt(layout, position);
            off.setInt(layout, offset);
            list.restoreHierarchyState(state);
        } catch (Throwable t) {
            Log.d(TAG, "scrollToRow failed", t);
        }
    }

    /** The layout manager's saved state inside a RecyclerView saved state (its only Parcelable instance field). */
    private static Object layoutState(Parcelable outer) throws Exception {
        for (Field f : outer.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && Parcelable.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f.get(outer);
            }
        }
        return null;
    }

    private static Field holderField;
    private static Field holderPositionField;

    /**
     * Adapter position of child {@code index}, read from its view holder (RecyclerView's getChildAdapterPosition is
     * stripped from Spotify's build). The position field is picked by checking that it counts up by one across the
     * visible children. Returns -1 if that can't be established.
     */
    private static int adapterPosition(ViewGroup group, int index) throws Exception {
        int n = group.getChildCount();
        if (n < 2) return -1;
        if (holderPositionField == null) {
            View child0 = group.getChildAt(0);
            Object lp = child0.getLayoutParams();
            // The view holder is the layout-params field whose object holds a reference to the child view itself.
            Field hf = null;
            for (Class<?> c = lp.getClass(); c != null && hf == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(lp);
                    if (v != null && holdsView(v, child0)) {
                        hf = f;
                        break;
                    }
                }
            }
            if (hf == null) {
                Log.d(TAG, "adapterPosition: no view holder in " + lp.getClass().getName());
                return -1;
            }
            // Its position field (declared in RecyclerView.ViewHolder, a superclass of Spotify's holders) counts up
            // by one across the visible children.
            Object holder0 = hf.get(lp);
            for (Class<?> c = holder0.getClass(); c != null && holderPositionField == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    f.setAccessible(true);
                    boolean consecutive = true;
                    int prev = Integer.MIN_VALUE;
                    for (int i = 0; i < n && consecutive; i++) {
                        Object h = hf.get(group.getChildAt(i).getLayoutParams());
                        if (h == null) { consecutive = false; break; }
                        int v = f.getInt(h);
                        if (v < 0 || (prev != Integer.MIN_VALUE && v != prev + 1)) consecutive = false;
                        prev = v;
                    }
                    if (consecutive) {
                        holderField = hf;
                        holderPositionField = f;
                        break;
                    }
                }
            }
            if (holderPositionField == null) {
                Log.d(TAG, "adapterPosition: no consecutive position field in " + holder0.getClass().getName());
                return -1;
            }
        }
        Object holder = holderField.get(group.getChildAt(index).getLayoutParams());
        return holder == null ? -1 : holderPositionField.getInt(holder);
    }

    /** Whether {@code obj} has a field (in its class chain) holding exactly {@code view}. */
    private static boolean holdsView(Object obj, View view) throws Exception {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !View.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                if (f.get(obj) == view) return true;
            }
        }
        return false;
    }

    /** The row's section URI ("spotify:section:..."), or null. */
    private static String rowKey(Object row) throws Exception {
        if (row == null) return null;
        Class<?> cls = row.getClass();
        if (rowKeyOwner != cls) {
            rowKeyOwner = cls;
            rowKeyField = null;
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(row);
                if (v instanceof String && ((String) v).startsWith("spotify:")) {
                    rowKeyField = f;
                    break;
                }
            }
        }
        if (rowKeyField == null) return null;
        Object v = rowKeyField.get(row);
        return v instanceof String ? (String) v : null;
    }

    /** Called from the carousel constructor in place of {@code new LinkedHashMap()}. */
    public static LinkedHashMap<Object, Object> carouselStates() {
        return CAROUSEL_STATES;
    }

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
        if (list != null) lastList = list;
        return list == null ? 0 : list.size();
    }
}
