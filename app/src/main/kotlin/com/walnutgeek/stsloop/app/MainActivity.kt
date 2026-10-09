package com.walnutgeek.stsloop.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import com.walnutgeek.stsloop.core.CORPUS_SCHEMA

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "stsloop — corpus schema $CORPUS_SCHEMA, $SAMPLE_RATE_HZ Hz"
            textSize = 20f
            setPadding(48, 48, 48, 48)
        })
    }
}
