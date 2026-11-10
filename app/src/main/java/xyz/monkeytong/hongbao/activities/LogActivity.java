package xyz.monkeytong.hongbao.activities;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import xyz.monkeytong.hongbao.R;
import xyz.monkeytong.hongbao.utils.HongbaoLog;

/**
 * 运行日志查看页：支持刷新、分享、复制、清空。
 * 作者：LIAO_S_Z
 */
public class LogActivity extends Activity {
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        logText = (TextView) findViewById(R.id.log_text);

        findViewById(R.id.btn_log_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        findViewById(R.id.btn_log_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        findViewById(R.id.btn_log_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        });
        findViewById(R.id.btn_log_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HongbaoLog.clear();
                refresh();
                Toast.makeText(LogActivity.this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_log_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        refresh();
    }

    private void refresh() {
        String content = HongbaoLog.readTail(200000);
        if (content.isEmpty()) {
            content = getString(R.string.log_empty) + "\n\n" + HongbaoLog.path();
        }
        logText.setText(content);
    }

    private void share() {
        String content = HongbaoLog.readTail(100000);
        if (content.isEmpty()) content = getString(R.string.log_empty);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title));
        intent.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(intent, getString(R.string.log_share)));
    }

    private void copy() {
        String content = HongbaoLog.readTail(100000);
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), content));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
