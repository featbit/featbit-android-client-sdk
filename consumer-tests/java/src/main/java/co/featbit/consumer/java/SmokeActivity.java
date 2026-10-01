package co.featbit.consumer.java;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Device entry point for the existing AAR model checks, not an SDK demo. */
public final class SmokeActivity extends Activity {
    private TextView result;
    private TextView lastRun;
    private int runCount;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Java · SDK 本地运行时检查");
        title.setTextSize(22);
        content.addView(title);
        result = new TextView(this);
        result.setTextSize(18);
        result.setPadding(0, padding, 0, padding);
        result.setTextIsSelectable(true);
        content.addView(result);
        lastRun = new TextView(this);
        content.addView(lastRun);
        Button retry = new Button(this);
        retry.setText("重新检查");
        retry.setOnClickListener(view -> runChecks());
        content.addView(retry);
        TextView scope = new TextView(this);
        scope.setText("验证独立 AAR 的模型与阶段 2 本地运行时。\n不验证网络同步、持久化或事件发送。");
        content.addView(scope);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        runChecks();
    }

    private void runChecks() {
        runCount++;
        lastRun.setText("正在进行第 " + runCount + " 次检查…");
        try {
            ModelSmoke.verify();
            result.setText("模型 PASS · 正在检查本地运行时…");
            final int run = runCount;
            RuntimeSmoke.verify(getApplicationContext(), message -> {
                if (run != runCount || isFinishing()) return;
                result.setText(message);
                Log.i("FeatBitConsumer", message);
                String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
                lastRun.setText("已完成第 " + run + " 次检查 · " + time);
            });
        } catch (AssertionError | RuntimeException | LinkageError failure) {
            result.setText("FAIL · 检查失败\n\n" + failure + "\n\n在 Logcat 中搜索 FeatBitConsumer 查看堆栈。");
            Log.e("FeatBitConsumer", "Java model checks FAIL", failure);
            String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
            lastRun.setText("已完成第 " + runCount + " 次检查 · " + time);
        }
    }
}
