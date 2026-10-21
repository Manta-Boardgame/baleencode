package com.deepseek.chat;

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
 * Lets the assistant use ONE app that the user chose, through Android's accessibility interface (the one screen
 * readers use). The user turns this service on in Settings; Android shows its own warning when doing so.
 * Every operation is tied to the chosen app's package: if another app is in front, nothing is read or touched.
 * No taps by coordinates are made; controls are pressed through their accessibility actions.
 */
public class DeviceControlService extends AccessibilityService {
    static volatile DeviceControlService instance;
    private static final int MAX_ITEMS = 400;
    private static final int MAX_DEPTH = 30;

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
        float scale = Math.min(1f, 720f / Math.min(w, h));
        if (scale < 1f) b = Bitmap.createScaledBitmap(b, Math.round(w * scale), Math.round(h * scale), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, out);
        return new JSONObject().put("w", b.getWidth()).put("h", b.getHeight())
                .put("b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).toString();
    }

    /** Brings the chosen app to the front (only the app the user picked) */
    static void openApp(Context c, String pkg) throws Exception {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) throw new Exception("That app cannot be opened.");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
    }
}
