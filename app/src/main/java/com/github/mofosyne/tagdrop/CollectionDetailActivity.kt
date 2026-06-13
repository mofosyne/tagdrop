package com.github.mofosyne.tagdrop

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.databinding.ActivityCollectionDetailBinding
import com.github.mofosyne.tagdrop.ui.CollectionDetailAdapter
import com.github.mofosyne.tagdrop.ui.PageItem
import kotlinx.coroutines.launch

/** "Map" screen for one collection: lists its pages and lets you open or delete cached ones. */
class CollectionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = CollectionDetailAdapter(
            onOpen = { cache -> openCache(cache) },
            onDelete = { cache -> confirmDelete(cache) },
            onMap = { lat, lng -> jumpToMap(lat, lng) }
        )
        binding.recyclerPages.layoutManager = LinearLayoutManager(this)
        binding.recyclerPages.adapter = adapter

        val db = AppDatabase.get(this)
        val rootHash = intent.getStringExtra(EXTRA_ROOT_HASH)
        val collectionId = intent.getStringExtra(EXTRA_COLLECTION_ID)
        val cacheId = intent.getStringExtra(EXTRA_CACHE_ID)

        when {
            rootHash != null -> observePaper(db, rootHash, adapter)
            collectionId != null -> observeAdHoc(db, collectionId, adapter)
            cacheId != null -> observeLoose(db, cacheId, adapter)
            else -> finish()
        }
    }

    /** Paper mode: file directory comes from the paper manifest; cache status comes from all caches. */
    private fun observePaper(db: AppDatabase, rootHash: String, adapter: CollectionDetailAdapter) {
        var latestPaper: ScannedPaper? = null
        var latestCaches: List<FoundCache> = emptyList()

        fun render() {
            val paper = latestPaper ?: return
            val files = TagDropCodec.decodePaperManifestCbor(paper.cborBytes)?.files.orEmpty()
            val cachesById = latestCaches.associateBy { it.cacheId }
            val items = files.map { f -> PageItem.PaperFile(f.slug, f.mimeType, cachesById[f.fileId.toHex()]) }
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

            title = paper.label ?: getString(R.string.paper_manifest_label)
            // Each page may be focused on its own theme, so accumulate every
            // distinct tag seen across the paper and its cached pages.
            val tags = (listOf(paper.collectionTag) + items.mapNotNull { it.cache?.collectionTag })
                .filterNotNull().distinct()
            val info = buildString {
                if (paper.set != null) append(getString(R.string.paper_set, paper.set))
                if (tags.isNotEmpty()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(tags.joinToString(" ") { "#$it" })
                }
            }
            binding.textInfo.text = info
            binding.textInfo.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE

            binding.textInspectCbor.visibility = View.VISIBLE
            binding.textInspectCbor.setOnClickListener { showCborDebugDialog(paper.cborBytes, rootHash) }
        }

        // Only the paper observer's null branch finishes the activity: a transient empty
        // cache list (e.g. before the second observer fires) must not close this screen.
        db.paperDao().observeByRootHash(rootHash).observe(this) { paper ->
            if (paper == null) {
                finish()
                return@observe
            }
            latestPaper = paper
            render()
        }
        db.cacheDao().getAllCaches().observe(this) { caches ->
            latestCaches = caches
            render()
        }
    }

    /** Ad-hoc mode: all caches sharing a collection_id. */
    private fun observeAdHoc(db: AppDatabase, collectionId: String, adapter: CollectionDetailAdapter) {
        db.cacheDao().getByCollectionId(collectionId).observe(this) { caches ->
            if (caches.isEmpty()) {
                finish()
                return@observe
            }
            adapter.submitList(caches.map { PageItem.CacheEntry(it) })
            binding.textEmpty.visibility = View.GONE

            title = caches.firstOrNull { it.collectionLabel != null }?.collectionLabel
                ?: getString(R.string.collection_adhoc_default_title, collectionId.take(8))
            val tags = caches.mapNotNull { it.collectionTag }.distinct()
            binding.textInfo.text = tags.joinToString(" ") { "#$it" }
            binding.textInfo.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Loose mode: a single cached item with no collection — its own one-page collection. */
    private fun observeLoose(db: AppDatabase, cacheId: String, adapter: CollectionDetailAdapter) {
        db.cacheDao().observeById(cacheId).observe(this) { cache ->
            if (cache == null) {
                finish()
                return@observe
            }
            adapter.submitList(listOf(PageItem.CacheEntry(cache)))
            binding.textEmpty.visibility = View.GONE
            binding.textInfo.visibility = View.GONE
            title = cache.hint ?: cache.filename ?: getString(R.string.collection_untitled)
        }
    }

    private fun openCache(cache: FoundCache) {
        val bytes = cache.contentBytes ?: return
        val dataUri = "data:${cache.mimeType};base64," +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        startActivity(
            Intent(this, ViewDataUriActivity::class.java)
                .putExtra(ViewDataUriActivity.EXTRA_DATA_URI, dataUri)
        )
    }

    /** Shows the raw CBOR bytes of a scanned paper manifest, annotated with field names. */
    private fun showCborDebugDialog(cbor: ByteArray, rootHash: String) {
        val view = layoutInflater.inflate(R.layout.dialog_cbor_debug, null)
        view.findViewById<TextView>(R.id.textCborDump).text = TagDropCodec.describeCbor(cbor)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cbor_debug_title, rootHash))
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun jumpToMap(lat: Double, lng: Double) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FOCUS_LAT, lat)
            .putExtra(MainActivity.EXTRA_FOCUS_LNG, lng)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun confirmDelete(cache: FoundCache) {
        val label = cache.hint ?: cache.filename ?: cache.cacheId.take(12)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, label))
            .setPositiveButton(R.string.button_delete) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@CollectionDetailActivity).cacheDao().delete(cache)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val EXTRA_ROOT_HASH = "extra_root_hash"
        const val EXTRA_COLLECTION_ID = "extra_collection_id"
        const val EXTRA_CACHE_ID = "extra_cache_id"
    }
}

/** Opens the collection-detail ("map") screen for a paper, ad-hoc group, or loose scan. */
fun Context.openCollectionDetail(rootHash: String? = null, collectionId: String? = null, cacheId: String? = null) {
    val intent = Intent(this, CollectionDetailActivity::class.java)
    rootHash?.let { intent.putExtra(CollectionDetailActivity.EXTRA_ROOT_HASH, it) }
    collectionId?.let { intent.putExtra(CollectionDetailActivity.EXTRA_COLLECTION_ID, it) }
    cacheId?.let { intent.putExtra(CollectionDetailActivity.EXTRA_CACHE_ID, it) }
    startActivity(intent)
}
