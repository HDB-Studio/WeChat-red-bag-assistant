package xyz.monkeytong.hongbao.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量运行日志：写入应用私有目录，方便在无法连接电脑时直接在应用内查看/分享。
 * 作者：HDB-Studio
 */
public final class HongbaoLog {
    private static final String TAG = "HongbaoLog";
    private static final long MAX_BYTES = 512 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File logFile;

    private HongbaoLog() {
    }

    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (logFile != null) return;
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "hongbao.log");
        }
        d("app", "==== 启动 " + appVersion(context)
                + " | " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") ====");
    }

    public static void d(String tag, String message) {
        write(tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        write(tag, message + (throwable == null ? "" : " : " + throwable), throwable);
    }

    private static void write(String tag, String message, Throwable throwable) {
        if (message == null) message = "null";
        String line = TIME.format(new Date()) + " [" + tag + "] " + message;
        if (throwable != null) {
            Log.e("Hongbao/" + tag, message, throwable);
        } else {
            Log.d("Hongbao/" + tag, message);
        }

        synchronized (LOCK) {
            if (logFile == null) return;
            try {
                if (logFile.length() > MAX_BYTES) {
                    File old = new File(logFile.getParentFile(), "hongbao.old.log");
                    if (old.exists()) old.delete();
                    logFile.renameTo(old);
                }
                FileWriter writer = new FileWriter(logFile, true);
                writer.write(line);
                writer.write("\n");
                writer.close();
            } catch (Throwable ignored) {
                // 日志失败不能影响主流程
            }
        }
    }

    public static String readTail(int maxChars) {
        synchronized (LOCK) {
            if (logFile == null || !logFile.exists()) return "";
            FileInputStream in = null;
            try {
                in = new FileInputStream(logFile);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                String content = out.toString("UTF-8");
                if (content.length() > maxChars) {
                    content = "...(已截断前 " + (content.length() - maxChars) + " 字符)...\n"
                            + content.substring(content.length() - maxChars);
                }
                return content;
            } catch (Throwable e) {
                return "读取日志失败: " + e;
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            if (logFile != null && logFile.exists()) logFile.delete();
        }
    }

    public static String path() {
        synchronized (LOCK) {
            return logFile == null ? "未初始化" : logFile.getAbsolutePath();
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + "(" + info.versionCode + ")";
        } catch (Throwable e) {
            return "unknown";
        }
    }
}
