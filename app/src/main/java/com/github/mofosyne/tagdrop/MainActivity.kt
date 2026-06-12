package com.github.mofosyne.tagdrop

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.databinding.ActivityMainBinding
import com.github.mofosyne.tagdrop.ui.CollectionItem
import com.github.mofosyne.tagdrop.ui.CollectionListAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val adapter = CollectionListAdapter { item -> openCollection(item) }
        binding.recyclerCollections.layoutManager = LinearLayoutManager(this)
        binding.recyclerCollections.adapter = adapter

        var latestPapers: List<ScannedPaper> = emptyList()
        var latestCaches: List<FoundCache> = emptyList()

        fun render() {
            val items = CollectionItem.build(latestPapers, latestCaches)
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        val db = AppDatabase.get(this)
        db.paperDao().getAll().observe(this) { papers ->
            latestPapers = papers
            render()
        }
        db.cacheDao().getAllCaches().observe(this) { caches ->
            latestCaches = caches
            render()
        }

        binding.fabScan.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
    }

    private fun openCollection(item: CollectionItem) {
        val intent = Intent(this, CollectionDetailActivity::class.java)
        when (item) {
            is CollectionItem.Paper -> intent.putExtra(CollectionDetailActivity.EXTRA_ROOT_HASH, item.paper.rootHash)
            is CollectionItem.AdHoc -> intent.putExtra(CollectionDetailActivity.EXTRA_COLLECTION_ID, item.collectionId)
            is CollectionItem.Loose -> intent.putExtra(CollectionDetailActivity.EXTRA_CACHE_ID, item.cache.cacheId)
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_create -> { startActivity(Intent(this, CreateActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
