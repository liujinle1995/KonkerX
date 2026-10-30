package com.huanzhi.sample

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 第二页。存在意义是验证「分身内部页面跳转」：
 *
 * 原应用里写的是 startActivity(Intent(this, SecondActivity::class.java))，
 * 编译后是显式 intent（component = 当前包名 + 类名）。分身包名变了以后，
 * 这条显式 intent 必须能落到分身自己的 SecondActivity 上，否则页面跳转会
 * "Activity class does not exist"。
 *
 * 这也顺带验证：改写后的清单把 **所有** Activity 都保留了，而不只是入口页。
 */
class SecondActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.i("KXSample", "second: pkg=$packageName pid=${android.os.Process.myPid()}")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(56, 160, 56, 56)
        }
        root.addView(TextView(this).apply {
            text = "第二页"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "packageName:\n$packageName\n\n进程: ${android.os.Process.myPid()}  uid: ${android.os.Process.myUid()}"
            textSize = 14f
            setPadding(0, 28, 0, 0)
        })
        setContentView(root)
    }
}
