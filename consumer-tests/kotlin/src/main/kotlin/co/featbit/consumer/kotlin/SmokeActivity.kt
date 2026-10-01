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
            text = "Kotlin · SDK 公共模型检查"
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
            text = "仅验证独立 AAR 的公共模型调用。\n不验证网络同步、缓存或事件发送。"
        })
        setContentView(ScrollView(this).apply { addView(content) })
        runChecks()
    }

    private fun runChecks() {
        runCount++
        try {
            ModelSmoke.verify()
            result.text = "PASS · 检查通过\n\n用户与配置、空 Bootstrap、JSON 值、非法数值、版本及自定义数据源声明。"
            Log.i("FeatBitConsumer", "Kotlin model checks PASS")
        } catch (failure: AssertionError) {
            showFailure(failure)
        } catch (failure: RuntimeException) {
            showFailure(failure)
        } catch (failure: LinkageError) {
            showFailure(failure)
        } finally {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            lastRun.text = "已完成第 $runCount 次检查 · $time"
        }
    }

    private fun showFailure(failure: Throwable) {
        result.text = "FAIL · 检查失败\n\n$failure\n\n在 Logcat 中搜索 FeatBitConsumer 查看堆栈。"
        Log.e("FeatBitConsumer", "Kotlin model checks FAIL", failure)
    }
}
