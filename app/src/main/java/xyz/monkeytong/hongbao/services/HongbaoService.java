package xyz.monkeytong.hongbao.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

import xyz.monkeytong.hongbao.utils.HongbaoLog;
import xyz.monkeytong.hongbao.utils.HongbaoSignature;
import xyz.monkeytong.hongbao.utils.PowerUtil;

/**
 * 微信自动抢红包服务。
 *
 * 已适配微信 8.x：
 * - 拆红包页面新增 LuckyMoneyNewReceiveUI / LuckyMoneyNotHookReceiveUI，统一按类名关键字匹配；
 * - 拆红包按钮由 Button 改为 ImageView/Button + “開”文案，改为遍历节点按关键字识别；
 * - 不再通过 PackageManager 查询 Activity（Android 11+ 包可见性限制会导致查询失败），直接使用事件类名；
 * - 点击失效时使用 dispatchGesture 在按钮中心补点，替代旧的固定像素坐标。
 *
 * Adapted by HDB-Studio.
 */
public class HongbaoService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "HongbaoService";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    /* 总开关：无障碍已开启时，是否真正自动抢红包（主界面按钮切换） */
    public static final String PREF_ENABLED = "pref_enabled";

    /* 红包详情页/失败提示文案 */
    private static final String WECHAT_DETAILS_EN = "Details";
    private static final String WECHAT_DETAILS_CH = "红包详情";
    private static final String WECHAT_BETTER_LUCK_EN = "Better luck next time!";
    private static final String WECHAT_BETTER_LUCK_CH = "手慢了";
    private static final String WECHAT_EXPIRES_CH = "已超过24小时";

    /* 聊天页红包气泡文案（微信 8.0.6x 起统一为「微信红包」） */
    private static final String WECHAT_VIEW_OTHERS_CH = "领取红包";
    private static final String WECHAT_PACKET_CH = "微信红包";
    private static final String WECHAT_PACKET_TW = "微信紅包";
    private static final String WECHAT_PACKET_OPENED_CH = "已领取";
    private static final String WECHAT_PACKET_OPENED_TW = "已領取";
    private static final String WECHAT_NOTIFICATION_TIP = "[微信红包]";

    /* 微信 6.x ~ 8.x 的页面类名关键字 */
    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = "ReceiveUI";
    private static final String WECHAT_LUCKMONEY_DETAIL_ACTIVITY = "DetailUI";
    private static final String WECHAT_LUCKMONEY_GENERAL_ACTIVITY = "LauncherUI";
    /* 8.0.6x 聊天页为 .ui.chatting.variants.ChattingMainUI，旧版为 .ui.chatting.ChattingUI */
    private static final String WECHAT_LUCKMONEY_CHATTING_ACTIVITY = "Chatting";
    private static final String WECHAT_LUCKMONEY_PLUGIN = ".plugin.luckymoney.";

    private String currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;

    private AccessibilityNodeInfo rootNodeInfo;
    private AccessibilityNodeInfo mReceiveNode, mUnpackNode;
    private boolean mLuckyMoneyReceived;
    private int mUnpackCount = 0;
    private boolean mMutex = false, mListMutex = false, mChatMutex = false;
    private boolean mOpenScheduled = false;
    private long mLastOpenAttempt = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HongbaoSignature signature = new HongbaoSignature();

    private static HongbaoService instance;

    private PowerUtil powerUtil;
    private SharedPreferences sharedPreferences;

    private boolean loggedFirstEvent = false;
    private String lastWindowKey;
    private long lastReceiveDump = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        HongbaoLog.init(this);
        HongbaoLog.d("service", "onCreate 服务已创建");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (sharedPreferences == null) return;

        final CharSequence packageName = event.getPackageName();
        final String pkg = packageName == null ? "" : packageName.toString();

        if (!loggedFirstEvent) {
            loggedFirstEvent = true;
            HongbaoLog.d("event", "收到首个无障碍事件: " + pkg + "/" + event.getClassName()
                    + " type=" + event.getEventType());
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String key = pkg + "/" + event.getClassName();
            if (!key.equals(lastWindowKey)) {
                lastWindowKey = key;
                HongbaoLog.d("event", "窗口切换 " + key);
            }
        }

        /* 只处理微信；日志里保留窗口切换，便于排查微信分身/工作资料等异常情况 */
        if (!pkg.startsWith(WECHAT_PACKAGE)) return;

        setCurrentActivityName(event);

        /* 总开关关闭：保留页面/窗口日志，但不再做任何点击 */
        if (!sharedPreferences.getBoolean(PREF_ENABLED, true)) return;

        /* 检测通知消息 */
        if (!mMutex) {
            if (sharedPreferences.getBoolean("pref_watch_notification", false) && watchNotifications(event)) return;
            if (sharedPreferences.getBoolean("pref_watch_list", false) && watchList(event)) return;
            mListMutex = false;
        }

        if (!mChatMutex) {
            mChatMutex = true;
            try {
                if (sharedPreferences.getBoolean("pref_watch_chat", false)) watchChat(event);
            } finally {
                mChatMutex = false;
            }
        }
    }

    private void watchChat(AccessibilityEvent event) {
        this.rootNodeInfo = getRootInActiveWindow();
        if (rootNodeInfo == null) return;

        CharSequence rootPackage = rootNodeInfo.getPackageName();
        if (rootPackage == null || !WECHAT_PACKAGE.contentEquals(rootPackage)) return;

        mReceiveNode = null;
        mUnpackNode = null;

        checkNodeInfo(event.getEventType());

        /* 如果已经接收到红包并且还没有戳开 */
        if (mLuckyMoneyReceived && mReceiveNode != null) {
            mMutex = true;
            if (clickNode(mReceiveNode)) {
                mLuckyMoneyReceived = false;
                HongbaoLog.d("chat", "红包气泡点击成功，等待跳转拆红包页");
                /* 兜底：避免页面没有跳转时一直阻塞通知/列表检测 */
                handler.postDelayed(() -> mMutex = false, 10000);
            } else {
                HongbaoLog.d("chat", "红包气泡点击失败: " + describeNode(mReceiveNode));
                mMutex = false;
            }
        }

        /* 如果戳开但还未领取 */
        if (mUnpackCount >= 1 && mUnpackNode != null && !mOpenScheduled
                && System.currentTimeMillis() - mLastOpenAttempt > 1500) {
            mOpenScheduled = true;
            mLastOpenAttempt = System.currentTimeMillis();

            final AccessibilityNodeInfo target = mUnpackNode;
            int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;

            handler.postDelayed(() -> {
                mOpenScheduled = false;
                if (!sharedPreferences.getBoolean(PREF_ENABLED, true)) {
                    HongbaoLog.d("open", "总开关已关闭，取消拆红包");
                    resetState();
                    return;
                }
                try {
                    openPacket(target);
                } catch (Exception e) {
                    HongbaoLog.e("open", "openPacket 异常", e);
                    resetState();
                }
            }, delayFlag);
        }
    }

    private void openPacket(AccessibilityNodeInfo node) {
        if (node == null || (!isReceiveActivity(currentActivityName)
                && !hasOpenButtonMarker(getRootInActiveWindow(), 0))) {
            HongbaoLog.d("open", "跳过拆红包：页面已离开或节点为空, activity=" + currentActivityName);
            resetState();
            return;
        }

        HongbaoLog.d("open", "准备拆红包: " + describeNode(node));
        boolean clicked = false;
        AccessibilityNodeInfo clickable = findClickableAncestor(node);
        if (clickable != null) {
            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            HongbaoLog.d("open", "点击可点击祖先=" + clicked + " -> " + describeNode(clickable));
        }
        if (!clicked) {
            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            HongbaoLog.d("open", "直接点击节点=" + clicked);
        }
        if (!clicked) {
            clicked = tapCenter(node);
            HongbaoLog.d("open", "手势点击=" + clicked);
        }

        HongbaoLog.d("open", "拆红包结果 clicked=" + clicked);
        if (!clicked) {
            dumpWindow("open-failed");
        }
        resetState();
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;

        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());

        GestureDescription gestureDescription = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();

        try {
            return dispatchGesture(gestureDescription, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private void setCurrentActivityName(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        if (packageName == null || className == null) return;
        if (!WECHAT_PACKAGE.contentEquals(packageName)) return;

        String name = className.toString();
        /* 微信 8.0.6x 的拆红包页类名是 .plugin.luckymoney.ui.LuckyMoney，不含 UI/Activity，需要一并放行 */
        if (name.startsWith(WECHAT_PACKAGE)
                && !name.contains("Dialog")
                && (name.contains("UI") || name.contains("Activity")
                    || name.contains("luckymoney") || name.contains("LuckyMoney"))) {
            if (!name.equals(currentActivityName)) {
                HongbaoLog.d("page", "当前微信页面: " + name);
            }
            currentActivityName = name;
        }
    }

    private boolean watchList(AccessibilityEvent event) {
        if (mListMutex) return false;
        mListMutex = true;

        AccessibilityNodeInfo eventSource = event.getSource();
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventSource == null)
            return false;

        List<AccessibilityNodeInfo> nodes = eventSource.findAccessibilityNodeInfosByText(WECHAT_NOTIFICATION_TIP);

        if (!nodes.isEmpty() && isGeneralActivity(currentActivityName)) {
            AccessibilityNodeInfo nodeToClick = nodes.get(0);
            if (nodeToClick == null) return false;

            CharSequence contentDescription = nodeToClick.getContentDescription();
            if (contentDescription != null && !signature.getContentDescription().equals(contentDescription)) {
                boolean clicked = clickNode(nodeToClick);
                HongbaoLog.d("list", "会话列表红包点击=" + clicked + " desc=" + shortText(contentDescription));
                signature.setContentDescription(contentDescription.toString());
                return true;
            }
        }
        return false;
    }

    private boolean watchNotifications(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            return false;

        List<CharSequence> texts = event.getText();
        if (texts == null) return false;
        String tip = texts.toString();
        if (!tip.contains(WECHAT_NOTIFICATION_TIP)) return true;

        Parcelable parcelable = event.getParcelableData();
        if (parcelable instanceof Notification) {
            Notification notification = (Notification) parcelable;
            try {
                /* 清除signature,避免进入会话后误判 */
                signature.cleanSignature();
                if (notification.contentIntent != null) {
                    notification.contentIntent.send();
                    HongbaoLog.d("notification", "通知栏红包，已自动打开会话");
                }
            } catch (PendingIntent.CanceledException e) {
                HongbaoLog.e("notification", "打开通知失败", e);
            }
        }
        return true;
    }

    @Override
    public void onInterrupt() {
        HongbaoLog.d("service", "onInterrupt 无障碍服务被中断");
    }

    /**
     * 在当前页面遍历查找拆红包按钮。
     */
    private AccessibilityNodeInfo findOpenButton(AccessibilityNodeInfo node) {
        return findOpenButton(node, 0);
    }

    private AccessibilityNodeInfo findOpenButton(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;

        if (isOpenButton(node)) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo button = findOpenButton(child, depth + 1);
            if (button != null) return button;
        }
        return null;
    }

    private boolean isOpenButton(AccessibilityNodeInfo node) {
        String className = String.valueOf(node.getClassName());
        CharSequence text = node.getText();
        CharSequence contentDescription = node.getContentDescription();

        if (isOpenKeyword(text) || isOpenKeyword(contentDescription)) {
            if (node.isClickable()
                    || "android.widget.Button".equals(className)
                    || "android.widget.ImageView".equals(className)) {
                return true;
            }
            CharSequence keyword = text != null ? text : contentDescription;
            return keyword != null && keyword.toString().trim().length() <= 4;
        }

        /* 不再把所有 Button 都当成拆红包按钮，避免误点页面上的「关闭」等按钮 */
        return false;
    }

    private boolean isOpenKeyword(CharSequence charSequence) {
        if (charSequence == null) return false;
        String text = charSequence.toString().trim();
        if (text.isEmpty()) return false;

        return text.equals("開") || text.equals("开") || text.equals("拆")
                || text.contains("拆红包") || text.contains("開紅包") || text.contains("开红包")
                || text.equalsIgnoreCase("Open") || text.equalsIgnoreCase("Open Red Packet");
    }

    /**
     * 页面中是否存在强特征的拆红包按钮文案（“開”等），用于在 Activity 类名未及时更新时兜底判断。
     */
    private boolean hasOpenButtonMarker(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return false;

        if (isStrongOpenMarker(node.getText()) || isStrongOpenMarker(node.getContentDescription())) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasOpenButtonMarker(node.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private boolean isStrongOpenMarker(CharSequence charSequence) {
        if (charSequence == null) return false;
        String text = charSequence.toString().trim();

        return text.equals("開") || text.equals("开") || text.equals("拆红包")
                || text.equals("開紅包") || text.equals("开红包")
                || text.equalsIgnoreCase("Open") || text.equalsIgnoreCase("Open Red Packet");
    }

    private void checkNodeInfo(int eventType) {
        if (this.rootNodeInfo == null) return;

        if (signature.commentString != null) {
            sendComment();
            signature.commentString = null;
        }

        /* 聊天会话窗口：找到最新的未拆红包气泡 */
        if (isChatActivity(currentActivityName) || isGeneralActivity(currentActivityName)) {
            boolean watchSelf = sharedPreferences.getBoolean("pref_watch_self", false);
            AccessibilityNodeInfo node1 = findLastPacketNode(watchSelf);
            if (node1 != null) {
                String excludeWords = sharedPreferences.getString("pref_watch_exclude_words", "");
                if (this.signature.generateSignature(node1, excludeWords)) {
                    AccessibilityNodeInfo clickable = findClickableAncestor(node1);
                    mReceiveNode = clickable != null ? clickable : node1;
                    mLuckyMoneyReceived = true;
                    HongbaoLog.d("chat", "发现红包气泡: " + this.signature
                            + " | 节点=" + describeNode(node1)
                            + " | 点击目标=" + describeNode(mReceiveNode));
                } else {
                    HongbaoLog.d("chat", "发现疑似红包但签名未通过（可能重复或已处理）: " + describeNode(node1));
                }
                return;
            }
        }

        /* 拆红包页面：遍历节点匹配“開/拆/领取/Open” */
        boolean receivePage = isReceiveActivity(currentActivityName)
                || (!isChatActivity(currentActivityName) && !isGeneralActivity(currentActivityName)
                    && hasOpenButtonMarker(this.rootNodeInfo, 0));
        if (receivePage) {
            AccessibilityNodeInfo node2 = findOpenButton(this.rootNodeInfo);
            if (node2 != null && (mUnpackNode == null || !mUnpackNode.equals(node2))) {
                mUnpackNode = node2;
                mUnpackCount += 1;
                HongbaoLog.d("receive", "发现拆红包按钮: " + describeNode(node2)
                        + " | activity=" + currentActivityName);
                return;
            }
            if (node2 == null) {
                long now = System.currentTimeMillis();
                if (now - lastReceiveDump > 8000) {
                    lastReceiveDump = now;
                    HongbaoLog.d("receive", "拆红包页未识别到按钮，自动抓取节点树供分析");
                    dumpWindow("receive-no-button");
                }
            }
        }

        /* 红包已被抢完 / 已拆开：返回聊天页 */
        boolean hasNodes = this.hasOneOfThoseNodes(
                WECHAT_BETTER_LUCK_CH, WECHAT_DETAILS_CH,
                WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH);

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && hasNodes
                && (isDetailActivity(currentActivityName) || isReceiveActivity(currentActivityName))) {
            HongbaoLog.d("page", "红包已拆/已抢完，返回聊天页: " + currentActivityName);
            resetState();
            performGlobalAction(GLOBAL_ACTION_BACK);
            signature.commentString = generateCommentString();
        }
    }

    private void resetState() {
        mMutex = false;
        mLuckyMoneyReceived = false;
        mUnpackCount = 0;
        mUnpackNode = null;
        mReceiveNode = null;
    }

    private void sendComment() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            AccessibilityNodeInfo nodeToInput = findEditableNode(root, 0);
            if (nodeToInput == null) return;

            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo
                    .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, signature.commentString);
            nodeToInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        } catch (Exception e) {
            // Not supported
        }
    }

    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        if ("android.widget.EditText".equals(String.valueOf(node.getClassName()))) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findEditableNode(node.getChild(i), depth + 1);
            if (result != null) return result;
        }
        return null;
    }

    private boolean hasOneOfThoseNodes(String... texts) {
        for (String text : texts) {
            if (text == null) continue;
            List<AccessibilityNodeInfo> nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 找到当前屏幕上最靠下的未领取红包气泡文本节点。
     *
     * 微信 8.0.6x 起气泡文案统一为「微信红包」，自己发的红包在右侧，别人发在左侧，
     * 因此用横坐标判断归属；已领取的气泡上会出现「已领取」标记，直接跳过。
     */
    private AccessibilityNodeInfo findLastPacketNode(boolean includeSelf) {
        if (rootNodeInfo == null) return null;

        Rect rootBounds = new Rect();
        rootNodeInfo.getBoundsInScreen(rootBounds);
        int screenWidth = rootBounds.width();

        int bottom = Integer.MIN_VALUE;
        AccessibilityNodeInfo lastNode = null;

        for (String text : new String[]{WECHAT_VIEW_OTHERS_CH, WECHAT_PACKET_CH, WECHAT_PACKET_TW}) {
            List<AccessibilityNodeInfo> nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);
            if (nodes == null || nodes.isEmpty()) continue;

            for (AccessibilityNodeInfo tempNode : nodes) {
                if (tempNode == null) continue;

                CharSequence nodeText = tempNode.getText();
                if (nodeText == null || !text.equals(nodeText.toString().trim())) continue;

                Rect bounds = new Rect();
                tempNode.getBoundsInScreen(bounds);
                if (bounds.bottom <= 0 || bounds.top < 0) continue;

                boolean isSelf = screenWidth > 0 && bounds.centerX() > screenWidth * 0.55;
                if (isSelf && !includeSelf) continue;
                if (isOpenedPacket(tempNode)) continue;

                if (bounds.bottom > bottom) {
                    bottom = bounds.bottom;
                    lastNode = tempNode;
                    signature.others = !isSelf;
                }
            }
        }
        return lastNode;
    }

    /**
     * 气泡容器里是否已有「已领取」标记（新版微信会在红包气泡上显示）。
     */
    private boolean isOpenedPacket(AccessibilityNodeInfo textNode) {
        AccessibilityNodeInfo container = textNode;
        for (int i = 0; i < 3 && container.getParent() != null; i++) {
            container = container.getParent();
        }
        return containsText(container, WECHAT_PACKET_OPENED_CH, 4)
                || containsText(container, WECHAT_PACKET_OPENED_TW, 4);
    }

    private boolean containsText(AccessibilityNodeInfo node, String text, int depth) {
        if (node == null || depth < 0) return false;

        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().contains(text)) return true;
        CharSequence description = node.getContentDescription();
        if (description != null && description.toString().contains(text)) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsText(node.getChild(i), text, depth - 1)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        AccessibilityNodeInfo clickable = findClickableAncestor(node);
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        return tapCenter(clickable != null ? clickable : node);
    }

    private String describeNode(AccessibilityNodeInfo node) {
        if (node == null) return "null";

        String id = null;
        try {
            id = node.getViewIdResourceName();
        } catch (Throwable ignored) {
        }

        Rect bounds = new Rect();
        try {
            node.getBoundsInScreen(bounds);
        } catch (Throwable ignored) {
        }

        return "{class=" + node.getClassName()
                + ", text='" + shortText(node.getText()) + "'"
                + ", desc='" + shortText(node.getContentDescription()) + "'"
                + (id != null ? ", id=" + id : "")
                + ", clickable=" + node.isClickable()
                + ", bounds=" + bounds.toShortString() + "}";
    }

    private String shortText(CharSequence charSequence) {
        if (charSequence == null) return "";
        String text = charSequence.toString().replace('\n', ' ').trim();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    /**
     * 延时抓取当前前台窗口节点树（用于诊断微信页面结构变化）。
     * 由主界面按钮在切换到微信后调用。
     */
    public static boolean requestCapture(final String reason) {
        final HongbaoService service = instance;
        if (service == null) return false;

        service.handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                service.dumpWindow(reason);
            }
        }, 5000);
        return true;
    }

    private void dumpWindow(String reason) {
        StringBuilder builder = new StringBuilder();
        builder.append("===== 节点抓取开始 (").append(reason)
                .append(") activity=").append(currentActivityName).append(" =====\n");

        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Throwable ignored) {
        }

        if (windows == null) {
            builder.append("窗口列表=null（未授予 flagRetrieveInteractiveWindows 或系统限制）\n");
        } else {
            builder.append("窗口列表 (共 ").append(windows.size()).append("):\n");
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                } catch (Throwable ignored) {
                }
                builder.append("  win type=").append(windowTypeName(window.getType()))
                        .append(", active=").append(window.isActive())
                        .append(", focused=").append(window.isFocused())
                        .append(", pkg=").append(windowRoot == null ? "null" : windowRoot.getPackageName())
                        .append(", root=").append(describeNode(windowRoot))
                        .append('\n');
            }
        }

        /* 优先抓取微信窗口：切到微信的瞬间可能被最近任务/系统浮层遮住，getRootInActiveWindow 会抓错 */
        AccessibilityNodeInfo root = null;
        String rootFrom = "active";
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                } catch (Throwable ignored) {
                }
                if (windowRoot == null) continue;
                CharSequence windowPackage = windowRoot.getPackageName();
                if (windowPackage != null && WECHAT_PACKAGE.contentEquals(windowPackage)) {
                    root = windowRoot;
                    rootFrom = "微信窗口";
                    break;
                }
            }
        }
        if (root == null) {
            try {
                root = getRootInActiveWindow();
            } catch (Throwable ignored) {
            }
        }

        if (root == null) {
            builder.append("root=null（可能未获得窗口内容权限，或当前界面为系统界面）\n");
            HongbaoLog.d("dump", builder.toString());
            return;
        }

        builder.append("选用 root 来源: ").append(rootFrom)
                .append(", pkg=").append(root.getPackageName()).append('\n');
        builder.append("root: ").append(describeNode(root)).append('\n');
        int[] budget = new int[]{600};
        dumpNode(root, 0, budget, builder);
        if (budget[0] <= 0) builder.append("...(节点过多，已截断)\n");
        builder.append("===== 节点抓取结束 =====");

        HongbaoLog.d("dump", builder.toString());
    }

    private String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APPLICATION";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "INPUT_METHOD";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYSTEM";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "ACCESSIBILITY_OVERLAY";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "SPLIT_SCREEN_DIVIDER";
            default:
                return String.valueOf(type);
        }
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth, int[] budget, StringBuilder builder) {
        if (node == null || depth > 50 || budget[0] <= 0) return;
        budget[0]--;

        for (int i = 0; i < depth; i++) builder.append("  ");
        builder.append(depth).append(' ').append(describeNode(node)).append('\n');

        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNode(node.getChild(i), depth + 1, budget, builder);
        }
    }

    private boolean isChatActivity(String name) {
        return name != null && name.contains(WECHAT_LUCKMONEY_CHATTING_ACTIVITY);
    }

    private boolean isGeneralActivity(String name) {
        return name != null && name.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY);
    }

    private boolean isReceiveActivity(String name) {
        if (name == null) return false;
        if (name.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)) return false;
        if (name.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)) return true;
        /* 8.0.6x 拆红包页为 .plugin.luckymoney.ui.LuckyMoney */
        return name.contains(WECHAT_LUCKMONEY_PLUGIN) && !name.contains("Detail");
    }

    private boolean isDetailActivity(String name) {
        if (name == null) return false;
        return name.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)
                || (name.contains(WECHAT_LUCKMONEY_PLUGIN) && name.contains("Detail"));
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        HongbaoLog.init(this);
        HongbaoLog.d("service", "onServiceConnected 无障碍服务已连接");

        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                HongbaoLog.d("service", "eventTypes=0x" + Integer.toHexString(info.eventTypes)
                        + " flags=0x" + Integer.toHexString(info.flags)
                        + " canRetrieveWindowContent=" + info.getCanRetrieveWindowContent());
            }
        } catch (Throwable t) {
            HongbaoLog.e("service", "读取服务信息失败", t);
        }

        this.watchFlagsFromPreference();
        logEnvironment();
    }

    private void watchFlagsFromPreference() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        this.powerUtil = new PowerUtil(this);
        boolean watchOnLockFlag = sharedPreferences.getBoolean("pref_watch_on_lock", false);
        this.powerUtil.handleWakeLock(watchOnLockFlag);

        HongbaoLog.d("prefs", "enabled=" + sharedPreferences.getBoolean(PREF_ENABLED, true)
                + ", watch_notification=" + sharedPreferences.getBoolean("pref_watch_notification", false)
                + ", watch_list=" + sharedPreferences.getBoolean("pref_watch_list", false)
                + ", watch_chat=" + sharedPreferences.getBoolean("pref_watch_chat", false)
                + ", open_delay=" + sharedPreferences.getInt("pref_open_delay", 0)
                + ", watch_self=" + sharedPreferences.getBoolean("pref_watch_self", false)
                + ", watch_on_lock=" + watchOnLockFlag);
    }

    private void logEnvironment() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.tencent.mm", 0);
            HongbaoLog.d("env", "微信版本: " + info.versionName + " (" + info.versionCode + ")");
        } catch (Throwable t) {
            HongbaoLog.d("env", "读取微信版本失败（可能未安装/使用分身）: " + t.getClass().getSimpleName());
        }

        HongbaoLog.d("env", "日志文件: " + HongbaoLog.path());
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (sharedPreferences != null && key != null) {
            HongbaoLog.d("prefs", "偏好变更 " + key + " -> " + sharedPreferences.getAll().get(key));
        }
        if ("pref_watch_on_lock".equals(key) && powerUtil != null) {
            boolean changedValue = sharedPreferences.getBoolean(key, false);
            this.powerUtil.handleWakeLock(changedValue);
        }
    }

    @Override
    public void onDestroy() {
        HongbaoLog.d("service", "onDestroy 服务销毁");
        if (powerUtil != null) {
            this.powerUtil.handleWakeLock(false);
        }
        instance = null;
        super.onDestroy();
    }

    private String generateCommentString() {
        if (!signature.others) return null;

        boolean needComment = sharedPreferences.getBoolean("pref_comment_switch", false);
        if (!needComment) return null;

        String[] wordsArray = sharedPreferences.getString("pref_comment_words", "").split(" +");
        if (wordsArray.length == 0) return null;

        boolean atSender = sharedPreferences.getBoolean("pref_comment_at", false);
        if (atSender) {
            return "@" + signature.sender + " " + wordsArray[(int) (Math.random() * wordsArray.length)];
        } else {
            return wordsArray[(int) (Math.random() * wordsArray.length)];
        }
    }
}
