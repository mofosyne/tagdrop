package com.github.mofosyne.tagdrop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.RetainedKey
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.db.hasPendingPassphrase
import com.github.mofosyne.tagdrop.data.db.isOpenable
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import kotlinx.coroutines.CompletableDeferred
import com.github.mofosyne.tagdrop.data.format.TagDropPayload
import com.github.mofosyne.tagdrop.data.format.matchesScannedPaper
import com.github.mofosyne.tagdrop.databinding.ActivityCollectionDetailBinding
import com.github.mofosyne.tagdrop.ui.CollectionDetailAdapter
import com.github.mofosyne.tagdrop.ui.PageItem
import com.github.mofosyne.tagdrop.util.ContentExporter
import com.github.mofosyne.tagdrop.util.showCborDebugDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Map" screen for one collection: lists its pages and lets you open or delete cached ones. */
class CollectionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionDetailBinding
    private var currentPaper: ScannedPaper? = null

    /** Ad-hoc mode only: the collection_id shared by every cache shown, and those caches. */
    private var currentCollectionId: String? = null
    private var currentAdHocCaches: List<FoundCache> = emptyList()

    /** Loose mode only: the single cache shown. */
    private var currentLooseCache: FoundCache? = null

    /** Cached items in this collection that have content and can be bundled into an export zip. */
    private var exportableCaches: List<FoundCache> = emptyList()

    /** The cache awaiting a destination from [saveLauncher]. */
    private var pendingSaveCache: FoundCache? = null

    /** The export zip's content:// URI awaiting a destination from [saveZipLauncher]. */
    private var pendingExportZipUri: Uri? = null

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) writeToUri(uri)
    }

    private val saveZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) writeZipToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = CollectionDetailAdapter(
            onOpen = { cache -> openCache(cache) },
            onDelete = { cache -> confirmDelete(cache) },
            onMap = { lat, lng -> jumpToMap(lat, lng) },
            onInspectCbor = { cache -> inspectCacheCbor(cache) },
            onShare = { cache -> shareCache(cache) },
            onSave = { cache -> saveCache(cache) },
            onShareQr = { cache -> shareCacheViaQr(cache) }
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
        var latestPapers: List<ScannedPaper> = emptyList()

        fun render() {
            val paper = latestPaper ?: return
            currentPaper = paper
            invalidateOptionsMenu()
            val manifest = TagDropCodec.decodePaperManifestCbor(paper.cborBytes)
            val files = manifest?.files.orEmpty()
            val related = manifest?.related.orEmpty()
            val cachesById = latestCaches.associateBy { it.cacheId }

            val fileItems = files.map { f -> PageItem.PaperFile(f.slug, f.mimeType, cachesById[f.fileId.toHex()]) }
            val items = buildList {
                addAll(fileItems)
                if (related.isNotEmpty()) {
                    add(PageItem.SectionHeader(getString(R.string.paper_related_header)))
                    related.forEach { r ->
                        add(PageItem.RelatedHint(r, latestPapers.find { r.matchesScannedPaper(it) }))
                    }
                }
            }
            adapter.submitList(items)
            binding.textEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            exportableCaches = fileItems.mapNotNull { it.cache }.filter { it.isOpenable }

            title = paper.label ?: getString(R.string.paper_manifest_label)
            // Each page may be focused on its own theme, so accumulate every
            // distinct tag seen across the paper and its cached pages.
            val tags = (listOf(paper.collectionTag) + fileItems.mapNotNull { it.cache?.collectionTag })
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
        db.paperDao().getAll().observe(this) { papers ->
            latestPapers = papers
            render()
        }
    }

    /** Ad-hoc mode: all caches sharing a collection_id. */
    private fun observeAdHoc(db: AppDatabase, collectionId: String, adapter: CollectionDetailAdapter) {
        currentCollectionId = collectionId
        db.cacheDao().getByCollectionId(collectionId).observe(this) { caches ->
            if (caches.isEmpty()) {
                finish()
                return@observe
            }
            adapter.submitList(caches.map { PageItem.CacheEntry(it) })
            binding.textEmpty.visibility = View.GONE
            exportableCaches = caches.filter { it.isOpenable }
            currentAdHocCaches = caches
            invalidateOptionsMenu()

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
            currentLooseCache = cache
            invalidateOptionsMenu()
        }
    }

    private fun openCache(cache: FoundCache) {
        if (cache.hasPendingPassphrase) {
            lifecycleScope.launch { tryPassphraseUnlock(cache) }
            return
        }
        val bytes = cache.contentBytes ?: return
        launchCacheViewer(cache, bytes)
    }

    private fun launchCacheViewer(cache: FoundCache, bytes: ByteArray) {
        val dataUri = "data:${cache.mimeType};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        startActivity(
            Intent(this, ViewDataUriActivity::class.java)
                .putExtra(ViewDataUriActivity.EXTRA_DATA_URI, dataUri)
                .putExtra(ViewDataUriActivity.EXTRA_CACHE_ID, cache.cacheId)
        )
    }

    private suspend fun tryPassphraseUnlock(cache: FoundCache) {
        val salt = cache.kdfSalt ?: return
        val result = askPassphrase(cache.hint)
        if (result == null) {
            val bytes = cache.contentBytes ?: return
            launchCacheViewer(cache, bytes)
            return
        }
        val (passphrase, shouldStore) = result
        val derivedKey = TagDropCodec.deriveKeyFromPassphrase(passphrase, salt, 100000)
        val override = TagDropCodec.tryDecryptOverrideMap(cache.pendingOverrideBlob!!, derivedKey, cache.pendingCompression)
        if (override == null) {
            Toast.makeText(this, getString(R.string.passphrase_wrong), Toast.LENGTH_SHORT).show()
            val bytes = cache.contentBytes ?: return
            launchCacheViewer(cache, bytes)
            return
        }
        if (shouldStore) {
            val saltHint = salt.take(4).joinToString("") { "%02x".format(it) }
            AppDatabase.get(this).keyDao().insert(
                RetainedKey(
                    keyHex = derivedKey.joinToString("") { "%02x".format(it) },
                    discoveredAt = System.currentTimeMillis(),
                    hint = "passphrase (salt: $saltHint…)"
                )
            )
        }
        val unlocked = cache.copy(
            hint = override.hint ?: cache.hint,
            filename = override.filename ?: cache.filename,
            mimeType = override.mimeType ?: cache.mimeType,
            contentBytes = override.content ?: cache.contentBytes,
            pendingOverrideBlob = null,
            pendingCompression = 0,
            kdfAlg = 0,
            kdfSalt = null
        )
        AppDatabase.get(this).cacheDao().insert(unlocked)
        launchCacheViewer(unlocked, unlocked.contentBytes ?: ByteArray(0))
    }

    private suspend fun askPassphrase(contentHint: String?): Pair<String, Boolean>? {
        val deferred = CompletableDeferred<Pair<String, Boolean>?>()
        runOnUiThread {
            val editText = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = getString(R.string.passphrase_hint_text)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.passphrase_dialog_title))
                .apply { contentHint?.let { setMessage(it) } }
                .setView(editText)
                .setPositiveButton(getString(R.string.passphrase_remember_key)) { _, _ ->
                    deferred.complete(editText.text.toString() to true)
                }
                .setNeutralButton(getString(R.string.passphrase_unlock_once)) { _, _ ->
                    deferred.complete(editText.text.toString() to false)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    deferred.complete(null)
                }
                .setOnCancelListener { deferred.complete(null) }
                .show()
        }
        return deferred.await()
    }

    private fun shareCache(cache: FoundCache) {
        val intent = ContentExporter.shareIntent(this, cache) ?: return
        startActivity(Intent.createChooser(intent, getString(R.string.share_uri_title)))
    }

    private fun shareCacheViaQr(cache: FoundCache) {
        startActivity(Intent(this, ShareQrActivity::class.java).putExtra(ShareQrActivity.EXTRA_CACHE_ID, cache.cacheId))
    }

    /** Lets the user pick a destination and saves a copy of the cached content there. */
    private fun saveCache(cache: FoundCache) {
        pendingSaveCache = cache
        saveLauncher.launch(ContentExporter.suggestFilename(cache))
    }

    private fun writeToUri(uri: Uri) {
        val bytes = pendingSaveCache?.contentBytes ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            Toast.makeText(this@CollectionDetailActivity, getString(if (ok) R.string.export_saved else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Bundles every cached item in this collection into a zip and offers to save or share it. */
    private fun exportCollection() {
        val uri = ContentExporter.exportZip(this, exportableCaches) ?: return
        pendingExportZipUri = uri
        AlertDialog.Builder(this)
            .setTitle(R.string.action_export_collection)
            .setMessage(getString(R.string.export_collection_message, exportableCaches.size))
            .setPositiveButton(R.string.action_save) { _, _ -> saveZipLauncher.launch(exportZipFilename()) }
            .setNeutralButton(R.string.action_share) { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_uri_title)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportZipFilename(): String {
        val base = title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "tagdrop-export"
        return "$base.zip"
    }

    private fun writeZipToUri(uri: Uri) {
        val zipUri = pendingExportZipUri ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(zipUri)?.use { input ->
                        contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    }
                }.isSuccess
            }
            Toast.makeText(this@CollectionDetailActivity, getString(if (ok) R.string.export_saved else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Reconstructs the CBOR for a cached page from its decoded fields and shows the debug dialog. */
    private fun inspectCacheCbor(cache: FoundCache) {
        val payload = TagDropPayload.Single(
            cacheId         = cache.cacheId.hexToBytes(),
            hint            = cache.hint,
            filename        = cache.filename,
            mimeType        = cache.mimeType,
            compression     = TagDropCodec.COMPRESSION_NONE,
            content         = cache.contentBytes ?: ByteArray(0),
            collectionId    = cache.collectionId?.hexToBytes(),
            collectionLabel = cache.collectionLabel,
            collectionTag   = cache.collectionTag,
            icon            = cache.icon
        )
        showCborDebugDialog(TagDropCodec.singleCbor(payload), cache.cacheId)
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

    /** Ad-hoc mode: deletes every cached item in this collection. Loose mode: deletes the one cache. */
    private fun confirmDeleteCollection() {
        val collectionId = currentCollectionId
        if (collectionId != null) {
            val label = currentAdHocCaches.firstOrNull { it.collectionLabel != null }?.collectionLabel
                ?: getString(R.string.collection_adhoc_default_title, collectionId.take(8))
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_collection_confirm_title)
                .setMessage(getString(R.string.delete_collection_confirm_message, label, currentAdHocCaches.size))
                .setPositiveButton(R.string.button_delete) { _, _ ->
                    lifecycleScope.launch {
                        AppDatabase.get(this@CollectionDetailActivity).cacheDao().deleteByCollectionId(collectionId)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        currentLooseCache?.let { confirmDelete(it) }
    }

    private fun confirmDeletePaper(paper: ScannedPaper) {
        val label = paper.label ?: paper.rootHash.take(12)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_paper_confirm_title)
            .setMessage(getString(R.string.delete_paper_confirm_message, label))
            .setPositiveButton(R.string.button_delete) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@CollectionDetailActivity).paperDao().delete(paper)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_collection_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_delete_paper).isVisible = currentPaper != null
        menu.findItem(R.id.action_export_collection).isVisible = exportableCaches.size > 1
        menu.findItem(R.id.action_delete_collection).isVisible = currentCollectionId != null || currentLooseCache != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_delete_paper -> { currentPaper?.let { confirmDeletePaper(it) }; true }
        R.id.action_export_collection -> { exportCollection(); true }
        R.id.action_delete_collection -> { confirmDeleteCollection(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = runCatching {
        ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
    }.getOrElse { this.encodeToByteArray() }

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
