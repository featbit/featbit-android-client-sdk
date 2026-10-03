package co.featbit.consumer.kotlin

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Device entry point for the existing AAR model checks, not an SDK demo. */
class SmokeActivity : Activity() {
    private lateinit var result: TextView
    private lateinit var lastRun: TextView
    private var runCount = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "Kotlin · SDK acceptance checks"
            textSize = 22f
        })
        result = TextView(this).apply {
            textSize = 18f
            setPadding(0, padding, 0, padding)
            setTextIsSelectable(true)
        }
        content.addView(result)
        lastRun = TextView(this)
        content.addView(lastRun)
        content.addView(Button(this).apply {
            text = "Run again"
            setOnClickListener { runChecks() }
        })
        content.addView(TextView(this).apply {
            text = when {
                intent.getBooleanExtra("phase5", false) -> "Checks event delivery, privacy filtering, Flush, offline recovery and Close using the published AAR."
                intent.getBooleanExtra("phase4", false) -> "Checks Streaming, Polling, Identify, offline recovery and Close using the published AAR."
                else -> "Checks the local runtime, cache and anonymous identity persistence using the published AAR.\nDoes not cover network synchronization or event delivery."
            }
        })
        setContentView(ScrollView(this).apply { addView(content) })
        // Keep first-frame renderer stalls outside SDK operation wait budgets.
        result.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            private var scheduled = false
            override fun onDraw() {
                if (scheduled) return
                scheduled = true
                result.post {
                    result.viewTreeObserver.removeOnDrawListener(this)
                    Log.i("FeatBitConsumer", "LAUNCHER_READY")
                    runChecks()
                }
            }
        })
    }

    private fun runChecks() {
        if (intent.getBooleanExtra("phase5", false)) {
            result.text = "Checking event delivery..."
            EventSmoke.verify(applicationContext) { message -> runOnUiThread {
                result.text = message.replace("PHASE5_", "Events "); Log.i("FeatBitConsumer", message)
            } }
            return
        }
        if (intent.getBooleanExtra("phase4", false)) {
            result.text = "Checking network synchronization..."
            NetworkSmoke.verify(applicationContext) { message -> runOnUiThread {
                result.text = message.replace("PHASE4_", "Synchronization "); Log.i("FeatBitConsumer", message)
            } }
            return
        }
        runCount++
        lastRun.text = "Running check $runCount..."
        try {
            ModelSmoke.verify()
            result.text = "Models PASS · Checking the local runtime..."
            val run = runCount
            RuntimeSmoke.verify(applicationContext) { message -> runOnUiThread {
                if (run == runCount && !isFinishing) {
                    result.text = message
                    Log.i("FeatBitConsumer", message)
                    val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
                    lastRun.text = "Completed check $run at $time"
                }
            } }
        } catch (failure: AssertionError) {
            showFailure(failure)
        } catch (failure: RuntimeException) {
            showFailure(failure)
        } catch (failure: LinkageError) {
            showFailure(failure)
        }
    }

    private fun showFailure(failure: Throwable) {
        result.text = "FAIL · Check failed\n\n$failure\n\nSearch Logcat for FeatBitConsumer to view the stack trace."
        Log.e("FeatBitConsumer", "Kotlin model checks FAIL", failure)
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        lastRun.text = "Completed check $runCount at $time"
    }
}
