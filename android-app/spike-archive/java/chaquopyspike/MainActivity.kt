package com.example.chaquopyspike

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply { setPadding(24, 24, 24, 24); text = "running..." }
        setContentView(ScrollView(this).apply { addView(tv) })
        Thread {
            val results = Spike.runAll(this)
            val text = results.joinToString("\n\n") { (k, v) -> "$k: $v" }
            runOnUiThread { tv.text = text }
        }.start()
    }
}
