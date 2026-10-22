package io.github.mantaboardgame.baleencode;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets the assistant use the apps that the user chose, through Android's accessibility interface (the one screen
 * readers use). The user turns this service on in Settings; Android shows its own warning when doing so.
 * Every operation is tied to the chosen app's package: if another app is in front, nothing is read or touched.
 * Two ways to act: through a control's accessibility action (the list mode), or by a tap / swipe at a screen
 * position (the screenshot mode). A position is only used when it lies on the chosen app's own window.
 */
public class DeviceControlService extends AccessibilityService {
    static volatile DeviceControlService instance;
    private static final int MAX_ITEMS = 400;
    private static final int MAX_DEPTH = 30;
    // Size of the full screen in the latest screenshot, to turn screenshot coordinates into screen coordinates
    private volatile int shotFullW, shotFullH;

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override
    public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    static boolean running() { return instance != null; }

    // ---------- finding the chosen app's window ----------

    private AccessibilityNodeInfo appRoot(String pkg) throws Exception {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && active.getPackageName() != null && pkg.equals(active.getPackageName().toString())) return active;
        // The chat app or the keyboard may hold focus while the chosen app is still visible (split screen)
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && pkg.equals(r.getPackageName().toString())) return r;
            }
        }
        // Do not say which other app is in front: that is not the assistant's business
        throw new Exception("The chosen app is not on screen. Use device_open_app to bring it forward.");
    }

    private static String simpleClass(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        if (c == null) return "View";
        String s = c.toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static String labelOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0 && !n.isPassword()) return clip(t.toString(), 90);
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) return clip(d.toString(), 90);
        CharSequence h = Build.VERSION.SDK_INT >= 26 ? n.getHintText() : null;
        if (h != null && h.length() > 0) return clip(h.toString(), 90);
        return "";
    }

    private static String clip(String s, int max) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ---------- reading the screen ----------

    String snapshot(String pkg) throws Exception {
        AccessibilityNodeInfo root = appRoot(pkg);
        JSONArray items = new JSONArray();
        boolean[] cut = {false};
        walk(root, "", 0, items, cut);
        return new JSONObject().put("items", items).put("cut", cut[0]).toString();
    }

    private void walk(AccessibilityNodeInfo node, String path, int depth, JSONArray items, boolean[] cut) throws Exception {
        if (depth > MAX_DEPTH) return;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (items.length() >= MAX_ITEMS) { cut[0] = true; return; }
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null || !c.isVisibleToUser()) continue;
            String p = path.isEmpty() ? String.valueOf(i) : path + "/" + i;
            String label = labelOf(c);
            boolean useful = c.isClickable() || c.isEditable() || c.isScrollable() || c.isCheckable() || !label.isEmpty();
            if (useful) {
                JSONObject o = new JSONObject();
                o.put("path", p).put("cls", simpleClass(c)).put("name", label)
                        .put("click", c.isClickable() || c.isCheckable()).put("edit", c.isEditable())
                        .put("scroll", c.isScrollable()).put("checked", c.isCheckable() && c.isChecked())
                        .put("checkable", c.isCheckable()).put("enabled", c.isEnabled()).put("password", c.isPassword());
                if (c.isEditable() && !c.isPassword() && c.getText() != null) o.put("value", clip(c.getText().toString(), 120));
                items.put(o);
            }
            walk(c, p, depth + 1, items, cut);
        }
    }

    private AccessibilityNodeInfo byPath(AccessibilityNodeInfo root, String path) throws Exception {
        if (!path.matches("^\\d+(/\\d+)*$")) throw new Exception("Bad element path.");
        AccessibilityNodeInfo cur = root;
        for (String s : path.split("/")) {
            int i = Integer.parseInt(s);
            if (i >= cur.getChildCount()) throw new Exception("That control is gone. Take a new snapshot.");
            cur = cur.getChild(i);
            if (cur == null) throw new Exception("That control is gone. Take a new snapshot.");
        }
        return cur;
    }

    // ---------- acting ----------

    String act(String pkg, String path, String expect, String action, String text, int amount) throws Exception {
        if (action.equals("back")) {
            appRoot(pkg);
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw new Exception("Back did not work.");
            return "ok";
        }
        AccessibilityNodeInfo root = appRoot(pkg);
        AccessibilityNodeInfo node = byPath(root, path);
        if (expect != null && !expect.isEmpty() && !expect.equals(simpleClass(node) + "|" + labelOf(node))) {
            throw new Exception("The screen changed since the snapshot. Take a new snapshot.");
        }
        switch (action) {
            case "click": {
                AccessibilityNodeInfo t = node;
                for (int up = 0; up < 5 && t != null && !t.isClickable() && !t.isCheckable(); up++) t = t.getParent();
                if (t == null) throw new Exception("This control cannot be clicked.");
                if (!t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new Exception("The control did not react to the click.");
                return "ok";
            }
            case "settext": {
                if (!node.isEditable()) throw new Exception("This control is not a text field.");
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) throw new Exception("The text could not be entered.");
                return "ok";
            }
            case "scroll_down":
            case "scroll_up": {
                int act = action.equals("scroll_down") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                AccessibilityNodeInfo t = node;
                for (int up = 0; up < 5 && t != null && !t.isScrollable(); up++) t = t.getParent();
                if (t == null) throw new Exception("This control cannot be scrolled.");
                for (int k = 0; k < Math.min(Math.max(amount, 1), 10); k++) {
                    if (!t.performAction(act)) break;
                    Thread.sleep(250);
                }
                return "ok";
            }
            default:
                throw new Exception("Unknown action.");
        }
    }

    // ---------- screenshot of the screen while the chosen app is in front ----------

    String screenshot(String pkg) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new Exception("Screenshots need Android 11 or newer.");
        appRoot(pkg);
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                try {
                    HardwareBuffer hb = result.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    bitmap.set(hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false));
                    hb.close();
                } catch (Exception e) { error.set(String.valueOf(e.getMessage())); }
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) { error.set("Screenshot failed (code " + errorCode + ")."); latch.countDown(); }
        });
        if (!latch.await(6, TimeUnit.SECONDS)) throw new Exception("The screenshot timed out.");
        Bitmap b = bitmap.get();
        if (b == null) throw new Exception(error.get() == null ? "No screenshot." : error.get());
        int w = b.getWidth(), h = b.getHeight();
        shotFullW = w; shotFullH = h;
        float scale = Math.min(1f, 720f / Math.min(w, h));
        if (scale < 1f) b = Bitmap.createScaledBitmap(b, Math.round(w * scale), Math.round(h * scale), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, out);
        return new JSONObject().put("w", b.getWidth()).put("h", b.getHeight())
                .put("b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).toString();
    }

    // ---------- acting by screen position (screenshot mode) ----------

    /** A point of the latest screenshot (w x h pixels) in screen pixels */
    private int[] toScreen(double x, double y, int w, int h) throws Exception {
        if (shotFullW <= 0 || w <= 0 || h <= 0) throw new Exception("Take a screenshot first, then use its coordinates.");
        if (x < 0 || y < 0 || x > w || y > h) throw new Exception("That point is outside the screenshot (" + w + "x" + h + ").");
        return new int[]{ (int) Math.round(x * shotFullW / w), (int) Math.round(y * shotFullH / h) };
    }

    /** The window that is on top at this screen point (windows are checked from the top layer down) */
    private AccessibilityWindowInfo windowAt(int x, int y) {
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws == null) return null;
        AccessibilityWindowInfo best = null;
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityWindowInfo w : ws) {
            w.getBoundsInScreen(r);
            if (r.contains(x, y) && (best == null || w.getLayer() > best.getLayer())) best = w;
        }
        return best;
    }

    /** The chosen app's window at this point, or an error (another app, the keyboard, the system bars) */
    private AccessibilityNodeInfo appRootAt(String pkg, int x, int y) throws Exception {
        AccessibilityWindowInfo w = windowAt(x, y);
        AccessibilityNodeInfo root = w == null ? null : w.getRoot();
        CharSequence p = root == null ? null : root.getPackageName();
        if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || p == null || !pkg.equals(p.toString())) {
            throw new Exception("That point is not on the chosen app (it is on another app, the keyboard or a system bar). Take a new screenshot.");
        }
        return root;
    }

    private static AccessibilityNodeInfo deepestAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        if (depth < MAX_DEPTH) {
            for (int i = n.getChildCount() - 1; i >= 0; i--) {         // later children are drawn on top
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null || !c.isVisibleToUser()) continue;
                AccessibilityNodeInfo d = deepestAt(c, x, y, depth + 1);
                if (d != null) return d;
            }
        }
        return n;
    }

    /** What lies at a screenshot point: whether it is on the chosen app, and the label of the control there */
    String probe(String pkg, double x, double y, int w, int h) throws Exception {
        int[] p = toScreen(x, y, w, h);
        AccessibilityNodeInfo node = deepestAt(appRootAt(pkg, p[0], p[1]), p[0], p[1], 0);
        String label = "";
        boolean password = false, editable = false;
        for (AccessibilityNodeInfo t = node; t != null && label.isEmpty(); t = t.getParent()) {
            label = labelOf(t);
            password |= t.isPassword();
            editable |= t.isEditable();
        }
        return new JSONObject().put("label", label).put("cls", node == null ? "" : simpleClass(node))
                .put("password", password).put("editable", editable).toString();
    }

    private void gesture(android.graphics.Path path, long ms) throws Exception {
        android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, ms)).build();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription d) { ok[0] = true; done.countDown(); }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription d) { done.countDown(); }
        }, null);
        if (!sent || !done.await(ms + 3000, TimeUnit.MILLISECONDS) || !ok[0]) throw new Exception("The touch did not go through.");
    }

    /** Tap (or long-press) at a screenshot point on the chosen app */
    String tap(String pkg, double x, double y, int w, int h, boolean longPress) throws Exception {
        int[] p = toScreen(x, y, w, h);
        appRootAt(pkg, p[0], p[1]);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(p[0], p[1]);
        gesture(path, longPress ? 700 : 60);
        return "ok";
    }

    /** Swipe between two screenshot points; both ends must be on the chosen app */
    String swipe(String pkg, double x1, double y1, double x2, double y2, int w, int h, int ms) throws Exception {
        int[] a = toScreen(x1, y1, w, h), b = toScreen(x2, y2, w, h);
        appRootAt(pkg, a[0], a[1]);
        appRootAt(pkg, b[0], b[1]);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(a[0], a[1]);
        path.lineTo(b[0], b[1]);
        gesture(path, Math.min(Math.max(ms, 100), 2000));
        return "ok";
    }

    /** Put text into the field that has the input focus in the chosen app. Password fields need allowPassword. */
    String typeFocused(String pkg, String text, boolean allowPassword) throws Exception {
        appRoot(pkg);
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || f.getPackageName() == null || !pkg.equals(f.getPackageName().toString()) || !f.isEditable()) {
            throw new Exception("No text field of the chosen app has the focus. Tap the field first.");
        }
        if (f.isPassword() && !allowPassword) throw new Exception("PASSWORD_FIELD");
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) throw new Exception("The text could not be entered.");
        return "ok";
    }

    /** Brings the chosen app to the front (only the app the user picked) */
    static void openApp(Context c, String pkg) throws Exception {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) throw new Exception("That app cannot be opened.");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
    }
}
