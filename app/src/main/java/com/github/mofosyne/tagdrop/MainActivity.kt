package com.github.mofosyne.tagdrop

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, CollectionsFragment())
                .commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_collections -> CollectionsFragment()
                R.id.nav_history -> HistoryFragment()
                R.id.nav_map -> MapFragment()
                else -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit()
            true
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
        return when (item.itemId) {
            R.id.action_create -> { startActivity(Intent(this, CreateActivity::class.java)); true }
            R.id.action_demo_collection -> { addDemoCollection(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Inserts a small sample collection with located pages, so the Collections, History,
     * and Map tabs can be previewed without scanning a real TagDrop code. Re-running this
     * just refreshes the same demo items (fixed IDs); delete them from the collection
     * detail screen like any other scan.
     */
    private fun addDemoCollection() {
        lifecycleScope.launch {
            val cacheDao = AppDatabase.get(this@MainActivity).cacheDao()
            val now = System.currentTimeMillis()
            val label = getString(R.string.demo_collection_label)
            DEMO_ITEMS.forEachIndexed { index, item ->
                cacheDao.insert(
                    FoundCache(
                        cacheId         = item.cacheId,
                        discoveredAt    = now - index * 60_000L,
                        hint            = item.hint,
                        filename        = item.filename,
                        mimeType        = "text/plain",
                        contentBytes    = item.content.toByteArray(),
                        collectionId    = DEMO_COLLECTION_ID,
                        collectionLabel = label,
                        collectionTag   = "demo",
                        lat             = item.lat,
                        lng             = item.lng
                    )
                )
            }
            Toast.makeText(this@MainActivity, R.string.demo_collection_added, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val DEMO_COLLECTION_ID = "demo-trail"
        private val DEMO_ITEMS = listOf(
            DemoItem(
                cacheId  = "demo-trailhead",
                hint     = "Trailhead",
                filename = "trailhead.txt",
                content  = "Welcome to the TagDrop demo trail!\n\nThis sample page was added so you can " +
                    "preview the Collections, History, and Map tabs without scanning a real code. " +
                    "Delete it any time from this collection's detail screen.",
                lat = 37.7694, lng = -122.4862
            ),
            DemoItem(
                cacheId  = "demo-lookout",
                hint     = "Lookout Point",
                filename = "lookout.txt",
                content  = "Lookout Point — the second stop on the demo trail. Tap its pin on the Map tab to jump back here.",
                lat = 37.8024, lng = -122.4058
            ),
            DemoItem(
                cacheId  = "demo-finish",
                hint     = "Finish Line",
                filename = "finish.txt",
                content  = "You've reached the end of the demo trail. Nice work!",
                lat = 37.8199, lng = -122.4783
            )
        )
    }
}

private data class DemoItem(
    val cacheId: String,
    val hint: String,
    val filename: String,
    val content: String,
    val lat: Double,
    val lng: Double
)
