package com.localbabymonitor.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeArea(findViewById(R.id.mainRoot))

        findViewById<View>(R.id.babyCard).setOnClickListener {
            startActivity(Intent(this, BabyActivity::class.java))
        }
        findViewById<View>(R.id.monitorCard).setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }
    }
}
