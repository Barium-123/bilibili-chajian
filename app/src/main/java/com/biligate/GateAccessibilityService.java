package com.biligate;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只遮“视频流”的遮罩服务（v1.3）。
 *
 * 规则：
 *  1. 只盖内容列表那一块：上沿在顶部搜索栏/标签栏之下，下沿在底部导航之上（可再下探几 dp
 *     盖掉导航条上方那条缝），所以底部“首页 / 动态 / 发布 / 会员购 / 我的”始终可以点。
 *  2. 不是视频流页面就不盖：判断依据是“屏幕上有没有哔哩哔哩那条搜索栏”
 *     （要求横向也在屏幕内，避免 ViewPager 里缓存的首页被判成当前页），
 *     再加上“底部选中的 tab 是不是我的/动态/消息/会员购”。所以「我的」页面完全不受影响。
 *  3. 首页里的「关注」「推荐」等 tab 内容同样会盖住，但标签栏本身可以点。
 *  4. 切 tab 不会换 Activity，所以内容变化（TYPE_WINDOW_CONTENT_CHANGED）也会重新判定，
 *     并在 0.9 秒后再复查一次，防止拿到还没刷新的旧节点树。
 */
public class GateAccessibilityService extends AccessibilityService {

    public static final String ACTION_REFRESH = "com.biligate.action.REFRESH";

    private static final String TAG = "BiliGate";
    private static final long VERIFY_DELAY_MS = 900L;
    private static final long ANCHOR_RETRY_MS = 500L;
    private static final long CONTENT_THROTTLE_MS = 600L;
    private static final int ANCHOR_RETRIES = 6;

    /** 大陆版、国际版、HD 版、Play 版。 */
    private static final Set<String> BILI_PACKAGES = new HashSet<>(Arrays.asList(
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "tv.danmaku.bilibilihd",
            "tv.danmaku.biliplay"));

    /** 底部这几个 tab 不是视频流页面，选中时一律不遮。 */
    private static final Set<String> NON_FEED_TABS = new HashSet<>(Arrays.asList(
            "我的", "动态", "消息", "会员购", "个人中心", "我的主页"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable evaluateTask = new Runnable() {
        @Override
        public void run() {
            evaluateNow();
        }
    };
    private final Runnable recheckTask = new Runnable() {
        @Override
        public void run() {
            evaluateNow();
        }
    };

    /** 窗口 id -> 包名，用来判断“活跃窗口”属于谁（AccessibilityWindowInfo 没有包名接口）。 */
    private final Map<Integer, String> windowPackages = new HashMap<>();

    private WindowManager windowManager;
    private View shieldView;
    private WindowManager.LayoutParams shieldParams;
    private int shieldTop = -1;
    private int shieldBottom = -1;

    private String foregroundPackage;
    private String foregroundClass;

    private int anchorRetries;
    private int verifyCount;
    private long shieldShownAt;
    private long lastContentEvalAt;
    private boolean pendingHideCheck;
    private boolean overlayWarned;
    private String lastSeenClass;
    private Boolean lastVisibleState;

    private BroadcastReceiver refreshReceiver;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        registerRefreshReceiver();
        schedule(0L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String pkg = text(event.getPackageName());
            String cls = text(event.getClassName());
            rememberWindow(event.getWindowId(), pkg);

            if (isInputMethod(pkg, cls)) {
                // 键盘弹出/收起不代表离开了 App。
                return;
            }
            // 遮罩窗口、我们自己的弹窗都会产生窗口事件：忽略，不要因此撤遮罩。
            if (getPackageName().equals(pkg) && !isOurActivity(cls)) {
                return;
            }
            foregroundPackage = pkg;
            // 只信 Activity 的类名，避免对话框/弹窗把“首页”覆盖掉。
            if (isBiliApp(pkg) && cls != null && cls.contains("Activity")) {
                foregroundClass = cls;
            }
            schedule(0L);

        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            schedule(120L);

        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // 切底部 tab 不换 Activity，只能靠内容变化来重新判定。
            if (!isBiliApp(foregroundPackage)) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastContentEvalAt < CONTENT_THROTTLE_MS) {
                return;
            }
            lastContentEvalAt = now;
            schedule(220L);
            // 节点树可能还没刷新，0.9 秒后再复查一次。
            handler.removeCallbacks(recheckTask);
            handler.postDelayed(recheckTask, 900L);
        }
    }

