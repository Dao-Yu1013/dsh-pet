package com.dshpet.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 常驻悬浮聊天窗：顶部固定屏幕 1/8，初始底部 1/4；
 * 随内容向下生长，底部最多到 1/2，之后消息区改为上下滑动。
 */
public final class ChatBox {
    /** Keep screen captures readable by vision models without sending full-resolution camera-sized frames. */
    private static final int SCREEN_CAPTURE_MAX_EDGE = 2048;
    public interface Host {
        void onClosed();

        void onLayout(int left, int top, int right, int bottom);

        void setPetVisible(boolean visible);

        /** 调起系统图片选择器；选完由宿主把图片回传给 setPendingImage。 */
        void pickImage();

        void onReply(String text);
    }

    private final Context c;
    private final WindowManager wm;
    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float d;
    /** 她联网找的表情下载完后，聊天窗已经关掉就不再处理 */
    private final Bus.Sink busSink;

    private FrameLayout root;
    private LinearLayout card;
    private LinearLayout head;
    private ScrollView msgScroll;
    private LinearLayout msgBox;
    private LinearLayout attachRow;
    private HorizontalScrollView stickerPanel;
    private ImageView attachThumb;
    private TextView attachText;
    private LinearLayout inputRow;
    private EditText input;
    private TextView sendBtn;
    private TextView stickerBtn;
    private TextView status;

    private WindowManager.LayoutParams wp;
    private int screenW, screenH, panelW, panelLeft, panelTop, maxH, baseH, targetH;
    private int winX, winY;
    private float dragOffX, dragOffY;
    private int lastInputLines = 1;
    private boolean open;
    private boolean busy;
    private boolean stickerPickerOpen;
    private int stickerPanelGrowth;
    private Bitmap pending;
    private String shotText = "";
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    public ChatBox(Context c, WindowManager wm, Host host) {
        this.c = c;
        this.wm = wm;
        this.host = host;
        this.d = c.getResources().getDisplayMetrics().density;
        this.busSink = (event, data) -> {
            if ("drawMessage".equals(event)) {
                String text = data.optString("text", "");
                if (!text.isEmpty()) h.post(() -> {
                    if (!open || msgBox == null) return;
                    addMessage("assistant", text, null, true);
                });
                return;
            }
            if ("sessionInsightCard".equals(event)) {
                String title = data.optString("cardTitle", "共同心得");
                JSONArray items = data.optJSONArray("cardItems");
                h.post(() -> { if (open && msgBox != null) addInsightCard(title, items); });
                return;
            }
            if (!"chatSticker".equals(event)) return;
            String name = data.optString("name", "");
            if (name.isEmpty()) return;
            Bitmap pic = Sticker.bitmap(c, name);
            if (pic == null) return;
            h.post(() -> {
                if (!open || msgBox == null) return;
                addMessage("assistant", "", pic, false);
            });
        };
    }

    public boolean isOpen() {
        return open;
    }

    public void show() {
        if (open) return;
        build();
        applyGeometry(true);
        try {
            wm.addView(root, wp);
        } catch (Throwable t) {
            root = null;
            return;
        }
        open = true;
        Bus.add(busSink);
        loadHistory();
        scrollBottom();
        host.onLayout(winX, winY, winX + panelW, winY + targetH);
    }

    public void reposition() {
        if (!open || root == null) return;
        applyGeometry(true);
        applyHeight(false);
        host.onLayout(winX, winY, winX + panelW, winY + targetH);
        scrollBottom();
    }

    public void dismiss() {
        if (!open) return;
        open = false;
        Bus.remove(busSink);
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        card = null;
        head = null;
        msgScroll = null;
        msgBox = null;
        attachRow = null;
        stickerPanel = null;
        attachThumb = null;
        attachText = null;
        inputRow = null;
        input = null;
        sendBtn = null;
        stickerBtn = null;
        stickerPickerOpen = false;
        stickerPanelGrowth = 0;
        status = null;
        pending = null;
        shotText = "";
        busy = false;
        host.onClosed();
    }

