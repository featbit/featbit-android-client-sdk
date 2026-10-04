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

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Java · SDK acceptance checks");
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
        retry.setText("Run again");
        retry.setOnClickListener(view -> runChecks());
        content.addView(retry);
        TextView scope = new TextView(this);
        scope.setText(
                getIntent().getBooleanExtra("phase5", false)
                        ? "Checks event delivery, privacy filtering, Flush, offline recovery and Close using the published AAR."
                        : getIntent().getBooleanExtra("phase4", false)
                                ? "Checks Streaming, Polling, Identify, offline recovery and Close using the published AAR."
                                : "Checks models and the local runtime using the published AAR.\nDoes not cover network synchronization, persistence or event delivery.");
        content.addView(scope);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        // Emulator renderer initialization may block the first traversal for many seconds.
        // Start SDK operation budgets only after that unrelated traversal has completed.
        result.getViewTreeObserver()
                .addOnDrawListener(
                        new android.view.ViewTreeObserver.OnDrawListener() {
                            private boolean scheduled;

                            @Override
                            public void onDraw() {
                                if (scheduled) return;
                                scheduled = true;
                                result.post(
                                        () -> {
                                            result.getViewTreeObserver().removeOnDrawListener(this);
                                            Log.i("FeatBitConsumer", "LAUNCHER_READY");
                                            runChecks();
                                        });
                            }
                        });
    }

    private void runChecks() {
        if (getIntent().getBooleanExtra("phase5", false)) {
            result.setText("Checking event delivery...");
            EventSmoke.verify(
                    getApplicationContext(),
                    message ->
                            runOnUiThread(
                                    () -> {
                                        result.setText(message.replace("PHASE5_", "Events "));
                                        Log.i("FeatBitConsumer", message);
                                    }));
            return;
        }
        if (getIntent().getBooleanExtra("phase4", false)) {
            result.setText("Checking network synchronization...");
            NetworkSmoke.verify(
                    getApplicationContext(),
                    message ->
                            runOnUiThread(
                                    () -> {
                                        result.setText(
                                                message.replace("PHASE4_", "Synchronization "));
                                        Log.i("FeatBitConsumer", message);
                                    }));
            return;
        }
        runCount++;
        lastRun.setText("Running check " + runCount + "...");
        try {
            ModelSmoke.verify();
            result.setText("Models PASS · Checking the local runtime...");
            final int run = runCount;
            RuntimeSmoke.verify(
                    getApplicationContext(),
                    message -> {
                        if (run != runCount || isFinishing()) return;
                        result.setText(message);
                        Log.i("FeatBitConsumer", message);
                        String time =
                                new java.text.SimpleDateFormat(
                                                "HH:mm:ss.SSS", java.util.Locale.getDefault())
                                        .format(new java.util.Date());
                        lastRun.setText("Completed check " + run + " at " + time);
                    });
        } catch (AssertionError | RuntimeException | LinkageError failure) {
            result.setText(
                    "FAIL · Check failed\n\n"
                            + failure
                            + "\n\nSearch Logcat for FeatBitConsumer to view the stack trace.");
            Log.e("FeatBitConsumer", "Java model checks FAIL", failure);
            String time =
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                            .format(new java.util.Date());
            lastRun.setText("Completed check " + runCount + " at " + time);
        }
    }
}
