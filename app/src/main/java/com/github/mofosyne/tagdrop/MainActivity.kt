package com.github.mofosyne.tagdrop

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.databinding.ActivityMainBinding
import com.github.mofosyne.tagdrop.ui.CacheListAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val adapter = CacheListAdapter(
            onOpen = { cache ->
                val bytes = cache.contentBytes ?: return@CacheListAdapter
                val dataUri = "data:${cache.mimeType};base64," +
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                startActivity(
                    Intent(this, ViewDataUriActivity::class.java)
                        .putExtra(ViewDataUriActivity.EXTRA_DATA_URI, dataUri)
                )
            },
            onDelete = { cache ->
                lifecycleScope.launch {
                    AppDatabase.get(this@MainActivity).cacheDao().delete(cache)
                }
            }
        )

        binding.recyclerCaches.layoutManager = LinearLayoutManager(this)
        binding.recyclerCaches.adapter = adapter

        AppDatabase.get(this).cacheDao().getAllCaches().observe(this) { caches ->
            adapter.submitList(caches)
            binding.textEmpty.visibility = if (caches.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.fabScan.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_papers) {
            startActivity(Intent(this, PapersActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