    private void applyGeometry(boolean resetHeight) {
        int[] ss = Screen.size(c);
        screenW = ss[0];
        screenH = ss[1];
        panelW = (int) (screenW * 0.78f);
        panelLeft = (screenW - panelW) / 2;
        panelTop = (int) (screenH * 0.125f);
        maxH = Math.max(dp(160), (int) (screenH * 0.50f) - panelTop);
        baseH = Math.min(maxH, Math.max(dp(130), (int) (screenH / 6f)));
        if (resetHeight) targetH = baseH;
        targetH = Math.max(baseH, Math.min(maxH, targetH));
        if (resetHeight) {
            winX = panelLeft;
            winY = panelTop;
        }
        if (wp == null) {
            wp = new WindowManager.LayoutParams(panelW, targetH, 2038,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            wp.gravity = Gravity.TOP | Gravity.LEFT;
            wp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        }
        wp.x = winX;
        wp.y = winY;
        wp.width = panelW;
        wp.height = targetH;
    }

    private void build() {
        root = new FrameLayout(c);

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF2161C2E, 0xF20B0F18});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x558B6CFF);
        card.setBackground(bg);
        card.setElevation(dp(16));
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View accent = new View(c);
        GradientDrawable ag = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x008B6CFF, 0xFF8B6CFF, 0xFF5EE6C5, 0x005EE6C5});
        ag.setCornerRadius(dp(1));
        accent.setBackground(ag);
        card.addView(accent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(4), 0, dp(3));

        TextView shot = new TextView(c);
        shot.setText("▣");
        shot.setTextColor(0xFFB9C3D8);
        shot.setTextSize(13);
        shot.setGravity(Gravity.CENTER);
        shot.setBackground(roundBg(0xFF1B2233, 0x22FFFFFF, 12));
        shot.setClickable(true);
        shot.setOnClickListener(v -> takeShot());
        head.addView(shot, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView pickPic = new TextView(c);
        pickPic.setText("图");
        pickPic.setTextColor(0xFFB9C3D8);
        pickPic.setTextSize(11);
        pickPic.setGravity(Gravity.CENTER);
        pickPic.setBackground(roundBg(0xFF1B2233, 0x22FFFFFF, 12));
        pickPic.setClickable(true);
        pickPic.setOnClickListener(v -> {
            if (busy) return;
            host.pickImage();
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(26), dp(26));
        plp.leftMargin = dp(4);
        head.addView(pickPic, plp);

        TextView title = new TextView(c);
        String name = "蓝色大肥鱼";
        try {
            name = Data.persona(c).optString("name", name);
        } catch (Throwable ignored) {
        }
        title.setText(name);
        title.setTextColor(0xFFF0F4FF);
        title.setTextSize(12.5f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(6), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        status = new TextView(c);
        status.setText("在线");
        status.setTextColor(0xFF5EE6C5);
        status.setTextSize(9f);
        status.setSingleLine(true);
        head.addView(status);

        TextView close = new TextView(c);
        close.setText("×");
        close.setTextColor(0xFFE6ECFA);
        close.setTextSize(17);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundBg(0xFF262E42, 0x22FFFFFF, 13));
        close.setClickable(true);
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(26), dp(26));
        clp.leftMargin = dp(6);
        head.addView(close, clp);
        card.addView(head);
        head.setOnTouchListener((v, e) -> {
            if (!open || wp == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragOffX = e.getRawX() - wp.x;
                    dragOffY = e.getRawY() - wp.y;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nx = (int) (e.getRawX() - dragOffX);
                    int ny = (int) (e.getRawY() - dragOffY);
                    nx = Math.max(0, Math.min(screenW - panelW, nx));
                    ny = Math.max(0, Math.min(screenH - targetH, ny));
                    winX = nx;
                    winY = ny;
                    wp.x = nx;
                    wp.y = ny;
                    try {
                        wm.updateViewLayout(root, wp);
                    } catch (Throwable ignored) {
                    }
                    host.onLayout(nx, ny, nx + panelW, ny + targetH);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });

        msgScroll = new ScrollView(c);
        msgScroll.setVerticalScrollBarEnabled(true);
        msgScroll.setScrollbarFadingEnabled(false);
        msgBox = new LinearLayout(c);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(0, dp(2), 0, dp(2));
        msgScroll.addView(msgBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(msgScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        attachRow = new LinearLayout(c);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setVisibility(View.GONE);
        attachThumb = new ImageView(c);
        attachThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        attachThumb.setBackground(roundBg(0xFF1B2233, 0x338B6CFF, 8));
        attachThumb.setClipToOutline(true);
        attachRow.addView(attachThumb, new LinearLayout.LayoutParams(dp(22), dp(22)));
        attachText = new TextView(c);
        attachText.setText("截图已插入");
        attachText.setTextColor(0xFF9EABC7);
        attachText.setTextSize(9f);
        attachText.setPadding(dp(6), 0, dp(6), 0);
        attachRow.addView(attachText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rm = new TextView(c);
        rm.setText("×");
        rm.setTextColor(0xFF9EABC7);
        rm.setTextSize(12);
        rm.setGravity(Gravity.CENTER);
        rm.setPadding(dp(6), dp(2), dp(6), dp(2));
        rm.setClickable(true);
        rm.setOnClickListener(v -> clearPending());
        attachRow.addView(rm);
        card.addView(attachRow);

        stickerPanel = new HorizontalScrollView(c);
        stickerPanel.setHorizontalScrollBarEnabled(false);
        stickerPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams stickerPanelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        stickerPanelLp.topMargin = dp(4);
        stickerPanelLp.bottomMargin = dp(4);
        card.addView(stickerPanel, stickerPanelLp);

        inputRow = new LinearLayout(c);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);

        LinearLayout inputBox = new LinearLayout(c);
        inputBox.setOrientation(LinearLayout.VERTICAL);
        inputBox.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable ibg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B0F1A, 0xFF111726});
        ibg.setCornerRadius(dp(14));
        ibg.setStroke(dp(1), 0x338B6CFF);
        inputBox.setBackground(ibg);

        input = new EditText(c);
        input.setBackground(null);
        input.setTextColor(0xFFEDF2FF);
        input.setHintTextColor(0xFF5D6880);
        input.setHint("和蓝色大肥鱼说点什么…");
        input.setTextSize(12);
        input.setMaxLines(3);
        input.setMinLines(1);
        input.setSingleLine(false);
        input.setPadding(0, dp(2), 0, dp(2));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fitInput();
            }
        });
        inputBox.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        inputRow.addView(inputBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stickerBtn = new TextView(c);
        stickerBtn.setText("☺");
        stickerBtn.setTextColor(0xFFB9C3D8);
        stickerBtn.setTextSize(17);
        stickerBtn.setGravity(Gravity.CENTER);
        stickerBtn.setContentDescription("选择并发送表情包");
        stickerBtn.setBackground(roundBg(0xFF1B2233, 0x338B6CFF, 18));
        stickerBtn.setClickable(true);
        stickerBtn.setOnClickListener(v -> toggleStickerPicker());
        LinearLayout.LayoutParams stickerBtnLp = new LinearLayout.LayoutParams(dp(34), dp(36));
        stickerBtnLp.leftMargin = dp(6);
        inputRow.addView(stickerBtn, stickerBtnLp);

        sendBtn = new TextView(c);
        sendBtn.setText("➤");
        sendBtn.setTextColor(0xFFFFFFFF);
        sendBtn.setTextSize(15);
        sendBtn.setGravity(Gravity.CENTER);
        GradientDrawable sbg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF9A7BFF, 0xFF6547FF});
        sbg.setShape(GradientDrawable.OVAL);
        sendBtn.setBackground(sbg);
        sendBtn.setClickable(true);
        sendBtn.setOnClickListener(v -> send());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(36), dp(36));
        slp.leftMargin = dp(6);
        inputRow.addView(sendBtn, slp);
        card.addView(inputRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void loadHistory() {
        try {
            JSONArray a = Data.chatList(c, 20);
            if (a.length() == 0) {
                addMessage("assistant", Data.persona(c).optString("greeting", "在呢，今天怎么样？"), null, false);
                return;
            }
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                boolean user = "user".equals(o.optString("role"));
                if (!user && "session_insights".equals(o.optString("cardType"))) {
                    addInsightCard(o.optString("cardTitle", "共同心得"), o.optJSONArray("cardItems"));
                    continue;
                }
                String txt = o.optString("content", "");
                Bitmap st = null;
                String stName = Sticker.pickName(txt);
                if (stName != null && !stName.isEmpty()) {
                    st = Sticker.bitmap(c, stName);
                    txt = Sticker.strip(txt);
                }
                // 聊天里发过的图片保留一个月，重新打开悬浮窗也还在
                String rel = o.optString("img", "");
                Bitmap pic = rel.isEmpty() ? null : chatImage(rel);
                addMessage(user ? "user" : "assistant", txt, pic != null ? pic : st, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 历史里的聊天图片：文件还在就解码，超过一个月被清掉就返回 null。 */
    private Bitmap chatImage(String rel) {
        try {
            java.io.File f = new java.io.File(Sg.dir(c), rel);
            if (!f.isFile()) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private void send() {
        if (busy) return;
        String text = input == null ? "" : input.getText().toString().trim();
        final boolean hadShot = pending != null;
        final Bitmap shotBmp = pending;
        final String shot = shotText == null ? "" : shotText.trim();
        if (text.isEmpty() && !hadShot) return;
        addMessage("user", text, pending, true);
        if (input != null) input.setText("");
        clearPending();
        if (!hadShot && StickerPainter.wantsDrawRequest(text)) {
            String reply = "我开始画啦，画好会自己发给你。你也可以随时问我画到哪了。";
            Data.chatAppend(c, "user", text);
            StickerPainter.startDirect(c, text);
            host.onReply(reply);
            done();
            return;
        }
        if (!hadShot) {
            JSONObject drawStatus = StickerPainter.statusFor(c, text);
            if (drawStatus != null) {
                String reply = drawStatus.optString("text", "我先看看现在画到哪一步了。" );
                String name = drawStatus.optString("name", "");
                Data.chatAppend(c, "user", text);
                Data.chatAppend(c, "assistant", reply);
                Bitmap current = name.isEmpty() ? null : Sticker.bitmap(c, name);
                addMessage("assistant", reply, current, true);
                if (!name.isEmpty()) Data.chatAppend(c, "assistant", "[[表情:" + name + "]]");
                host.onReply(reply);
                done();
                return;
            }
        }
        if (hadShot && wantsLink(text)) {
            String link = firstUrl(shot);
            if (!link.isEmpty()) {
                String reply = "找到了：" + link;
                Data.chatAppend(c, "user", text.isEmpty() ? "[截图] 提取链接" : text);
                Data.chatAppend(c, "assistant", reply);
                addMessage("assistant", reply, null, true);
                host.onReply(reply);
                return;
            }
        }
        if (!hadShot) {
            String device = DeviceAsk.answerIfAsked(c, text);
            if (device != null) {
                localReply(text, device);
                return;
            }
        }
        if (!Cfg.load(c).aiReady() && !PluginGen.intercept(c, text)) {
            addMessage("assistant", "还没绑定 API Key，先去应用里绑定一下吧。", null, true);
            return;
        }
        busy = true;
        if (sendBtn != null) sendBtn.setAlpha(0.45f);
        if (status != null) {
            status.setText("输入中…");
            status.setTextColor(0xFFFFCF7A);
        }
        // Explicitly addressed DSHBox requests go through the second runtime. Ordinary chat
        // and the Pet's native plugin lifecycle remain on the existing path.
        if (!hadShot && DshBoxBridge.wantsRemote(text)) {
            Data.chatAppend(c, "user", text);
            DshBoxBridge.ask(c, text, new Ai.Cb() {
                @Override public void ok(String reply) {
                    h.post(() -> {
                        Data.chatAppend(c, "assistant", reply);
                        addLines(reply, null);
                        host.onReply(reply);
                        done();
                    });
                }

                @Override public void fail(String err) {
                    h.post(() -> {
                        String message = err == null || err.trim().isEmpty()
                                ? "DSHBox 当前不可用，桌宠本机插件没有受到影响。" : err;
                        Data.chatAppend(c, "assistant", message);
                        addMessage("assistant", message, null, true);
                        done();
                    });
                }
            });
            return;
        }
        // 插件意图：让她现写/安装插件，走沙盒插件链路，不进入普通聊天
        if (!hadShot && PluginGen.intercept(c, text)) {
            Data.chatAppend(c, "user", text);
            PluginGen.talk(c, text, new Ai.Cb() {
                @Override
                public void ok(String reply) {
                    h.post(() -> {
                        Data.chatAppend(c, "assistant", reply);
                        addMessage("assistant", reply, null, true);
                        host.onReply(reply);
                        done();
                    });
                }

                @Override
                public void fail(String err) {
                    h.post(() -> {
                        String msg = err == null || err.trim().isEmpty()
                                ? "这个我先不会，换个说法再试试。" : err;
                        addMessage("assistant", msg, null, true);
                        done();
                    });
                }
            });
            return;
        }
        try {
            Ai.Cb cb = new Ai.Cb() {
                @Override
                public void ok(String reply) {
                    h.post(() -> {
                        String shown = Sticker.strip(reply);
                        String stName = Sticker.pickName(reply);
                        Bitmap st = stName.isEmpty() ? null : Sticker.bitmap(c, stName);
                        addLines(shown, st);
                        host.onReply(shown);
                        done();
                    });
                }

                @Override
                public void fail(String err) {
                    h.post(() -> {
                        addMessage("assistant", "（没连上）" + err, null, true);
                        done();
                    });
                }
            };
            if (hadShot) {
                String prompt = text.isEmpty()
                        ? (shot.isEmpty() ? "[图片] 看看这张图。" : "帮我看看这个截图。")
                        : text;
                Brain.chatExtra(c, prompt, shot, shotBmp, cb);
            } else if (ScreenAsk.wants(text)) {
                inspectCurrentScreen(text, cb, false);
            } else if (ScreenAsk.hasRecentScreenContext(c)) {
                // In a recent screen discussion, let the model decide whether this is a
                // screen follow-up; ordinary replies continue as chat without taking a screenshot.
                IntentRouter.routeAsync(c, text, intent -> h.post(() -> {
                    if (IntentRouter.SCREEN.equals(intent)) inspectCurrentScreen(text, cb, true);
                    else Brain.chat(c, text, cb);
                }));
            } else {
                Brain.chat(c, text, cb);
            }
        } catch (Throwable t) {
            addMessage("assistant", "发送失败：" + t.getMessage(), null, true);
            done();
        }
    }

    private void toggleStickerPicker() {
        if (busy || stickerPanel == null) return;
        if (!stickerPickerOpen) {
            JSONArray items = Sticker.list(c);
            if (items == null || items.length() == 0) {
                Toast.makeText(c, "表情库还没有图片；先在聊天页导入表情包", Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int shown = 0;
            for (int i = 0; i < items.length() && shown < 24; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                final String name = item.optString("name", "");
                Bitmap bmp = Sticker.bitmap(c, name);
                if (name.isEmpty() || bmp == null) continue;
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(3), dp(2), dp(3), dp(2));
                cell.setBackground(roundBg(0x331B2233, 0x223B4660, 9));
                ImageView image = new ImageView(c);
                image.setImageBitmap(bmp);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackground(roundBg(0xFF111827, 0x00000000, 7));
                image.setClipToOutline(true);
                cell.addView(image, new LinearLayout.LayoutParams(dp(38), dp(38)));
                TextView label = new TextView(c);
                label.setText(name);
                label.setTextColor(0xFFB9C3D8);
                label.setTextSize(8.5f);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                label.setMaxWidth(dp(52));
                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(13));
                labelLp.topMargin = dp(1);
                cell.addView(label, labelLp);
                cell.setContentDescription("发送表情：" + name);
                cell.setOnClickListener(v -> sendSticker(name));
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(50), dp(56));
                cellLp.rightMargin = dp(5);
                row.addView(cell, cellLp);
                shown++;
            }
            if (shown == 0) {
                Toast.makeText(c, "表情图片暂时无法读取", Toast.LENGTH_SHORT).show();
                return;
            }
            stickerPanel.removeAllViews();
            stickerPanel.addView(row, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            stickerPanel.setVisibility(View.VISIBLE);
            stickerPickerOpen = true;
            int before = targetH;
            grow(dp(70));
            stickerPanelGrowth = targetH - before;
        } else {
            stickerPanel.setVisibility(View.GONE);
            stickerPickerOpen = false;
            targetH = Math.max(baseH, targetH - stickerPanelGrowth);
            stickerPanelGrowth = 0;
            applyHeight(true);
        }
    }

    private void sendSticker(String name) {
        if (busy || name == null || name.isEmpty()) return;
        Bitmap bmp = Sticker.bitmap(c, name);
        if (bmp == null) {
            Toast.makeText(c, "这张表情图片已经不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (stickerPanel != null && stickerPickerOpen) {
            stickerPanel.setVisibility(View.GONE);
            stickerPickerOpen = false;
            targetH = Math.max(baseH, targetH - stickerPanelGrowth);
            stickerPanelGrowth = 0;
            applyHeight(false);
        }
        String marker = "[[表情:" + name + "]]";
        addMessage("user", "", bmp, true);
        busy = true;
        if (sendBtn != null) sendBtn.setAlpha(0.45f);
        if (status != null) {
            status.setText("识别表情…");
            status.setTextColor(0xFFFFCF7A);
        }
        Brain.chat(c, marker, new Ai.Cb() {
            @Override public void ok(String reply) {
                h.post(() -> {
                    String shown = Sticker.strip(reply);
                    String stickerName = Sticker.pickName(reply);
                    Bitmap answerSticker = stickerName == null || stickerName.isEmpty()
                            ? null : Sticker.bitmap(c, stickerName);
                    if (shown.trim().isEmpty() && answerSticker != null) {
                        addMessage("assistant", "", answerSticker, true);
                    } else {
                        addLines(shown, answerSticker);
                    }
                    host.onReply(shown);
                    done();
                });
            }

            @Override public void fail(String err) {
                h.post(() -> {
                    addMessage("assistant", "（没连上）" + (err == null ? "请稍后再试" : err), null, true);
                    done();
                });
            }
        });
    }

    private void inspectCurrentScreen(String text, Ai.Cb cb, boolean modelChoseScreen) {
        A11y.readNow((screen, serviceOn) -> {
            boolean explicit = ScreenAsk.explicit(text) || modelChoseScreen;
            if (!serviceOn) {
                if (explicit) localReply(text, "我这次没连上无障碍服务，暂时读不到当前屏幕；请检查权限体检里的无障碍状态。" );
                else Brain.chat(c, text, cb);
                return;
            }
            String readable = screen == null ? "" : screen.trim();
            if (readable.isEmpty() || modelChoseScreen) {
                if (explicit && Build.VERSION.SDK_INT >= 30 && A11y.get() != null) {
                    // The model selected a fresh screen observation, so always capture now;
                    // this also prevents a previous app's text from leaking into the answer.
                    analyzeScreenShot(text, "", cb);
                    return;
                }
                if (readable.isEmpty()) {
                    localReply(text, "无障碍服务已连接，但当前页面没有可读文字；这台系统暂时不能通过无障碍截图分析画面。" );
                    return;
                }
            }
            if (explicit && readable.length() < 80 && Build.VERSION.SDK_INT >= 30 && A11y.get() != null) {
                analyzeScreenShot(text, readable, cb);
                return;
            }
            Brain.chat(c, text, cb);
        });
    }

    private static boolean wantsLink(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("链接") || t.contains("网址") || t.contains("地址")
                || t.contains("提取") || t.contains("url");
    }

    private static String firstUrl(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            Matcher m = URL_PATTERN.matcher(s);
            if (!m.find()) return "";
            String u = m.group();
            while (!u.isEmpty() && "，。；、）】」》\"'”’".indexOf(u.charAt(u.length() - 1)) >= 0) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        } catch (Throwable t) {
            return "";
        }
    }

    private void done() {
        busy = false;
        if (sendBtn != null) sendBtn.setAlpha(1f);
        if (status != null) {
            status.setText("在线");
            status.setTextColor(0xFF5EE6C5);
        }
    }

    /** 本地即时回答（设备状态 / 屏幕不可用），不消耗 token；先假装想 1 秒再逐字打出来。 */
    private void localReply(String userText, String reply) {
        Data.chatAppend(c, "user", userText);
        Data.chatAppend(c, "assistant", reply);
        if (status != null) {
            status.setText("输入中…");
            status.setTextColor(0xFFFFCF7A);
        }
        h.postDelayed(() -> {
            final TextView out = addMessage("assistant", "", null, true);
            if (out == null) {
                host.onReply(reply);
                done();
                return;
            }
            final int width = Math.max(dp(120), panelW - dp(20));
            // 逐字变长会让这一行重新折行，边打边把面板高度补上
            final int[] lastH = {0};
            final int step = Math.max(1, (int) Math.ceil(reply.length() / 45.0));
            final int[] at = {0};
            h.post(new Runnable() {
                @Override
                public void run() {
                    at[0] = Math.min(reply.length(), at[0] + step);
                    out.setText(reply.substring(0, at[0]));
                    android.view.View row = (android.view.View) out.getParent().getParent();
                    row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int hh = row.getMeasuredHeight();
                    if (hh > lastH[0]) {
                        grow(hh - lastH[0] + dp(1));
                        lastH[0] = hh;
                    }
                    scrollBottom();
                    if (at[0] < reply.length()) {
                        h.postDelayed(this, 24);
                    } else {
                        host.onReply(reply);
                        done();
                    }
                }
            });
        }, 1000);
    }

    private void takeShot() {
        if (A11y.get() == null) {
            toast("请先在权限体检里开启无障碍服务");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            toast("系统版本过低，无法截屏");
            return;
        }
        hideKeyboard();
        if (root != null) root.setVisibility(View.INVISIBLE);
        host.setPetVisible(false);
        h.postDelayed(() -> A11y.screenshot(new A11y.ShotCb() {
            @Override
            public void ok(Bitmap bmp) {
                h.post(() -> {
                    pending = shrink(bmp, SCREEN_CAPTURE_MAX_EDGE);
                    shotText = A11y.currentText();
                    restoreAfterShot();
                    if (attachText != null) attachText.setText("截图已插入");
                    if (attachRow != null && attachThumb != null) {
                        attachThumb.setImageBitmap(pending);
                        attachRow.setVisibility(View.VISIBLE);
                        grow(dp(26));
                    }
                });
            }

            @Override
            public void fail(String msg) {
                h.post(() -> {
                    restoreAfterShot();
                    toast(msg);
                });
            }
        }), 260);
    }

    /**
     * 问屏幕但无障碍读不到文字时（视频/短剧/游戏画面）：把聊天窗和桌宠临时藏起来，
     * 截一张整屏交给支持视觉的模型，截不到再退回文字说明。
     */
    private void analyzeScreenShot(final String userText, final String extra, final Ai.Cb cb) {
        hideKeyboard();
        if (root != null) root.setVisibility(View.INVISIBLE);
        host.setPetVisible(false);
        h.postDelayed(() -> A11y.screenshotWhileOverlayHidden(new A11y.ShotCb() {
            @Override
            public void ok(Bitmap bmp) {
                h.post(() -> {
                    // Keep the chat and pet hidden until visual inference finishes. The
                    // screenshot layer does not toggle the overlay again, avoiding the old
                    // hide/show/hide/show flash.
                    Ai.Cb visualCb = new Ai.Cb() {
                        @Override public void ok(String reply) {
                            h.post(() -> { restoreAfterShot(); cb.ok(reply); });
                        }
                        @Override public void fail(String error) {
                            h.post(() -> { restoreAfterShot(); cb.fail(error); });
                        }
                    };
                    Brain.chatScreenImage(c, userText, extra,
                            shrink(bmp, SCREEN_CAPTURE_MAX_EDGE), visualCb);
                });
            }

            @Override
            public void fail(String msg) {
                h.post(() -> {
                    restoreAfterShot();
                    if (!extra.isEmpty()) Brain.chat(c, userText, cb);
                    else localReply(userText, screenCaptureFailure(msg));
                });
            }
        }), 300);
    }

    /** Keep accessibility connection, readable text, screenshot capture, and visual analysis distinct. */
    private String screenCaptureFailure(String error) {
        if (A11y.get() == null) {
            return "无障碍服务刚刚断开了，我这次没能读取屏幕；重新开启权限体检里的无障碍服务后再试一次。";
        }
        String detail = error == null ? "未知原因" : error.trim();
        if (detail.isEmpty()) detail = "系统没有返回原因";
        return "无障碍服务是连着的，只是这个页面没有可读文字；系统截图没成功（" + detail
                + "），所以我还没看到画面。你不用重复开无障碍，先再试一次；如果还不行，把这条错误号发我。";
    }

    private void hideKeyboard() {
        try {
            if (input == null) return;
            input.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    private void restoreAfterShot() {
        if (root != null) root.setVisibility(View.VISIBLE);
        host.setPetVisible(true);
    }

    /** 相册选图完成：先显示预览，等用户点发送再进上下文。 */
    public void setPendingImage(Bitmap bmp, String label) {
        if (!open || bmp == null) return;
        pending = shrink(bmp);
        shotText = "";
        if (attachThumb != null) attachThumb.setImageBitmap(pending);
        if (attachText != null) attachText.setText(TextUtils.isEmpty(label) ? "图片已插入" : label);
        if (attachRow != null) {
            attachRow.setVisibility(View.VISIBLE);
            grow(dp(26));
        }
    }

    /** 打开系统选择器期间隐藏悬浮窗，避免盖住相册；取消或选完再恢复。 */
    public void suspendForPick(boolean hide) {
        if (root != null) root.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
        if (hide) hideKeyboard();
    }

    private Bitmap shrink(Bitmap src) {
        return shrink(src, 1280);
    }

    private Bitmap shrink(Bitmap src, int max) {
        try {
            int w = src.getWidth(), hgt = src.getHeight();
            if (w <= max && hgt <= max) return src;
            float s = Math.min(max / (float) w, max / (float) hgt);
            Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * s)),
                    Math.max(1, (int) (hgt * s)), true);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 她一次会发好几条：一条一行，隔几百毫秒逐条追加，像真人在连发。 */
    private void addLines(String text, Bitmap st) {
        String[] lines = Brain.bubbles(text);
        if (lines.length == 0) return;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final Bitmap img = st != null && i == lines.length - 1 ? st : null;
            if (i == 0) {
                addMessage("assistant", line, img, true);
            } else {
                h.postDelayed(() -> addMessage("assistant", line, img, true), i * 450L);
            }
        }
    }

    private TextView addMessage(String role, String text, Bitmap img, boolean grow) {
        if (msgBox == null) return null;
        boolean user = "user".equals(role);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(user ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(3), 0, dp(3));

        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(11), dp(7), dp(11), dp(7));
        if (user) {
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF8261FF, 0xFF5E3FF0});
            bg.setCornerRadius(dp(16));
            bubble.setBackground(bg);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(0xFF1B2233);
            bg.setStroke(dp(1), 0x22FFFFFF);
            bubble.setBackground(bg);
        }

        if (img != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(img);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(roundBg(0xFF121826, 0x00000000, 8));
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(user ? 44 : 64), dp(user ? 44 : 64));
            lp.bottomMargin = text.isEmpty() ? 0 : dp(5);
            bubble.addView(iv, lp);
        }
        TextView tv = null;
        // 助手消息即使暂时没字也先建控件，方便逐字往里填；纯表情消息不建空文本行
        if (!text.isEmpty() || (!user && grow)) {
            tv = new TextView(c);
            tv.setText(text);
            tv.setTextColor(0xFFF0F4FF);
            tv.setTextSize(12.5f);
            tv.setLineSpacing(dp(2), 1f);
            tv.setMaxWidth(dp(205));
            bubble.addView(tv);
        }

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (user) blp.leftMargin = dp(36);
        else blp.rightMargin = dp(36);
        row.addView(bubble, blp);
        msgBox.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (grow) {
            int width = Math.max(dp(120), panelW - dp(20));
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            grow(row.getMeasuredHeight() + dp(2));
        }
        scrollBottom();
        return tv;
    }

    private void addInsightCard(String title, JSONArray items) {
        int count = items == null ? 0 : items.length();
        String label = (title == null || title.isEmpty() ? "共同心得" : title)
                + " · " + count + " 条（点击展开）";
        TextView head = addMessage("assistant", label, null, false);
        if (head == null || !(head.getParent() instanceof LinearLayout)) return;
        LinearLayout bubble = (LinearLayout) head.getParent();
        StringBuilder body = new StringBuilder();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            String line = items.optString(i, "").trim();
            if (line.isEmpty()) continue;
            if (body.length() > 0) body.append('\n');
            body.append("• ").append(line);
        }
        TextView detail = new TextView(c);
        detail.setText(body.toString());
        detail.setTextColor(0xFFD5E1F7);
        detail.setTextSize(11.5f);
        detail.setLineSpacing(dp(2), 1f);
        detail.setVisibility(View.GONE);
        bubble.addView(detail);
        head.setOnClickListener(v -> detail.setVisibility(
                detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private void grow(int delta) {
        if (delta <= 0) return;
        targetH = Math.min(maxH, targetH + delta);
        applyHeight(true);
    }

    private void applyHeight(boolean scroll) {
        if (wp == null) return;
        wp.x = winX;
        wp.y = winY;
        wp.height = targetH;
        try {
            if (root != null && root.getParent() != null) wm.updateViewLayout(root, wp);
        } catch (Throwable ignored) {
        }
        host.onLayout(winX, winY, winX + panelW, winY + targetH);
        if (scroll) scrollBottom();
    }

    private void fitInput() {
        if (!open || input == null) return;
        int lines = Math.max(1, input.getLineCount());
        if (lines > lastInputLines) {
            grow((lines - lastInputLines) * dp(15));
        }
        lastInputLines = lines;
    }

    private void clearPending() {
        pending = null;
        shotText = "";
        if (attachThumb != null) attachThumb.setImageDrawable(null);
        if (attachText != null) attachText.setText("截图已插入");
        if (attachRow != null) attachRow.setVisibility(View.GONE);
    }

    private void scrollBottom() {
        if (msgScroll == null) return;
        msgScroll.post(() -> {
            if (msgScroll != null) msgScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    private GradientDrawable roundBg(int color, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius));
        g.setColor(color);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void toast(String msg) {
        try {
            Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }
}
