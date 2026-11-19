package com.smartprompter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能提词器主界面 (v8 - 三级架构 + OPPO TWS 深度保活)
 * 一级界面：草稿列表（文档1、文档2... 含标题与内容预览，支持新建与删除）
 * 二级界面：编辑标题、提词内容与提词参数设置
 * 三级界面：全屏提词（单击屏幕/耳机启停，上下滑屏/耳机调速 ±10 px/s，单一镜像水平+180°）
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "SmartPrompterPrefs";
    private static final String KEY_DRAFTS_JSON = "key_drafts_json";
    private static final String KEY_SPEED = "key_speed";
    private static final String KEY_FONT_SIZE = "key_font_size";
    private static final String KEY_MIRROR = "key_mirror";
    private static final String KEY_FOCUS_LINE = "key_focus_line";
    private static final String KEY_ALIGN_LEFT = "key_align_left";
    private static final String KEY_VOICE_FOLLOW = "key_voice_follow";

    private static final int SPEED_STEP = 10;

    private static final String DEFAULT_SAMPLE = "大家好，欢迎来到今天的分享。\n\n"
            + "今天我们要聊的是：如何用科技让工作与创作更高效。\n\n"
            + "当你站在镜头前，眼神要坚定，语速要平稳，自然呼吸。\n\n"
            + "通过蓝牙耳机遥控，双手无需触碰屏幕，按一下耳机即可随时暂停或继续；\n\n"
            + "感觉语速跟不上？上下滑动屏幕或耳机音量键，每次加减 10 px/s 滚动速度！\n\n"
            + "祝你拍摄顺利，录制一遍过！";

    private static class Draft {
        String id;
        String title;
        String content;
        boolean pinned;

        Draft(String id, String title, String content, boolean pinned) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.pinned = pinned;
        }
    }

    // 三级视图容器
    private LinearLayout layoutDraftList;
    private LinearLayout layoutEditor;
    private FrameLayout layoutPrompter;

    // 一级：草稿列表控件
    private LinearLayout draftListContainer;
    private TextView tvDraftCount;
    private EditText etSearchDrafts;
    private String searchQuery = "";
    private CheckBox cbCountdown;
    private TextView tvCountdownOverlay;
    private boolean isCountdownEnabled = true;
    private Draft pendingExportDraft = null;
    private String pendingExportFormat = "txt";

    // 二级：编辑与参数控件
    private EditText etDraftTitle;
    private EditText etScript;
    private TextView tvWordCount;
    private SeekBar seekSpeed;
    private TextView tvSpeedValue;
    private SeekBar seekFontSize;
    private TextView tvFontSizeValue;
    private CheckBox cbMirror;
    private CheckBox cbFocusLine;
    private CheckBox cbVoiceFollow;
    private CheckBox cbBtMic;
    private TextView tvRemoteSignalBadge;

    // 三级：提词控件
    private View viewFocusLine;
    private PrompterCanvasView prompterCanvasView;
    private LinearLayout layoutTopProgress;
    private SeekBar seekPrompterProgress;
    private TextView tvProgressPercent;
    private TextView tvChapterBubble;
    private boolean isUserSeekingProgress = false;
    private int lastProgressPct = -1;
    private TextView tvPrompterHud;
    private HorizontalScrollView layoutFloatingBar;
    private LinearLayout layoutFloatingBarInner;
    private ImageButton btnPrompterMirror;
    private ImageButton btnPrompterAlign;
    private Button btnPrompterAi;
    private ImageButton btnPrompterMic;
    private Button btnPrompterLineSpacing;
    private LinearLayout layoutLineSpacingPanel;
    private SeekBar seekLineSpacing;
    private TextView tvLineSpacingVal;
    private float currentLineSpacing = 1.15f;
    private ImageButton btnPrompterOrientation;
    private ImageButton btnPrompterPlayPause;
    private TextView tvPrompterSpeedIndicator;

    // 数据与状态
    private final List<Draft> drafts = new ArrayList<>();
    private int currentDraftIndex = -1;
    private boolean isNewUnsavedDraft = false;
    private String originalDraftTitle = "";
    private String originalDraftContent = "";
    private boolean isUpdatingEditorFields = false;

    private int currentSpeed = 45;
    private int currentFontSize = 48;
    private boolean isMirror = false;
    private boolean showFocusLine = true;
    private boolean isAlignLeft = false;
    private boolean isVoiceFollow = false;
    private boolean useBluetoothMic = false;
    private int highlightedIndex = 0;
    private final ForegroundColorSpan ktvHighlightSpan = new ForegroundColorSpan(Color.parseColor("#FACC15"));
    private String currentPrompterScript = "";
    private VoiceFollower voiceFollower;
    private boolean isPlaying = false;
    private boolean isPrompterActive = false;
    private boolean isLandscape = true;

    private float scrollRemainder = 0f;
    private long lastFrameTime = 0;

    // 蓝牙耳机 MediaSession 与极微底噪保活音轨
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private VolumeChangeReceiver volumeReceiver;
    private int midVolume = 7;
    private int savedOriginalVolume = -1;
    private float savedOriginalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private long lastMediaKeyTime = 0;

    private GestureDetector tapGestureDetector;



    private final ActivityResultLauncher<String[]> importFileLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                try {
                    String fileName = DraftFileHelper.getFileName(getContentResolver(), uri);
                    String content = DraftFileHelper.readTextFromUri(getContentResolver(), uri, fileName);
                    String title = DraftFileHelper.stripExtension(fileName);
                    drafts.add(0, new Draft(UUID.randomUUID().toString(), title, content, false));
                    sortDraftsByPin();
                    saveDrafts();
                    renderDraftList();
                    Toast.makeText(this, "已导入: " + title, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
    );

    private final ActivityResultLauncher<String> exportFileLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("*/*"),
            uri -> {
                if (uri == null || pendingExportDraft == null) return;
                try {
                    DraftFileHelper.writeExportToUri(getContentResolver(), uri,
                            pendingExportDraft.title, pendingExportDraft.content, pendingExportFormat);
                    Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
    );


    private final ActivityResultLauncher<String> exportLogLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"),
            uri -> {
                if (uri == null) return;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        String logs = PrompterLogger.getRecentLogsCombined(this);
                        os.write(logs.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        os.flush();
                        Toast.makeText(this, "运行日志导出成功", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
    );

    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String trace = android.util.Log.getStackTraceString(e);
                PrompterLogger.logEvent(this, "CRASH: " + trace); PrompterLogger.flush(this);
                getSharedPreferences("CrashPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_crash", trace)
                        .commit();
            } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        });
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        force60HzRefreshRate();
        setContentView(R.layout.activity_main);

        initViews();
        loadSavedPreferences();
        setupMediaSessionAndAudioFocus();
        setupVolumeReceiver();
        setupEventListeners();
        renderDraftList();
        showLastCrashIfAny();
        clearPrompterSessionState();
        PrompterLogger.endSession(this, "用户退出提词");
        restorePrompterSessionFromBundle(savedInstanceState);
    }

    private void showLastCrashIfAny() {
        SharedPreferences cp = getSharedPreferences("CrashPrefs", MODE_PRIVATE);
        String crash = cp.getString("last_crash", null);
        if (crash != null && !crash.isEmpty()) {
            cp.edit().remove("last_crash").apply();
            String preview = crash.length() > 1200 ? crash.substring(0, 1200) + "..." : crash;
            new AlertDialog.Builder(this)
                    .setTitle("检测到上次闪退日志")
                    .setMessage(preview)
                    .setPositiveButton("复制日志", (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash));
                            Toast.makeText(this, "已复制完整闪退日志", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        }
    }

    private void initViews() {
        layoutDraftList = findViewById(R.id.layoutDraftList);
        layoutEditor = findViewById(R.id.layoutEditor);
        layoutPrompter = findViewById(R.id.layoutPrompter);

        draftListContainer = findViewById(R.id.draftListContainer);
        tvDraftCount = findViewById(R.id.tvDraftCount);
        etSearchDrafts = findViewById(R.id.etSearchDrafts);
        cbCountdown = findViewById(R.id.cbCountdown);
        tvCountdownOverlay = findViewById(R.id.tvCountdownOverlay);

        etDraftTitle = findViewById(R.id.etDraftTitle);
        etScript = findViewById(R.id.etScript);
        tvWordCount = findViewById(R.id.tvWordCount);
        seekSpeed = findViewById(R.id.seekSpeed);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        seekFontSize = findViewById(R.id.seekFontSize);
        tvFontSizeValue = findViewById(R.id.tvFontSizeValue);
        cbMirror = findViewById(R.id.cbMirror);
        cbFocusLine = findViewById(R.id.cbFocusLine);
        cbVoiceFollow = findViewById(R.id.cbVoiceFollow);
        cbBtMic = findViewById(R.id.cbBtMic);
        tvRemoteSignalBadge = findViewById(R.id.tvRemoteSignalBadge);

        viewFocusLine = findViewById(R.id.viewFocusLine);
        prompterCanvasView = findViewById(R.id.prompterCanvasView);
        layoutTopProgress = findViewById(R.id.layoutTopProgress);
        seekPrompterProgress = findViewById(R.id.seekPrompterProgress);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvChapterBubble = findViewById(R.id.tvChapterBubble);
        prompterCanvasView.setInteractionListener(new PrompterCanvasView.OnPrompterInteractionListener() {
            @Override
            public void onSingleTap() {
                if (isPrompterActive) {
                    if (layoutLineSpacingPanel != null && layoutLineSpacingPanel.getVisibility() == View.VISIBLE) {
                        layoutLineSpacingPanel.setVisibility(View.GONE);
                        syncToolbarHighlights();
                    }
                    togglePrompterPlayPause("点击屏幕");
                    wakeFloatingBar();
                }
            }
            @Override
            public void onLongPressOffset(int charOffset) {
                if (isPrompterActive) {
                    jumpAndStartVoiceFollow(charOffset);
                }
            }
            @Override
            public void onUserScrollFinished() {
                if (isVoiceFollow) {
                    syncHighlightedIndexToTopVisibleLine();
                }
                wakeFloatingBar();
            }
            @Override
            public void onReachBottom() {
                pausePrompter("台词结束");
            }
            @Override
            public void onScrollProgress(float progress01, String currentChapterTitle) {
                if (!isUserSeekingProgress && seekPrompterProgress != null) {
                    seekPrompterProgress.setProgress(Math.round(progress01 * 1000f));
                }
                int pct = Math.round(progress01 * 100f);
                if (tvProgressPercent != null && pct != lastProgressPct) {
                    lastProgressPct = pct;
                    tvProgressPercent.setText(pct + "%");
                }
            }
        });
        tvPrompterHud = findViewById(R.id.tvPrompterHud);
        layoutFloatingBar = findViewById(R.id.layoutFloatingBar);
        layoutFloatingBarInner = findViewById(R.id.layoutFloatingBarInner);
        btnPrompterMirror = findViewById(R.id.btnPrompterMirror);
        btnPrompterAlign = findViewById(R.id.btnPrompterAlign);
        btnPrompterAi = findViewById(R.id.btnPrompterAi);
        btnPrompterMic = findViewById(R.id.btnPrompterMic);
        btnPrompterLineSpacing = findViewById(R.id.btnPrompterLineSpacing);
        layoutLineSpacingPanel = findViewById(R.id.layoutLineSpacingPanel);
        seekLineSpacing = findViewById(R.id.seekLineSpacing);
        tvLineSpacingVal = findViewById(R.id.tvLineSpacingVal);
        btnPrompterOrientation = findViewById(R.id.btnPrompterOrientation);
        btnPrompterPlayPause = findViewById(R.id.btnPrompterPlayPause);
        tvPrompterSpeedIndicator = findViewById(R.id.tvPrompterSpeedIndicator);

    }

    private void loadSavedPreferences() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        currentSpeed = sp.getInt(KEY_SPEED, 45);
        currentFontSize = sp.getInt(KEY_FONT_SIZE, 48);
        isMirror = sp.getBoolean(KEY_MIRROR, false);
        showFocusLine = sp.getBoolean(KEY_FOCUS_LINE, true);
        isAlignLeft = sp.getBoolean(KEY_ALIGN_LEFT, false);
        isVoiceFollow = sp.getBoolean(KEY_VOICE_FOLLOW, false);

        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(String.valueOf(currentSpeed));

        seekFontSize.setProgress(currentFontSize);
        tvFontSizeValue.setText(currentFontSize + " sp");

        cbMirror.setChecked(isMirror);
        cbFocusLine.setChecked(showFocusLine);
        cbVoiceFollow.setChecked(isVoiceFollow);
        cbBtMic.setChecked(useBluetoothMic);

        drafts.clear();
        String json = sp.getString(KEY_DRAFTS_JSON, "");
        if (!json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    drafts.add(new Draft(
                            obj.optString("id", UUID.randomUUID().toString()),
                            obj.optString("title", "文档" + (i + 1)),
                            obj.optString("content", ""),
                            obj.optBoolean("pinned", false)
                    ));
                }
            } catch (Exception ignored) {}
        }

        if (drafts.isEmpty()) {
            drafts.add(new Draft(UUID.randomUUID().toString(), "文档1（示例）", DEFAULT_SAMPLE, false));
            saveDrafts();
        }
    }

    private void saveDrafts() {
        try {
            JSONArray arr = new JSONArray();
            for (Draft d : drafts) {
                JSONObject obj = new JSONObject();
                obj.put("id", d.id);
                obj.put("title", d.title);
                obj.put("content", d.content);
                obj.put("pinned", d.pinned);
                arr.put(obj);
            }
            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DRAFTS_JSON, arr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private void saveSettings() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_SPEED, currentSpeed)
                .putInt(KEY_FONT_SIZE, currentFontSize)
                .putBoolean(KEY_MIRROR, isMirror)
                .putBoolean(KEY_FOCUS_LINE, showFocusLine)
                .putBoolean(KEY_ALIGN_LEFT, isAlignLeft)
                .putBoolean(KEY_VOICE_FOLLOW, isVoiceFollow)
                .apply();
    }

    // =========================================================================
    // 一级界面：草稿列表渲染与交互
    // =========================================================================

    private void sortDraftsByPin() {
        drafts.sort((a, b) -> Boolean.compare(b.pinned, a.pinned));
    }

    private void showDraftActionMenu(int index) {
        if (index < 0 || index >= drafts.size()) return;
        Draft d = drafts.get(index);
        String[] options = {
                d.pinned ? "取消置顶" : "📌 置顶草稿",
                "导出为 Word (.docx)",
                "导出为 Markdown (.md)",
                "导出为 TXT (.txt)",
                "删除草稿"
        };
        new AlertDialog.Builder(this)
                .setTitle(d.title.isEmpty() ? "草稿操作" : d.title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        d.pinned = !d.pinned;
                        sortDraftsByPin();
                        saveDrafts();
                        renderDraftList();
                        Toast.makeText(this, d.pinned ? "已置顶" : "已取消置顶", Toast.LENGTH_SHORT).show();
                    } else if (which == 1 || which == 2 || which == 3) {
                        pendingExportDraft = d;
                        pendingExportFormat = which == 1 ? "docx" : (which == 2 ? "md" : "txt");
                        String safeTitle = (d.title == null || d.title.trim().isEmpty()) ? "提词文稿" : d.title.trim();
                        exportFileLauncher.launch(safeTitle + "." + pendingExportFormat);
                    } else if (which == 4) {
                        drafts.remove(index);
                        saveDrafts();
                        renderDraftList();
                        Toast.makeText(this, "已删除草稿", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void renderDraftList() {
        sortDraftsByPin();
        draftListContainer.removeAllViews();
        String q = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        int shownCount = 0;

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);
        int marginBottom = (int) (12 * density);

        for (int i = 0; i < drafts.size(); i++) {
            final int index = i;
            Draft draft = drafts.get(i);
            if (!q.isEmpty()) {
                boolean matchTitle = draft.title != null && draft.title.toLowerCase(Locale.ROOT).contains(q);
                boolean matchBody = draft.content != null && draft.content.toLowerCase(Locale.ROOT).contains(q);
                if (!matchTitle && !matchBody) continue;
            }
            shownCount++;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, pad, pad, pad);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#1E293B"));
            bg.setCornerRadius(12 * density);
            bg.setStroke((int) (1 * density), Color.parseColor("#334155"));
            card.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = marginBottom;
            card.setLayoutParams(lp);

            // 标题行 + 删除按钮
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvTitle = new TextView(this);
            tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            String baseTitle = draft.title.isEmpty() ? "未命名草稿" : draft.title;
            tvTitle.setText(draft.pinned ? "📌 " + baseTitle : baseTitle);
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(16f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

            Button btnDelete = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btnDelete.setText("删除");
            btnDelete.setTextColor(Color.parseColor("#F43F5E"));
            btnDelete.setTextSize(12f);
            btnDelete.setMinimumWidth(0);
            btnDelete.setMinWidth(0);
            btnDelete.setOnClickListener(v -> {
                drafts.remove(index);
                saveDrafts();
                renderDraftList();
                Toast.makeText(this, "已删除草稿", Toast.LENGTH_SHORT).show();
            });

            topRow.addView(tvTitle);
            topRow.addView(btnDelete);

            // 内容预览
            TextView tvPreview = new TextView(this);
            String cleanContent = draft.content.replace("\n", " ").trim();
            tvPreview.setText(cleanContent.isEmpty() ? "（暂无内容，点击编辑）" : cleanContent);
            tvPreview.setTextColor(Color.parseColor("#94A3B8"));
            tvPreview.setTextSize(13f);
            tvPreview.setMaxLines(2);
            tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            prevLp.topMargin = (int) (6 * density);
            tvPreview.setLayoutParams(prevLp);

            // 字数统计
            int len = draft.content.trim().length();
            int sec = Math.round((len / 240.0f) * 60);
            TextView tvMeta = new TextView(this);
            tvMeta.setText(len + " 字 · 约 " + sec + " 秒");
            tvMeta.setTextColor(Color.parseColor("#818CF8"));
            tvMeta.setTextSize(11f);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            metaLp.topMargin = (int) (8 * density);
            tvMeta.setLayoutParams(metaLp);

            card.addView(topRow);
            card.addView(tvPreview);
            card.addView(tvMeta);

            card.setOnClickListener(v -> openDraftInEditor(index));
            draftListContainer.addView(card);
        }
    }

    private String getTodayDateTitle() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }


    private void showSettingsDialog() {
        String ver = "1.62.0";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        final String versionStr = ver;
        String[] items = {
                "关于智能提词器 (v" + versionStr + ")",
                "查看最近 3 次提词运行日志",
                "导出最近 3 次运行日志 (.txt)",
                "清空本地运行日志"
        };
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle("关于智能提词器")
                                .setMessage("版本：v" + versionStr + "\n"
                                        + "设备：" + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")\n\n"
                                        + "核心功能：\n"
                                        + "• 原生 Canvas 视口直绘引擎（60Hz 锁帧防卡顿）\n"
                                        + "• 离线 AI 严格跟读（支持手机麦 / 蓝牙麦切换）\n"
                                        + "• **重点词**加粗高亮 & # 章节标题进度跳转\n"
                                        + "• 蓝牙耳机免触控遥控 & 半透明悬浮窗\n"
                                        + "• 草稿置顶、搜索与 Word/MD/TXT 导入导出")
                                .setPositiveButton("确定", null)
                                .show();
                    } else if (which == 1) {
                        String logs = PrompterLogger.getRecentLogsCombined(this);
                        String preview = logs.length() > 3000 ? logs.substring(0, 3000) + "\n...(完整内容请点击导出)" : logs;
                        new AlertDialog.Builder(this)
                                .setTitle("最近 3 次提词运行日志")
                                .setMessage(preview)
                                .setPositiveButton("复制全部", (d2, w2) -> {
                                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("prompter_logs", logs));
                                        Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    } else if (which == 2) {
                        String dateTag = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                        exportLogLauncher.launch("SmartPrompter_Logs_" + dateTag + ".txt");
                    } else if (which == 3) {
                        PrompterLogger.clearAllLogs(this);
                        Toast.makeText(this, "已清空本地运行日志", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("返回", null)
                .show();
    }

    private void createNewDraft() {
        isNewUnsavedDraft = true;
        currentDraftIndex = -1;
        originalDraftTitle = getTodayDateTitle();
        originalDraftContent = "";

        isUpdatingEditorFields = true;
        etDraftTitle.setText(originalDraftTitle);
        etScript.setText("");
        isUpdatingEditorFields = false;

        updateWordCount();
        layoutDraftList.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);
    }

    private void openDraftInEditor(int index) {
        isNewUnsavedDraft = false;
        currentDraftIndex = index;
        Draft d = drafts.get(index);
        originalDraftTitle = d.title;
        originalDraftContent = d.content;

        isUpdatingEditorFields = true;
        etDraftTitle.setText(d.title);
        etScript.setText(d.content);
        isUpdatingEditorFields = false;

        updateWordCount();

        layoutDraftList.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);
    }

    private void backToDraftList() {
        String currentTitle = etDraftTitle.getText().toString().trim();
        String currentContent = etScript.getText().toString();
        boolean hasText = !currentContent.trim().isEmpty();
        boolean isModified = isNewUnsavedDraft
                || !currentTitle.equals(originalDraftTitle)
                || !currentContent.equals(originalDraftContent);

        if (hasText && isModified) {
            new AlertDialog.Builder(this)
                    .setTitle("保存草稿")
                    .setMessage("内容栏中有未保存的文字，是否保存到草稿列表？")
                    .setPositiveButton("保存", (dialog, which) -> {
                        saveCurrentDraftFromEditor();
                        finishBackToDraftList();
                    })
                    .setNegativeButton("不保存", (dialog, which) -> finishBackToDraftList())
                    .setNeutralButton("取消", null)
                    .show();
        } else {
            finishBackToDraftList();
        }
    }

    private void finishBackToDraftList() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        renderDraftList();
        layoutEditor.setVisibility(View.GONE);
        layoutDraftList.setVisibility(View.VISIBLE);
    }


    private final Runnable autoSaveRunnable = this::saveCurrentDraftFromEditor;

    private void scheduleAutoSave() {
        hudHandler.removeCallbacks(autoSaveRunnable);
        hudHandler.postDelayed(autoSaveRunnable, 800);
    }

    private void saveCurrentDraftFromEditor() {
        String title = etDraftTitle.getText().toString().trim();
        if (title.isEmpty()) title = getTodayDateTitle();
        String content = etScript.getText().toString();

        if (isNewUnsavedDraft) {
            drafts.add(0, new Draft(UUID.randomUUID().toString(), title, content, false));
            currentDraftIndex = 0;
            isNewUnsavedDraft = false;
        } else if (currentDraftIndex >= 0 && currentDraftIndex < drafts.size()) {
            Draft d = drafts.get(currentDraftIndex);
            d.title = title;
            d.content = content;
        }
        originalDraftTitle = title;
        originalDraftContent = content;
        saveDrafts();
    }

    // =========================================================================
    // 蓝牙耳机保活与 MediaSession 拦截 (OPPO / ColorOS 深度适配)
    // =========================================================================

    private void setupMediaSessionAndAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        requestExclusiveAudioFocus();

        mediaSession = new MediaSessionCompat(this, "PrompterMediaSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机播放指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onPause() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机暂停指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onStop() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                runOnUiThread(() -> adjustSpeed(SPEED_STEP, "耳机切歌(下一首)"));
            }

            @Override
            public void onSkipToPrevious() {
                runOnUiThread(() -> adjustSpeed(-SPEED_STEP, "耳机切歌(上一首)"));
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        if (!isDebounced()) {
                            runOnUiThread(() -> {
                                if (isPrompterActive) {
                                    togglePrompterPlayPause("TWS耳机");
                                } else {
                                    tvRemoteSignalBadge.setText("🟢 收到TWS耳机按键信号");
                                }
                            });
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                        onSkipToNext();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                        onSkipToPrevious();
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });

        mediaSession.setActive(true);
        tvRemoteSignalBadge.setText("🟢 蓝牙耳机按键与音量监听已激活");
        tvRemoteSignalBadge.setTextColor(Color.parseColor("#34D399"));
    }

    private boolean isDebounced() {
        long now = System.currentTimeMillis();
        if (now - lastMediaKeyTime < 350) {
            return true;
        }
        lastMediaKeyTime = now;
        return false;
    }

    private void requestExclusiveAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if ((focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || focusChange == AudioManager.AUDIOFOCUS_LOSS)
                                && isPrompterActive) {
                            requestExclusiveAudioFocus();
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, 0, 1.0f)
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private void setupVolumeReceiver() {
        if (audioManager != null) {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            midVolume = Math.max(1, maxVol / 2);
        }

        volumeReceiver = new VolumeChangeReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, filter);
        }
    }

    private class VolumeChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isPrompterActive) return;
            if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) return;

                int volume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                if (volume == -1 || volume == midVolume) return;

                if (volume > midVolume) {
                    adjustSpeed(SPEED_STEP, "耳机音量上滑 ➔ 加速");
                } else {
                    adjustSpeed(-SPEED_STEP, "耳机音量下滑 ➔ 减速");
                }

                if (audioManager != null) {
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midVolume, 0);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isPrompterActive) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(SPEED_STEP, "音量加 ➔ 加速");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(-SPEED_STEP, "音量减 ➔ 减速");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !isDebounced()) {
                    togglePrompterPlayPause("TWS耳机/按键");
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void setupEventListeners() {
        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btnNewDraft).setOnClickListener(v -> createNewDraft());
        findViewById(R.id.btnImportDraft).setOnClickListener(v ->
                importFileLauncher.launch(new String[]{
                        "text/plain",
                        "text/markdown",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "*/*"
                })
        );
        if (etSearchDrafts != null) {
            etSearchDrafts.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchQuery = s == null ? "" : s.toString();
                    renderDraftList();
                }
                private boolean isCleaningMd = false;
            @Override public void afterTextChanged(Editable s) {
                if (!isCleaningMd && !isUpdatingEditorFields && s != null && DraftFileHelper.hasMarkdownSyntax(s.toString())) {
                    isCleaningMd = true;
                    String cleaned = DraftFileHelper.parseMarkdownToPrompter(s.toString());
                    s.replace(0, s.length(), cleaned);
                    isCleaningMd = false;
                    Toast.makeText(MainActivity.this, "已自动识别并转换 Markdown 格式", Toast.LENGTH_SHORT).show();
                }
            }
            });
        }
        if (cbCountdown != null) {
            cbCountdown.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isCountdownEnabled = isChecked;
                saveSettings();
            });
        }
        findViewById(R.id.btnBackToDrafts).setOnClickListener(v -> backToDraftList());

        TextWatcher draftWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isUpdatingEditorFields) {
                    updateWordCount();
                    scheduleAutoSave();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        etDraftTitle.addTextChangedListener(draftWatcher);
        etScript.addTextChangedListener(draftWatcher);

        findViewById(R.id.btnLoadSample).setOnClickListener(v -> {
            etScript.setText(DEFAULT_SAMPLE);
            Toast.makeText(this, "已载入示例台词", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnClearScript).setOnClickListener(v -> {
            etScript.setText("");
            Toast.makeText(this, "已清空文稿", Toast.LENGTH_SHORT).show();
        });

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSpeed = Math.max(10, progress);
                tvSpeedValue.setText(currentSpeed + " px/s");
                tvPrompterSpeedIndicator.setText(String.valueOf(currentSpeed));
                saveSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentFontSize = Math.max(24, progress);
                tvFontSizeValue.setText(currentFontSize + " sp");
                saveSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbMirror.setOnCheckedChangeListener((btn, isChecked) -> {
            isMirror = isChecked;
            saveSettings();
        });

        cbFocusLine.setOnCheckedChangeListener((btn, isChecked) -> {
            showFocusLine = isChecked;
            saveSettings();
        });

        cbVoiceFollow.setOnCheckedChangeListener((btn, isChecked) -> {
            isVoiceFollow = isChecked;
            saveSettings();
        });

        findViewById(R.id.btnLaunchPrompter).setOnClickListener(v -> openPrompter());
        findViewById(R.id.btnLaunchFloating).setOnClickListener(v -> launchFloatingPrompter());
        findViewById(R.id.btnPrompterExit).setOnClickListener(v -> exitPrompter());
        findViewById(R.id.btnPrompterRestart).setOnClickListener(v -> restartPrompter());
        btnPrompterPlayPause.setOnClickListener(v -> togglePrompterPlayPause("屏幕按钮"));
        findViewById(R.id.btnPrompterSpeedMinus).setOnClickListener(v -> adjustSpeed(-SPEED_STEP, "手动减速"));
        findViewById(R.id.btnPrompterSpeedPlus).setOnClickListener(v -> adjustSpeed(SPEED_STEP, "手动加速"));
        btnPrompterMirror.setOnClickListener(v -> {
            isMirror = !isMirror;
            cbMirror.setChecked(isMirror);
            applyMirror();
        applyAlignment();
        syncToolbarHighlights();
            saveSettings();
            showHud("🪞 镜像(水平+180°): " + (isMirror ? "开" : "关"));
            wakeFloatingBar();
        hudHandler.removeCallbacks(countdownTickRunnable);
        if (tvCountdownOverlay != null) tvCountdownOverlay.setVisibility(View.GONE);
        if (tvChapterBubble != null) tvChapterBubble.setVisibility(View.GONE);
        hudHandler.removeCallbacks(autoSaveRunnable);
        hudHandler.removeCallbacks(memoryWatchdogRunnable);
        hudHandler.postDelayed(memoryWatchdogRunnable, 15000);
        });

        btnPrompterAlign.setOnClickListener(v -> {
            isAlignLeft = !isAlignLeft;
            applyAlignment();
            saveSettings();
            showHud(isAlignLeft ? "📐 已切换为左对齐" : "📐 已切换为居中对齐");
            wakeFloatingBar();
        });

        btnPrompterAi.setOnClickListener(v -> {
            isVoiceFollow = !isVoiceFollow;
            cbVoiceFollow.setChecked(isVoiceFollow);
            saveSettings();
            syncToolbarHighlights();
            if (isVoiceFollow) {
                // 开启AI跟读时自动暂停匀速滚动
                isPlaying = true;
                pausePrompter("切换AI跟读");
                syncHighlightedIndexToTopVisibleLine();
                ensureMicPermissionAndInitVoice();
                showHud("🎤 AI跟读: 开 (已暂停自动滚动)");
            } else {
                if (voiceFollower != null) voiceFollower.release();
                showHud("🎤 AI跟读: 关");
            }
            wakeFloatingBar();
        });

        btnPrompterMic.setOnClickListener(v -> {
            useBluetoothMic = !useBluetoothMic;
            cbBtMic.setChecked(useBluetoothMic);
            saveSettings();
            syncToolbarHighlights();
            if (voiceFollower != null) {
                voiceFollower.setMicSource(useBluetoothMic ? VoiceFollower.MicSource.BLUETOOTH_MIC : VoiceFollower.MicSource.PHONE_MIC);
            }
            showHud(useBluetoothMic ? "🎧 已切换为蓝牙耳机收音" : "📱 已切换为手机麦克风收音");
            wakeFloatingBar();
        });

        btnPrompterLineSpacing.setOnClickListener(v -> {
            boolean show = layoutLineSpacingPanel.getVisibility() != View.VISIBLE;
            layoutLineSpacingPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            syncToolbarHighlights();
            wakeFloatingBar();
        });

        if (seekPrompterProgress != null) {
            seekPrompterProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || !isPrompterActive) return;
                    float p = progress / 1000f;
                    prompterCanvasView.seekToProgress(p, false);
                    String ch = prompterCanvasView.getChapterTitleAtProgress(p);
                    int pct = Math.round(p * 100f);
                    if (tvChapterBubble != null) {
                        tvChapterBubble.setText(ch.isEmpty() ? ("进度 " + pct + "%") : ("📑 " + ch + " (" + pct + "%)"));
                        tvChapterBubble.setVisibility(View.VISIBLE);
                    }
                    wakeFloatingBar();
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    isUserSeekingProgress = true;
                }
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    isUserSeekingProgress = false;
                    float p = seekBar.getProgress() / 1000f;
                    prompterCanvasView.seekToProgress(p, true);
                    if (tvChapterBubble != null) tvChapterBubble.setVisibility(View.GONE);
                    if (isVoiceFollow) {
                        syncHighlightedIndexToTopVisibleLine();
                    }
                }
            });
        }

        seekLineSpacing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentLineSpacing = 1.00f + (progress * 0.01f);
                tvLineSpacingVal.setText(String.format(Locale.US, "%.2fx", currentLineSpacing));
                if (fromUser && isPrompterActive) {
                    prompterCanvasView.setStyleParams(currentFontSize, currentLineSpacing, isAlignLeft, isMirror);
                    wakeFloatingBar();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettings(); }
        });

        btnPrompterOrientation.setOnClickListener(v -> {
            isLandscape = !isLandscape;
            if (isLandscape) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                showHud("📱 已切换为横屏");
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                showHud("📱 已切换为竖屏");
            }
            syncToolbarHighlights();
            updateFloatingBarSpacing();
            wakeFloatingBar();
        });

        findViewById(R.id.btnPrompterFontMinus).setOnClickListener(v -> {
            adjustFontSize(-4);
            wakeFloatingBar();
        });
        findViewById(R.id.btnPrompterFontPlus).setOnClickListener(v -> {
            adjustFontSize(4);
            wakeFloatingBar();
        });


    }

    private void updateWordCount() {
        int count = etScript.getText().toString().trim().length();
        int estimatedSec = Math.round((count / 240.0f) * 60);
        tvWordCount.setText(count + " 字 · 约 " + estimatedSec + " 秒");
    }

    private void launchFloatingPrompter() {
        saveCurrentDraftFromEditor();
        saveSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启“显示在其他应用上层”权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        Intent svc = new Intent(this, FloatingPrompterService.class);
        svc.putExtra(FloatingPrompterService.EXTRA_SCRIPT, etScript.getText().toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        moveTaskToBack(true);
    }

    private void openPrompter() {
        openPrompterInternal(true);
    }

    private void openPrompterInternal(boolean allowAutoStart) {
        saveCurrentDraftFromEditor();
        isPrompterActive = true;
        saveSettings();

        requestExclusiveAudioFocus();
        if (audioManager != null) {
            try {
                savedOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midVolume, 0);
            } catch (Exception ignored) {}
        }
        if (mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            }

        isLandscape = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        updateFloatingBarSpacing();

        layoutEditor.setVisibility(View.GONE);
        layoutPrompter.setVisibility(View.VISIBLE);

        // 自动将屏幕亮度调至最高 (100%)
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        savedOriginalBrightness = lp.screenBrightness;
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        getWindow().setAttributes(lp);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        currentPrompterScript = etScript.getText().toString();
        highlightedIndex = 0;
        scrollRemainder = 0f;
        prompterCanvasView.setStyleParams(currentFontSize, currentLineSpacing, isAlignLeft, isMirror);
        String verName = "1.62.0";
        try { verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        PrompterLogger.startNewSession(this, verName, currentPrompterScript.length(),
                currentSpeed, currentFontSize, currentLineSpacing, isMirror, isVoiceFollow, useBluetoothMic);
        prompterCanvasView.setScript(currentPrompterScript);

        applyMirror();
        applyAlignment();
        syncToolbarHighlights();
        viewFocusLine.setVisibility(showFocusLine ? View.VISIBLE : View.GONE);
        wakeFloatingBar();
        if (!allowAutoStart) {
            isPlaying = false;
            btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            syncToolbarHighlights();
        } else if (isVoiceFollow) {
            isPlaying = false;
            btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            syncToolbarHighlights();
            ensureMicPermissionAndInitVoice();
            showHud("🎤 正在启动 AI 严格跟读...");
        } else if (isCountdownEnabled && tvCountdownOverlay != null) {
            runCountdownAndStart();
        } else {
            startPrompter("开启提词");
        }
    }

    private int countdownRemain = 0;
    private final Runnable countdownTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPrompterActive || tvCountdownOverlay == null) return;
            if (countdownRemain <= 0) {
                tvCountdownOverlay.setVisibility(View.GONE);
                startPrompter("倒计时完成");
            } else {
                tvCountdownOverlay.setText(String.valueOf(countdownRemain));
                countdownRemain--;
                hudHandler.postDelayed(this, 1000);
            }
        }
    };

    private void runCountdownAndStart() {
        isPlaying = false;
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_play);
        syncToolbarHighlights();
        countdownRemain = 3;
        tvCountdownOverlay.setVisibility(View.VISIBLE);
        hudHandler.removeCallbacks(countdownTickRunnable);
        countdownTickRunnable.run();
    }

    private void exitPrompter() {
        pausePrompter("退出提词");
        hudHandler.removeCallbacks(memoryWatchdogRunnable);
        if (voiceFollower != null) voiceFollower.release();
        isPrompterActive = false;
        clearPrompterSessionState();

        if (audioManager != null && savedOriginalVolume != -1) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedOriginalVolume, 0);
                savedOriginalVolume = -1;
            } catch (Exception ignored) {}
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        if (layoutLineSpacingPanel != null) layoutLineSpacingPanel.setVisibility(View.GONE);
        layoutPrompter.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);

        // 恢复进入提词前的屏幕亮度设置
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = savedOriginalBrightness;
        getWindow().setAttributes(lp);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void applyMirror() {
        prompterCanvasView.setStyleParams(currentFontSize, currentLineSpacing, isAlignLeft, isMirror);
        btnPrompterMirror.setColorFilter(isMirror ? Color.parseColor("#38BDF8") : Color.parseColor("#CBD5E1"));
    }

    private void updateAiButtonStyle() {
        syncToolbarHighlights();
    }

    /**
     * 统一同步底部工具栏所有开关图标的常亮状态
     */
    private void syncToolbarHighlights() {
        int activeColor = Color.parseColor("#38BDF8");
        int normalColor = Color.parseColor("#CBD5E1");
        if (btnPrompterPlayPause != null) {
            btnPrompterPlayPause.setColorFilter(isPlaying ? activeColor : normalColor);
        }
        if (btnPrompterMirror != null) {
            btnPrompterMirror.setColorFilter(isMirror ? activeColor : normalColor);
        }
        if (btnPrompterAlign != null) {
            btnPrompterAlign.setColorFilter(isAlignLeft ? activeColor : normalColor);
        }
        if (btnPrompterAi != null) {
            btnPrompterAi.setTextColor(isVoiceFollow ? activeColor : normalColor);
        }
        if (btnPrompterMic != null) {
            btnPrompterMic.setImageResource(useBluetoothMic ? R.drawable.ic_mic_bluetooth : R.drawable.ic_mic_phone);
            btnPrompterMic.setColorFilter(useBluetoothMic ? activeColor : normalColor);
        }
        if (btnPrompterLineSpacing != null) {
            boolean panelOpen = layoutLineSpacingPanel != null && layoutLineSpacingPanel.getVisibility() == View.VISIBLE;
            btnPrompterLineSpacing.setTextColor(panelOpen ? activeColor : normalColor);
        }
        if (btnPrompterOrientation != null) {
            btnPrompterOrientation.setColorFilter(isLandscape ? activeColor : normalColor);
        }
    }

    private void applyAlignment() {
        if (isAlignLeft) {
            btnPrompterAlign.setImageResource(R.drawable.ic_align_left);
            btnPrompterAlign.setColorFilter(Color.parseColor("#818CF8"));
        } else {
            btnPrompterAlign.setImageResource(R.drawable.ic_align_center);
            btnPrompterAlign.setColorFilter(Color.parseColor("#CBD5E1"));
        }
        prompterCanvasView.setStyleParams(currentFontSize, currentLineSpacing, isAlignLeft, isMirror);
    }

    private void startPrompter(String source) {
        if (isPlaying) return;
        // 与 AI 跟读严格互斥：开启匀速滚动时自动关闭 AI 跟读
        if (isVoiceFollow) {
            isVoiceFollow = false;
            cbVoiceFollow.setChecked(false);
            syncToolbarHighlights();
            if (voiceFollower != null) voiceFollower.stopListening();
            saveSettings();
        }
        isPlaying = true;
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        lastFrameTime = 0L;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (voiceFollower != null) voiceFollower.stopListening();
        Choreographer.getInstance().postFrameCallback(frameCallback);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        syncToolbarHighlights();
        PrompterLogger.logEvent(this, "开始滚动 (" + source + ")");
        showHud("▶ 开始滚动 (" + source + ")");
    }

    private void pausePrompter(String source) {
        if (!isPlaying) return;
        isPlaying = false;
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_play);
        syncToolbarHighlights();
        PrompterLogger.logEvent(this, "暂停滚动 (" + source + ")");
        showHud("⏸ 暂停滚动 (" + source + ")");
    }

    private void togglePrompterPlayPause(String source) {
        if (isPlaying) {
            pausePrompter(source);
        } else {
            startPrompter(source);
        }
    }

    private void restartPrompter() {
        highlightedIndex = 0;
        scrollRemainder = 0f;
        prompterCanvasView.resetToTop();
        showHud("⏮ 回到文稿开头");
    }

    private void ensureMicPermissionAndInitVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            return;
        }
        initAndStartVoiceFollower();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initAndStartVoiceFollower();
            } else {
                isVoiceFollow = false;
                cbVoiceFollow.setChecked(false);
                syncToolbarHighlights();
                saveSettings();
                Toast.makeText(this, "需要麦克风权限才能使用 AI 跟读", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initAndStartVoiceFollower() {
        if (voiceFollower == null) {
            voiceFollower = new VoiceFollower(this, new VoiceFollower.Listener() {
                @Override
                public void onModelReady() {
                    if (isPrompterActive && isVoiceFollow) {
                        showHud("🎤 语音模型就绪，请开始朗读");
                    }
                }

                @Override
                public void onNewSpokenText(String deltaText) {
                    if (isPrompterActive && isVoiceFollow) {
                        handleStrictSpokenMatch(deltaText);
                    }
                }

                @Override
                public void onError(String msg) {
                    showHud("⚠️ " + msg);
                }
            });
        }
        voiceFollower.setMicSource(useBluetoothMic ? VoiceFollower.MicSource.BLUETOOTH_MIC : VoiceFollower.MicSource.PHONE_MIC);
        voiceFollower.initAndStart();
    }

    /**
     * 严格跟读匹配：读对推进并 KTV 染色滚动；如果台词是 1 读成了 2，原地停住不滚
     */
    private void handleStrictSpokenMatch(String deltaText) {
        if (currentPrompterScript.isEmpty() || highlightedIndex >= currentPrompterScript.length()) return;
        boolean advanced = false;

        for (int i = 0; i < deltaText.length(); i++) {
            char spoken = normalizeChar(deltaText.charAt(i));
            if (isIgnorableChar(spoken)) continue;

            // 跳过标点与换行找到当前等待朗读的字
            int expectedIdx = skipPunctuation(highlightedIndex);
            if (expectedIdx >= currentPrompterScript.length()) break;

            char expected1 = normalizeChar(currentPrompterScript.charAt(expectedIdx));
            if (spoken == expected1) {
                highlightedIndex = skipPunctuation(expectedIdx + 1);
                advanced = true;
            } else {
                // 仅允许最多 1 个字的语音识别轻微漏字，绝不跳过整段或错词 (1 读成 2 不会匹配)
                int nextIdx = skipPunctuation(expectedIdx + 1);
                if (nextIdx < currentPrompterScript.length()) {
                    char expected2 = normalizeChar(currentPrompterScript.charAt(nextIdx));
                    if (spoken == expected2) {
                        highlightedIndex = skipPunctuation(nextIdx + 1);
                        advanced = true;
                    }
                }
            }
        }

        if (!advanced) {
            StringBuilder cleanSpoken = new StringBuilder();
            for (int i = 0; i < deltaText.length(); i++) {
                char ch = normalizeChar(deltaText.charAt(i));
                if (!isIgnorableChar(ch)) cleanSpoken.append(ch);
            }
            if (cleanSpoken.length() >= 2) {
                int[] visibleRange = prompterCanvasView.getVisibleCharRange();
                // 严格限制：只允许向后跳到“当前屏幕内”还没读过的台词，绝不往回跳、绝不跳出屏幕外
                int searchStart = Math.max(skipPunctuation(highlightedIndex), visibleRange[0]);
                int searchLimit = Math.min(currentPrompterScript.length(), visibleRange[1]);
                if (searchStart < searchLimit) {
                    String target = cleanSpoken.substring(0, Math.min(3, cleanSpoken.length()));
                    int matchEnd = findPhraseInWindow(searchStart, searchLimit, target);
                    if (matchEnd > highlightedIndex) {
                        highlightedIndex = skipPunctuation(matchEnd);
                        advanced = true;
                    }
                }
            }
        }

        if (advanced) {
            renderKtvText();
            scrollToHighlightedLine();
        }
    }

    private int findPhraseInWindow(int start, int limit, String cleanTarget) {
        if (cleanTarget.length() < 2) return -1;
        for (int pos = start; pos < limit; pos++) {
            int p = pos;
            int matched = 0;
            while (p < limit && matched < cleanTarget.length()) {
                char sc = normalizeChar(currentPrompterScript.charAt(p));
                if (isIgnorableChar(sc)) {
                    p++;
                    continue;
                }
                if (sc == cleanTarget.charAt(matched)) {
                    matched++;
                    p++;
                } else {
                    break;
                }
            }
            if (matched == cleanTarget.length()) {
                return p;
            }
        }
        return -1;
    }

    private int skipPunctuation(int idx) {
        while (idx < currentPrompterScript.length() && isIgnorableChar(currentPrompterScript.charAt(idx))) {
            idx++;
        }
        return idx;
    }

    private boolean isIgnorableChar(char c) {
        return !Character.isLetterOrDigit(c);
    }

    private char normalizeChar(char c) {
        c = Character.toLowerCase(c);
        switch (c) {
            case '0': return '零';
            case '1': return '一';
            case '2': return '二';
            case '3': return '三';
            case '4': return '四';
            case '5': return '五';
            case '6': return '六';
            case '7': return '七';
            case '8': return '八';
            case '9': return '九';
            default: return c;
        }
    }

    private void renderKtvText() {
        prompterCanvasView.setHighlightedIndex(highlightedIndex, true);
    }

    private int findSentenceStart(int offset) {
        if (currentPrompterScript == null || currentPrompterScript.isEmpty()) return 0;
        int i = Math.min(Math.max(0, offset), currentPrompterScript.length() - 1);
        while (i > 0) {
            char prev = currentPrompterScript.charAt(i - 1);
            if (prev == '\n' || prev == '。' || prev == '！' || prev == '？' || prev == '.' || prev == '!' || prev == '?') {
                break;
            }
            i--;
        }
        return skipPunctuation(i);
    }

    private void jumpAndStartVoiceFollow(int targetOffset) {
        int sentenceStart = findSentenceStart(targetOffset);
        highlightedIndex = sentenceStart;
        prompterCanvasView.setHighlightedIndex(highlightedIndex, false);
        if (!isVoiceFollow) {
            isVoiceFollow = true;
            cbVoiceFollow.setChecked(true);
            saveSettings();
            syncToolbarHighlights();
            isPlaying = true;
            pausePrompter("指定句开启AI跟读");
            ensureMicPermissionAndInitVoice();
        }
        showHud("🎤 已定位到该句，开始AI跟读");
        wakeFloatingBar();
    }

    private void syncHighlightedIndexToTopVisibleLine() {
        int focusOffset = prompterCanvasView.getFocusLineCharOffset();
        highlightedIndex = findSentenceStart(focusOffset);
        prompterCanvasView.setHighlightedIndex(highlightedIndex, false);
    }

    private void scrollToHighlightedLine() {
        prompterCanvasView.smoothScrollToCharOffset(highlightedIndex);
    }

    private void adjustFontSize(int delta) {
        currentFontSize = Math.max(24, Math.min(90, currentFontSize + delta));
        seekFontSize.setProgress(currentFontSize);
        tvFontSizeValue.setText(currentFontSize + " sp");
        prompterCanvasView.setStyleParams(currentFontSize, currentLineSpacing, isAlignLeft, isMirror);
        showHud("🔤 字号: " + currentFontSize + " sp");
        saveSettings();
    }

    private void adjustSpeed(int delta, String trigger) {
        currentSpeed = Math.max(10, Math.min(200, currentSpeed + delta));
        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(String.valueOf(currentSpeed));
        showHud("⚡ 速度: " + currentSpeed + " px/s (" + trigger + ")");
        saveSettings();
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!isPlaying) return;
            if (lastFrameTime == 0) {
                lastFrameTime = frameTimeNanos;
            }
            long elapsedNanos = frameTimeNanos - lastFrameTime;
            // 节流至 ~60fps：高刷屏(120Hz)跳过过密帧，显著降低CPU与GPU发热占用
            if (elapsedNanos > 0 && elapsedNanos < 15_000_000L) {
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }
            float deltaSeconds = elapsedNanos / 1_000_000_000.0f;
            lastFrameTime = frameTimeNanos;

            if (deltaSeconds > 0f && deltaSeconds < 0.1f) {
                float dy = currentSpeed * deltaSeconds;
                if (dy > 0f && !prompterCanvasView.scrollByDelta(dy)) {
                    return;
                }
            }

            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void showHud(String message) {
        tvPrompterHud.setText(message);
        tvPrompterHud.setVisibility(View.VISIBLE);
        tvRemoteSignalBadge.setText(message);

        hudHandler.removeCallbacks(hideHudRunnable);
        hudHandler.postDelayed(hideHudRunnable, 2000);
    }


    private final Runnable memoryWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPrompterActive) return;
            try {
                Runtime rt = Runtime.getRuntime();
                long used = rt.totalMemory() - rt.freeMemory();
                long max = rt.maxMemory();
                PrompterLogger.logHeartbeat(MainActivity.this, Math.max(0, lastProgressPct));
                if (max > 0 && (used * 100L / max) >= 70L) {
                    if (!isVoiceFollow) {
                        VoiceFollower.Companion.releaseSharedModelIfIdle();
                    }
                }
            } catch (Exception ignored) {}
            hudHandler.postDelayed(this, 15000);
        }
    };

    private final Runnable hideHudRunnable = () -> tvPrompterHud.setVisibility(View.GONE);

    private void updateFloatingBarSpacing() {
        if (layoutFloatingBarInner == null) return;
        float density = getResources().getDisplayMetrics().density;
        int gapPx = isLandscape ? (int) (8 * density) : 0;
        int padH = isLandscape ? (int) (14 * density) : (int) (6 * density);
        int padV = isLandscape ? (int) (6 * density) : (int) (4 * density);
        layoutFloatingBarInner.setPadding(padH, padV, padH, padV);

        for (int i = 0; i < layoutFloatingBarInner.getChildCount(); i++) {
            View child = layoutFloatingBarInner.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.setMarginStart(i == 0 ? 0 : gapPx);
            child.setLayoutParams(lp);
        }
    }

    private void wakeFloatingBar() {
        layoutFloatingBar.setVisibility(View.VISIBLE);
        layoutFloatingBar.animate().cancel();
        layoutFloatingBar.setAlpha(1.0f);
        if (layoutTopProgress != null) { layoutTopProgress.animate().cancel(); layoutTopProgress.setAlpha(0.95f); }
        hudHandler.removeCallbacks(hideFloatingBarRunnable);
        hudHandler.postDelayed(hideFloatingBarRunnable, 3000);
    }

    private final Runnable hideFloatingBarRunnable = () -> {
        if (isPrompterActive) {
            // 工具栏常驻不消失，静置时自动变为半透明
            layoutFloatingBar.animate().alpha(0.35f).setDuration(300).start();
            if (layoutTopProgress != null) layoutTopProgress.animate().alpha(0.55f).setDuration(300).start();
        }
    };


    private void savePrompterSessionState() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putBoolean("prompter_session_active", isPrompterActive)
                .putInt("prompter_session_draft_idx", currentDraftIndex)
                .putFloat("prompter_session_scroll_y", prompterCanvasView != null ? prompterCanvasView.getScrollYPos() : 0f)
                .putInt("prompter_session_highlight_idx", highlightedIndex)
                .putBoolean("prompter_session_landscape", isLandscape)
                .apply();
    }

    private void clearPrompterSessionState() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putBoolean("prompter_session_active", false)
                .apply();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (isPrompterActive) {
            outState.putBoolean("prompter_session_active", true);
            outState.putInt("prompter_session_draft_idx", currentDraftIndex);
            outState.putFloat("prompter_session_scroll_y", prompterCanvasView != null ? prompterCanvasView.getScrollYPos() : 0f);
            outState.putInt("prompter_session_highlight_idx", highlightedIndex);
            outState.putBoolean("prompter_session_landscape", isLandscape);
        }
    }

    private void restorePrompterSessionFromBundle(Bundle savedInstanceState) {
        if (savedInstanceState == null || !savedInstanceState.getBoolean("prompter_session_active", false)) return;
        int dIdx = savedInstanceState.getInt("prompter_session_draft_idx", 0);
        float savedScrollY = savedInstanceState.getFloat("prompter_session_scroll_y", 0f);
        int savedHighlight = savedInstanceState.getInt("prompter_session_highlight_idx", 0);
        boolean savedLand = savedInstanceState.getBoolean("prompter_session_landscape", true);
        if (dIdx >= 0 && dIdx < drafts.size()) {
            openDraftInEditor(dIdx);
            openPrompterInternal(false);
            isPlaying = false;
            btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            syncToolbarHighlights();
            if (!savedLand) {
                isLandscape = false;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            prompterCanvasView.post(() -> {
                highlightedIndex = savedHighlight;
                prompterCanvasView.restoreState(savedScrollY, savedHighlight);
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isPrompterActive) {
            pausePrompter("切入后台");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        force60HzRefreshRate();
        requestExclusiveAudioFocus();
        if (!isPrompterActive) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(isLandscape
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        if (mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    public void onBackPressed() {
        if (isPrompterActive) {
            exitPrompter();
        } else if (layoutEditor.getVisibility() == View.VISIBLE) {
            backToDraftList();
        } else {
            super.onBackPressed();
        }
    }


    private void force60HzRefreshRate() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = 60.0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.view.Display display = getWindowManager().getDefaultDisplay();
                if (display != null) {
                    android.view.Display.Mode current = display.getMode();
                    android.view.Display.Mode best60 = null;
                    float bestDiff = Float.MAX_VALUE;
                    for (android.view.Display.Mode m : display.getSupportedModes()) {
                        if (m.getPhysicalWidth() == current.getPhysicalWidth()
                                && m.getPhysicalHeight() == current.getPhysicalHeight()) {
                            float diff = Math.abs(m.getRefreshRate() - 60.0f);
                            if (diff < bestDiff) {
                                bestDiff = diff;
                                best60 = m;
                            }
                        }
                    }
                    if (best60 != null && bestDiff <= 5.0f) {
                        lp.preferredDisplayModeId = best60.getModeId();
                    }
                }
            }
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        PrompterLogger.logEvent(this, "系统内存警告 onTrimMemory level=" + level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && !isVoiceFollow) {
            if (voiceFollower != null) voiceFollower.stopListening();
            VoiceFollower.Companion.releaseSharedModelIfIdle();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.release();
        }
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
        }
        if (audioManager != null && audioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (voiceFollower != null) voiceFollower.release();
        hudHandler.removeCallbacksAndMessages(null);
    }
}
