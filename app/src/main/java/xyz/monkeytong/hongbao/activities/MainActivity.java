package xyz.monkeytong.hongbao.activities;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import xyz.monkeytong.hongbao.R;
import xyz.monkeytong.hongbao.services.HongbaoService;
import xyz.monkeytong.hongbao.utils.HongbaoLog;

import java.util.List;


public class MainActivity extends Activity implements AccessibilityManager.AccessibilityStateChangeListener {

    //开关切换按钮
    private TextView pluginStatusText;
    private ImageView pluginStatusIcon;
    //总开关：无障碍已开启时，是否真正自动抢红包
    private TextView switchButton;
    private SharedPreferences preferences;
    //AccessibilityService 管理
    private AccessibilityManager accessibilityManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        HongbaoLog.init(this);
        HongbaoLog.d("ui", "打开主界面");

        pluginStatusText = (TextView) findViewById(R.id.layout_control_accessibility_text);
        pluginStatusIcon = (ImageView) findViewById(R.id.layout_control_accessibility_icon);
        switchButton = (TextView) findViewById(R.id.btn_switch);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        handleMaterialStatusBar();

        explicitlyLoadPreferences();

        //监听AccessibilityService 变化
        accessibilityManager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        accessibilityManager.addAccessibilityStateChangeListener(this);
        updateServiceStatus();
    }

    private void explicitlyLoadPreferences() {
        PreferenceManager.setDefaultValues(this, R.xml.general_preferences, false);
    }

    /**
     * 适配MIUI沉浸状态栏
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void handleMaterialStatusBar() {
        // Not supported in APK level lower than 21
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        Window window = this.getWindow();

        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        window.setStatusBarColor(0xffE46C62);

    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        updateServiceStatus();
    }

    @Override
    protected void onDestroy() {
        //移除监听服务
        accessibilityManager.removeAccessibilityStateChangeListener(this);
        super.onDestroy();
    }

    public void openAccessibility(View view) {
        try {
            Toast.makeText(this, getString(R.string.turn_on_toast) + pluginStatusText.getText(), Toast.LENGTH_SHORT).show();
            Intent accessibleIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(accessibleIntent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.turn_on_error_toast), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }

    }

    public void openGitHub(View view) {
        Intent webViewIntent = new Intent(this, WebViewActivity.class);
        webViewIntent.putExtra("title", getString(R.string.webview_github_title));
        webViewIntent.putExtra("url", getString(R.string.url_project_home));
        startActivity(webViewIntent);
    }

    public void openSettings(View view) {
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        settingsIntent.putExtra("title", getString(R.string.preference));
        settingsIntent.putExtra("frag_id", "GeneralSettingsFragment");
        startActivity(settingsIntent);
    }

    public void openLog(View view) {
        startActivity(new Intent(this, LogActivity.class));
    }

    /**
     * 5 秒后抓取当前前台界面节点，方便用户切到微信红包页面后采集诊断信息。
     */
    public void captureWindow(View view) {
        HongbaoLog.d("ui", "用户请求抓取界面节点");
        if (HongbaoService.requestCapture("手动抓取")) {
            Toast.makeText(this, R.string.log_capture_toast, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.log_service_required, Toast.LENGTH_LONG).show();
        }
    }


    /**
     * 总开关：无障碍服务已开启时，决定是否真正执行自动抢红包。
     * 关闭后服务仍在运行（只记录日志），但不会点击任何红包。
     */
    public void togglePlugin(View view) {
        if (!isServiceEnabled()) {
            Toast.makeText(this, R.string.switch_need_service_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean running = preferences.getBoolean(HongbaoService.PREF_ENABLED, true);
        boolean next = !running;
        preferences.edit().putBoolean(HongbaoService.PREF_ENABLED, next).apply();
        HongbaoLog.d("ui", "自动抢红包总开关=" + next);
        Toast.makeText(this, next ? R.string.switch_resumed_toast : R.string.switch_paused_toast,
                Toast.LENGTH_SHORT).show();
        updateServiceStatus();
    }


    @Override
    public void onAccessibilityStateChanged(boolean enabled) {
        updateServiceStatus();
    }

    /**
     * 更新当前 HongbaoService 显示状态
     */
    private void updateServiceStatus() {
        boolean enabled = isServiceEnabled();
        HongbaoLog.d("ui", "无障碍服务状态=" + enabled);
        if (enabled) {
            pluginStatusText.setText(R.string.service_off);
            pluginStatusIcon.setBackgroundResource(R.mipmap.ic_stop);
        } else {
            pluginStatusText.setText(R.string.service_on);
            pluginStatusIcon.setBackgroundResource(R.mipmap.ic_start);
        }

        boolean running = preferences.getBoolean(HongbaoService.PREF_ENABLED, true);
        if (enabled) {
            switchButton.setText(running ? R.string.switch_pause : R.string.switch_resume);
            switchButton.setAlpha(1f);
        } else {
            switchButton.setText(R.string.switch_need_service);
            switchButton.setAlpha(0.55f);
        }
    }

    /**
     * 获取 HongbaoService 是否启用状态
     *
     * @return
     */
    private boolean isServiceEnabled() {
        List<AccessibilityServiceInfo> accessibilityServices =
                accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo info : accessibilityServices) {
            if (info.getId().equals(getPackageName() + "/.services.HongbaoService")) {
                return true;
            }
        }
        return false;
    }
}
