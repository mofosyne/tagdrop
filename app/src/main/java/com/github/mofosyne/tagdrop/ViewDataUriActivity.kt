package com.github.mofosyne.tagdrop

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.mofosyne.tagdrop.databinding.ActivityViewdatauriBinding

class ViewDataUriActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewdatauriBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewdatauriBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dataUri = intent.getStringExtra(EXTRA_DATA_URI) ?: return

        with(binding.htmldisp.settings) {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        binding.htmldisp.loadUrl(dataUri)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        const val EXTRA_DATA_URI = "extra_data_uri"
    }
}
