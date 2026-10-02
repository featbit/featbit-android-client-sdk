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
            text = "Kotlin · SDK 本地运行时检查"
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
            text = "重新检查"
            setOnClickListener { runChecks() }
        })
        content.addView(TextView(this).apply {
            text = "验证独立 AAR 的本地运行时、缓存与匿名身份持久化。\n不验证网络同步或事件发送。"
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
            result.text = "正在检查阶段 5 事件发送…"
            EventSmoke.verify(applicationContext) { message -> runOnUiThread {
                result.text = message; Log.i("FeatBitConsumer", message)
            } }
            return
        }
        if (intent.getBooleanExtra("phase4", false)) {
            result.text = "正在检查阶段 4 网络同步…"
            NetworkSmoke.verify(applicationContext) { message -> runOnUiThread {
                result.text = message; Log.i("FeatBitConsumer", message)
            } }
            return
        }
        runCount++
        lastRun.text = "正在进行第 $runCount 次检查…"
        try {
            ModelSmoke.verify()
            result.text = "模型 PASS · 正在检查本地运行时…"
            val run = runCount
            RuntimeSmoke.verify(applicationContext) { message -> runOnUiThread {
                if (run == runCount && !isFinishing) {
                    result.text = message
                    Log.i("FeatBitConsumer", message)
                    val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
                    lastRun.text = "已完成第 $run 次检查 · $time"
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
        result.text = "FAIL · 检查失败\n\n$failure\n\n在 Logcat 中搜索 FeatBitConsumer 查看堆栈。"
        Log.e("FeatBitConsumer", "Kotlin model checks FAIL", failure)
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        lastRun.text = "已完成第 $runCount 次检查 · $time"
    }
}
