package com.walnutgeek.stsloop.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.TextView
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import com.walnutgeek.stsloop.core.CORPUS_SCHEMA

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "stsloop — corpus schema $CORPUS_SCHEMA, $SAMPLE_RATE_HZ Hz"
            textSize = 20f
            // targetSdk 35+ is edge-to-edge: keep content clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left + 48, bars.top + 48, bars.right + 48, bars.bottom + 48)
                insets
            }
        })
    }
}