    @Override
    public void onInterrupt() {
        // 不要在这里撤遮罩：部分 ROM 在窗口切换时会回调 onInterrupt。
        schedule(0L);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideShield();
        unregisterRefreshReceiver();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideShield();
        unregisterRefreshReceiver();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // -------------------------------------------------------------- the gate

    private void schedule(long delayMs) {
        handler.removeCallbacks(evaluateTask);
        handler.postDelayed(evaluateTask, delayMs);
    }

    private void evaluateNow() {
        try {
            doEvaluate();
        } catch (Exception e) {
            Log.w(TAG, "evaluate failed", e);
        }
    }

    private void doEvaluate() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
        if (!GatePrefs.get(this).isEnabled()) {
            hideShield();
            return;
        }

        String active = activeApplicationPackage();
        if (!isBiliApp(active)) {
            // 刚盖上遮罩的一瞬间，系统窗口信息可能还没稳定：先复查一次再撤。
            boolean justShown = shieldView != null
                    && SystemClock.uptimeMillis() - shieldShownAt < 1500L;
            if (justShown && !pendingHideCheck) {
                pendingHideCheck = true;
                schedule(250L);
                return;
            }
            pendingHideCheck = false;
            hideShield();
            return;
        }
        pendingHideCheck = false;

        if (foregroundClass != null && !foregroundClass.equals(lastSeenClass)) {
            lastSeenClass = foregroundClass;
            GatePrefs.get(this).setLastSeenClass(foregroundClass);
        }

        if (!isHomeActivity(foregroundClass)) {
            hideShield();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isBiliApp(text(root.getPackageName()))) {
            // 拿不到哔哩哔哩的节点树（可能拿到我们自己的遮罩层）：保持现状，稍后重试。
            if (anchorRetries < ANCHOR_RETRIES) {
                anchorRetries++;
                schedule(ANCHOR_RETRY_MS);
            }
            return;
        }
        anchorRetries = 0;

        Region region = computeRegion(root);
        if (region == null) {
            // 不是视频流页面（我的 / 动态 / 消息 / 会员购…）：不遮。
            hideShield();
            return;
        }

        boolean wasHidden = shieldView == null;
        showShield(region.top, region.bottom);

        if (wasHidden && shieldView != null) {
            shieldShownAt = SystemClock.uptimeMillis();
            if (verifyCount < 3) {
                verifyCount++;
                schedule(VERIFY_DELAY_MS);
            }
        }
    }

    /**
     * 计算要盖住的区域；返回 null 表示“这里不是视频流，别盖”。
     */
    private Region computeRegion(AccessibilityNodeInfo root) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        GatePrefs prefs = GatePrefs.get(this);

        // 1) 屏幕上那条搜索栏是“视频流页面”的标志；我的页面没有它。
        Rect anchor = findSearchAnchor(root);
        if (anchor == null) {
            return null;
        }
        // 2) 明确选中了非首页 tab 时也不遮。
        if (isNonFeedTabSelected(root)) {
            return null;
        }

        int statusBar = statusBarHeight();
        int top;
        int bottom;

        if (prefs.isAutoInset()) {
            int barTop = findBottomBarTop(root, screenWidth, screenHeight);
            int fallbackBottom = screenHeight - dp(prefs.manualBottomDp());

            top = anchor.bottom + dp(6);
            Rect feed = findFeedBounds(root, screenWidth, screenHeight, anchor,
                    barTop > 0 ? barTop : fallbackBottom);
            if (feed != null) {
                top = Math.max(top, feed.top);
            }
            // 导航条上方常有一条透明留白，往下多盖 tuck dp 把缝压掉。
            bottom = barTop > 0 ? barTop + dp(prefs.bottomTuckDp()) : fallbackBottom;
        } else {
            top = statusBar + dp(prefs.manualInsetDp());
            bottom = screenHeight - dp(prefs.manualBottomDp());
        }

