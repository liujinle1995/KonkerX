package com.huanzhi.sample

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 用来验证分身是否真的隔离：
 *  - 显示自己的 packageName（分身应该是一个不同的包名）
 *  - 显示本地保存的"昵称"和计数（分身应该有一份独立的 SharedPreferences）
 *  - 用主题色画一个色块（验证资源能正常解析）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var accountView: TextView
    private lateinit var counterView: TextView
    private var counter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 诊断：确认资源和主题在克隆环境里是否正常解析
        try {
            val res = resources
            val appNameId = res.getIdentifier("app_name", "string", packageName)
            val tv = android.util.TypedValue()
            val hasWindowBg = theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
            android.util.Log.i(
                "KXSample",
                "pkg=$packageName appNameId=0x${appNameId.toString(16)} " +
                    "windowBg=$hasWindowBg(0x${tv.resourceId.toString(16)}) " +
                    "themeRes=${readThemeRes()}"
            )
        } catch (t: Throwable) {
            android.util.Log.e("KXSample", "probe failed", t)
        }

        // 诊断：确认主题里到底有没有 AppCompat 的属性
        try {
            fun probe(tag: String) {
                val ta = theme.obtainStyledAttributes(
                    intArrayOf(androidx.appcompat.R.attr.windowActionBar)
                )
                android.util.Log.i("KXSample", "$tag windowActionBar=${ta.hasValue(0)}")
                ta.recycle()
            }
            probe("beforeSet:")
            setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
            probe("afterSet:")
        } catch (t: Throwable) {
            android.util.Log.e("KXSample", "theme probe failed", t)
        }

        val prefs = getSharedPreferences("demo", MODE_PRIVATE)
        counter = prefs.getInt("counter", 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(56, 120, 56, 56)
        }

        root.addView(TextView(this).apply {
            text = "HuanZhi 测试目标"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "packageName:\n$packageName"
            textSize = 14f
            setPadding(0, 28, 0, 0)
        })

        accountView = TextView(this).apply {
            text = "昵称: ${prefs.getString("account", "(未设置)")}"
            textSize = 16f
            setPadding(0, 28, 0, 0)
        }
        root.addView(accountView)

        counterView = TextView(this).apply {
            text = "本地计数: $counter"
            textSize = 16f
            setPadding(0, 12, 0, 0)
        }
        root.addView(counterView)

        root.addView(Button(this).apply {
            text = "计数 +1（写入本包私有存储）"
            setOnClickListener {
                counter++
                counterView.text = "本地计数: $counter"
                prefs.edit().putInt("counter", counter).apply()
            }
        })

        root.addView(Button(this).apply {
            text = "改个昵称"
            setOnClickListener {
                val name = "账号" + (1000..9999).random()
                accountView.text = "昵称: $name"
                prefs.edit().putString("account", name).apply()
            }
        })

        root.addView(Button(this).apply {
            text = "打开第二页（验证分身内部跳转）"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, SecondActivity::class.java))
            }
        })

        root.addView(TextView(this).apply {
            text = "进程: ${android.os.Process.myPid()}  uid: ${android.os.Process.myUid()}"
            textSize = 12f
            setPadding(0, 36, 0, 0)
        })

        setContentView(root)
    }

    /** Activity.getThemeResId 是隐藏 API，这里反射读一下，仅用于诊断 */
    private fun readThemeRes(): Int = try {
        val m = android.app.Activity::class.java.getDeclaredMethod("getThemeResId")
        m.isAccessible = true
        m.invoke(this) as Int
    } catch (t: Throwable) {
        -1
    }
}
