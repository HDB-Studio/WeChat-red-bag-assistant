package xyz.monkeytong.hongbao.utils;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 红包签名。
 *
 * 旧实现假设红包气泡的父节点一定是 LinearLayout，且第一个子节点是文本节点。
 * 微信 7.x/8.x 之后气泡层级发生变化，这里改为从“领取红包/查看红包”文本节点出发，
 * 向上寻找真实的气泡容器，不再依赖固定的节点层级。
 *
 * Adapted by HDB-Studio.
 */
public class HongbaoSignature {
    public String sender, content, time, contentDescription = "", commentString;
    public boolean others;

    public boolean generateSignature(AccessibilityNodeInfo textNode, String excludeWords) {
        try {
            if (textNode == null) return false;

            CharSequence textCharSequence = textNode.getText();
            if (textCharSequence == null) return false;
            String hongbaoContent = textCharSequence.toString().trim();
            if (hongbaoContent.isEmpty()) return false;

            /* 用户设置的关键字过滤 */
            String[] excludeWordsArray = (excludeWords == null ? "" : excludeWords).split(" +");
            for (String word : excludeWordsArray) {
                if (word.length() > 0 && hongbaoContent.contains(word)) return false;
            }

            /* 找到气泡容器，并确保它位于屏幕内（避免滑动过程中重复触发） */
            AccessibilityNodeInfo bubbleNode = findBubbleContainer(textNode);
            Rect bounds = new Rect();
            bubbleNode.getBoundsInScreen(bounds);
            if (bounds.top < 0 || bounds.bottom <= 0) return false;

            String[] hongbaoInfo = getSenderContentDescriptionFromNode(bubbleNode);

            /* 用气泡顶部的坐标作为时间信息的补充，避免同一发送者连续发送同样祝福语时被误判为同一个红包 */
            String positionKey = String.valueOf(bounds.top / 10);
            if (this.getSignature(hongbaoInfo[0], hongbaoContent, positionKey).equals(this.toString())) {
                return false;
            }

            this.sender = hongbaoInfo[0];
            this.time = positionKey;
            this.content = hongbaoContent;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从文本节点向上寻找红包气泡容器。
     */
    private AccessibilityNodeInfo findBubbleContainer(AccessibilityNodeInfo textNode) {
        AccessibilityNodeInfo current = textNode;
        AccessibilityNodeInfo bubble = textNode;

        for (int i = 0; i < 5; i++) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;
            current = parent;

            String className = String.valueOf(parent.getClassName());
            if (parent.isClickable()
                    || className.contains("LinearLayout")
                    || className.contains("RelativeLayout")
                    || className.contains("FrameLayout")) {
                bubble = parent;
                break;
            }
        }
        return bubble;
    }

    /**
     * 在气泡及其附近的节点中寻找发送者头像（contentDescription）和时间文本。
     */
    private String[] getSenderContentDescriptionFromNode(AccessibilityNodeInfo node) {
        String sender = null;
        String time = null;

        AccessibilityNodeInfo current = node;
        for (int level = 0; level < 3 && current != null; level++) {
            int count = current.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo thisNode = current.getChild(i);
                if (thisNode == null) continue;
                String className = String.valueOf(thisNode.getClassName());

                if (sender == null && "android.widget.ImageView".equals(className)) {
                    CharSequence cd = thisNode.getContentDescription();
                    if (cd != null) {
                        String s = cd.toString().replaceAll("头像$", "").trim();
                        if (!s.isEmpty()) sender = s;
                    }
                } else if (time == null && "android.widget.TextView".equals(className)) {
                    CharSequence text = thisNode.getText();
                    if (text != null && isTimeLike(text.toString().trim())) {
                        time = text.toString().trim();
                    }
                }
            }
            current = current.getParent();
        }

        return new String[]{
                sender == null ? "unknownSender" : sender,
                time == null ? "unknownTime" : time
        };
    }

    private boolean isTimeLike(String text) {
        if (text.isEmpty()) return false;
        return text.matches(".*\\d{1,2}:\\d{2}.*")
                || text.contains("昨天")
                || text.contains("星期")
                || text.contains("月")
                || text.contains("日")
                || text.equalsIgnoreCase("AM")
                || text.equalsIgnoreCase("PM")
                || text.matches(".*[AP]M.*");
    }

    @Override
    public String toString() {
        return this.getSignature(this.sender, this.content, this.time);
    }

    private String getSignature(String... strings) {
        StringBuilder signature = new StringBuilder();
        for (String str : strings) {
            if (str == null) return null;
            signature.append(str).append("|");
        }
        signature.setLength(signature.length() - 1);
        return signature.toString();
    }

    public String getContentDescription() {
        return this.contentDescription;
    }

    public void setContentDescription(String description) {
        this.contentDescription = description;
    }

    public void cleanSignature() {
        this.content = "";
        this.time = "";
        this.sender = "";
    }
}