        top = Math.max(top, statusBar + dp(24));
        top = Math.min(top, screenHeight / 2);
        bottom = Math.min(bottom, screenHeight);
        if (bottom - top < dp(120)) {
            return null;
        }
        return new Region(top, bottom);
    }

    private static final class Region {
        final int top;
        final int bottom;

        Region(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    // --------------------------------------------------- 内容区 / 底部导航识别

    /**
     * 找内容列表（RecyclerView / ViewPager / ListView / ScrollView）的矩形。
     */
    private Rect findFeedBounds(AccessibilityNodeInfo root, int screenWidth,
                                int screenHeight, Rect anchor, int barTop) {
        int minHeight = (int) Math.max(dp(120), (barTop - anchor.bottom) * 0.35f);
        Rect best = null;
        long bestArea = 0;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited < 450) {
                AccessibilityNodeInfo node = queue.poll();
                visited++;
                if (node == null) {
                    continue;
                }
                CharSequence cls = node.getClassName();
                String name = cls == null ? "" : cls.toString();
                boolean scrollable = name.contains("RecyclerView")
                        || name.contains("ViewPager")
                        || name.contains("ListView")
                        || (name.contains("ScrollView") && !name.contains("Horizontal"));
                if (scrollable) {
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    if (r.width() >= screenWidth * 0.8
                            && r.height() >= minHeight
                            && r.left >= -dp(2)
                            && r.right <= screenWidth + dp(2)
                            && r.top >= anchor.bottom - dp(8)
                            && r.top <= screenHeight * 0.5
                            && r.bottom > anchor.bottom) {
                        long area = (long) r.width() * r.height();
                        if (area > bestArea) {
                            bestArea = area;
                            best = r;
                        }
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "feed scan stopped", e);
        }
        return best;
    }

    /**
     * 找底部导航条的上沿。
     */
    private int findBottomBarTop(AccessibilityNodeInfo root, int screenWidth, int screenHeight) {
        List<Rect> candidates = new ArrayList<>();
        int maxBottom = -1;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited < 450) {
                AccessibilityNodeInfo node = queue.poll();
                visited++;
                if (node == null) {
                    continue;
                }
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() >= screenWidth * 0.9
                        && r.height() >= dp(44)
                        && r.height() <= dp(130)
                        && r.top >= screenHeight * 0.60
                        && r.bottom >= screenHeight - dp(200)) {
                    candidates.add(r);
                    if (r.bottom > maxBottom) {
                        maxBottom = r.bottom;
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bar scan stopped", e);
        }

        int bestTop = -1;
        for (Rect r : candidates) {
            if (r.bottom < maxBottom - dp(6)) {
                continue;
            }
            if (r.top > bestTop) {
                bestTop = r.top;
            }
        }
        return bestTop;
    }

    /** 底部选中的 tab 是不是「我的 / 动态 / 消息 / 会员购」。 */
    private boolean isNonFeedTabSelected(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited < 450) {
                AccessibilityNodeInfo node = queue.poll();
                visited++;
                if (node == null) {
                    continue;
                }
                if (node.isSelected() || node.isChecked()) {
                    String label = labelOf(node);
                    if (label != null && NON_FEED_TABS.contains(label.trim())) {
                        return true;
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Exception ignored) {
            // 节点失效
        }
        return false;
    }

    private String labelOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return text.toString();
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            return desc.toString();
        }
        try {
            int count = Math.min(node.getChildCount(), 3);
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) {
                    continue;
                }
                CharSequence childText = child.getText();
                if (childText != null && childText.length() > 0) {
                    return childText.toString();
                }
            }
        } catch (Exception ignored) {
            // 节点失效
        }
        return null;
    }

    // ------------------------------------------------------------- the shield

    private void applyShieldTheme(View root) {
        boolean dark = GatePrefs.get(this).isDarkShield();
        root.setBackgroundColor(getColor(
                dark ? R.color.shield_bg_dark : R.color.shield_bg_light));

        ImageView art = root.findViewById(R.id.shieldArt);
        art.setImageResource(dark ? R.drawable.shield_art_dark : R.drawable.shield_art_light);

        ((TextView) root.findViewById(R.id.shieldTitle)).setTextColor(getColor(
                dark ? R.color.shield_text_dark : R.color.shield_text_light));
        ((TextView) root.findViewById(R.id.shieldSub)).setTextColor(getColor(
                dark ? R.color.shield_sub_dark : R.color.shield_sub_light));
        ((TextView) root.findViewById(R.id.shieldFoot)).setTextColor(getColor(
                dark ? R.color.shield_foot_dark : R.color.shield_foot_light));
        root.findViewById(R.id.shieldDivider).setBackgroundColor(getColor(
                dark ? R.color.shield_line_dark : R.color.shield_line_light));
    }

    private void showShield(int top, int bottom) {
        if (windowManager == null) {
            return;
        }
        int height = bottom - top;
        if (height <= 0) {
            return;
        }
        if (shieldView != null) {
            if (shieldParams != null && (shieldTop != top || shieldBottom != bottom)) {
                shieldParams.y = top;
                shieldParams.height = height;
                shieldTop = top;
                shieldBottom = bottom;
                try {
                    windowManager.updateViewLayout(shieldView, shieldParams);
                } catch (Exception ignored) {
                    // 已经脱离窗口
                }
            }
            return;
        }
        try {
            View view = LayoutInflater.from(this).inflate(R.layout.view_shield, null);
            applyShieldTheme(view);
            // 吞掉这块区域里的全部手势：不能点、不能滑、不能下拉刷新。
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    return true;
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.y = top;

            windowManager.addView(view, lp);
            shieldView = view;
            shieldParams = lp;
            shieldTop = top;
            shieldBottom = bottom;
            setVisibleState(true);
        } catch (Exception e) {
            Log.w(TAG, "cannot show shield", e);
            shieldView = null;
            shieldParams = null;
            shieldTop = -1;
            shieldBottom = -1;
            warnMissingOverlayOnce();
        }
    }

    private void hideShield() {
        View view = shieldView;
        shieldView = null;
        shieldParams = null;
        shieldTop = -1;
        shieldBottom = -1;
        anchorRetries = 0;
        pendingHideCheck = false;
        if (view != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Exception ignored) {
                // 已经移除
            }
        }
        if (view != null) {
            setVisibleState(false);
        }
    }

    private void setVisibleState(boolean visible) {
        if (lastVisibleState != null && lastVisibleState == visible) {
            return;
        }
        lastVisibleState = visible;
        GatePrefs.get(this).setShieldVisible(visible);
    }

    /** MIUI 上遮罩被系统拦住时，最可能的原因就是没给「显示悬浮窗」。只提示一次。 */
    private void warnMissingOverlayOnce() {
        if (overlayWarned) {
            return;
        }
        try {
            if (!Settings.canDrawOverlays(this)) {
                overlayWarned = true;
                Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
            // 查不到就算了
        }
    }

    // ------------------------------------------------------------ 前台判定

    private String activeApplicationPackage() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    if (!window.isActive()) {
                        continue;
                    }
                    String pkg = windowPackages.get(window.getId());
                    if (pkg != null) {
                        return pkg;
                    }
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) {
                        CharSequence name = root.getPackageName();
                        if (name != null) {
                            return name.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot inspect windows", e);
        }
        return foregroundPackage;
    }

    private void rememberWindow(int windowId, String pkg) {
        if (windowId < 0 || pkg == null) {
            return;
        }
        if (windowPackages.size() > 64) {
            windowPackages.clear();
        }
        windowPackages.put(windowId, pkg);
    }

    // --------------------------------------------------------- 节点识别

    /**
     * 找到哔哩哔哩自己的搜索栏矩形。
     *
     * 注意：必须整条都在屏幕横向范围内。ViewPager 里缓存的首页会被挪到屏幕外
     * （left 为负），如果只看宽度就会把「我的」误判成首页，遮罩就不会消失。
     */
    private Rect findSearchAnchor(AccessibilityNodeInfo root) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxTop = screenHeight / 3;

        List<Rect> candidates = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        try {
            while (!queue.isEmpty() && visited < 300) {
                AccessibilityNodeInfo node = queue.poll();
                visited++;
                if (node == null) {
                    continue;
                }
                if (node.isVisibleToUser() && looksLikeSearch(node)) {
                    Rect candidate = containerBounds(node, screenWidth, maxTop);
                    if (candidate != null && candidates.size() < 24) {
                        candidates.add(candidate);
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "anchor scan stopped", e);
        }

        // 优先最靠上的匹配；同一高度带里（搜索框 vs 连带标签栏的头部）取更矮的那个。
        Rect best = null;
        int band = dp(12);
        for (Rect rect : candidates) {
            if (best == null) {
                best = rect;
            } else if (rect.top > best.top + band) {
                continue;
            } else if (rect.top + band < best.top) {
                best = rect;
            } else if (rect.bottom < best.bottom) {
                best = rect;
            }
        }
        return best;
    }

    private boolean looksLikeSearch(AccessibilityNodeInfo node) {
        try {
            String id = node.getViewIdResourceName();
            if (id != null && id.toLowerCase(Locale.ROOT).contains("search")) {
                return true;
            }
            CharSequence text = node.getText();
            if (text != null && text.toString().contains("搜索")) {
                return true;
            }
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().contains("搜索")) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                CharSequence hint = node.getHintText();
                return hint != null && hint.toString().contains("搜索");
            }
        } catch (Exception ignored) {
            // 节点失效
        }
        return false;
    }

    private Rect containerBounds(AccessibilityNodeInfo node, int screenWidth, int maxTop) {
        Rect rect = new Rect();
        AccessibilityNodeInfo current = node;
        try {
            for (int depth = 0; depth < 5 && current != null; depth++) {
                current.getBoundsInScreen(rect);
                if (rect.width() > screenWidth / 2
                        && rect.height() >= dp(24)
                        && rect.height() <= dp(110)
                        && rect.left >= -dp(2)
                        && rect.right <= screenWidth + dp(2)
                        && rect.top >= 0
                        && rect.top < maxTop) {
                    return new Rect(rect);
                }
                current = current.getParent();
            }
        } catch (Exception ignored) {
            // 节点失效
        }
        return null;
    }

    // --------------------------------------------------------------- helpers

    private boolean isHomeActivity(String cls) {
        if (cls == null || !cls.contains("MainActivity")) {
            return false;
        }
        return !cls.contains("Search")
                && !cls.contains("Login")
                && !cls.contains("Setting")
                && !cls.contains("Splash");
    }

    private boolean isBiliApp(String pkg) {
        return pkg != null && BILI_PACKAGES.contains(pkg);
    }

    private boolean isOurActivity(String cls) {
        return cls != null && cls.startsWith(getPackageName() + ".");
    }

    private boolean isInputMethod(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls;
        return p.contains("inputmethod")
                || p.contains(".ime")
                || c.contains("InputMethod")
                || c.endsWith("IME");
    }

    private static String text(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return dp(24);
    }

    // ------------------------------------------------------------- broadcast

    private void registerRefreshReceiver() {
        if (refreshReceiver != null) {
            return;
        }
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                hideShield();
                schedule(0L);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
    }

    private void unregisterRefreshReceiver() {
        if (refreshReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Exception ignored) {
            // 已经注销
        }
        refreshReceiver = null;
    }
}
