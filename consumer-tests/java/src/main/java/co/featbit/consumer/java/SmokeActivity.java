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
        title.setText("Java · SDK 公共模型检查");
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
        scope.setText("仅验证独立 AAR 的公共模型调用。\n不验证网络同步、缓存或事件发送。");
        content.addView(scope);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        runChecks();
    }

    private void runChecks() {
        runCount++;
        try {
            ModelSmoke.verify();
            result.setText("PASS · 检查通过\n\n用户与配置、Bootstrap、JSON 值、非法数值、版本及数据源更新模型。");
            Log.i("FeatBitConsumer", "Java model checks PASS");
        } catch (AssertionError | RuntimeException | LinkageError failure) {
            result.setText("FAIL · 检查失败\n\n" + failure + "\n\n在 Logcat 中搜索 FeatBitConsumer 查看堆栈。");
            Log.e("FeatBitConsumer", "Java model checks FAIL", failure);
        } finally {
            String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
            lastRun.setText("已完成第 " + runCount + " 次检查 · " + time);
        }
    }
}
