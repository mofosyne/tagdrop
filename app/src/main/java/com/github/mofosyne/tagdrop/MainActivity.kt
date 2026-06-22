package com.github.mofosyne.tagdrop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.databinding.ActivityMainBinding
import com.github.mofosyne.tagdrop.util.BackupManager
import com.github.mofosyne.tagdrop.util.LocationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** The freshly created backup zip's content:// URI, awaiting a destination from [backupSaveLauncher]. */
    private var pendingBackupUri: Uri? = null

    private val backupSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) writeBackupToUri(uri)
    }

    private val restorePickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        if (savedInstanceState == null) {
            val lat = intent.getDoubleExtra(EXTRA_FOCUS_LAT, Double.NaN)
            val lng = intent.getDoubleExtra(EXTRA_FOCUS_LNG, Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) {
                viewModel.focusOnMap(lat, lng)
                binding.bottomNav.selectedItemId = R.id.nav_map
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, MapFragment())
                    .commit()
            } else {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, CollectionsFragment())
                    .commit()
            }
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

        binding.fabCreate.setOnClickListener { showCreateMenu(it) }
    }

    /** Lets the user jump straight to Create Cache / Create Paper from the main screen. */
    private fun showCreateMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_create_fab, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_create -> { startActivity(Intent(this, CreateActivity::class.java)); true }
                R.id.action_create_paper -> { startActivity(Intent(this, CreatePaperActivity::class.java)); true }
                else -> false
            }
        }
        popup.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val lat = intent.getDoubleExtra(EXTRA_FOCUS_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_FOCUS_LNG, Double.NaN)
        if (!lat.isNaN() && !lng.isNaN()) {
            viewModel.focusOnMap(lat, lng)
            switchToMap()
        }
    }

    fun switchToMap() {
        binding.bottomNav.selectedItemId = R.id.nav_map
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_demo_collection -> { addDemoCollection(); true }
            R.id.action_retained_keys -> { startActivity(Intent(this, RetainedKeysActivity::class.java)); true }
            R.id.action_backup -> { startBackup(); true }
            R.id.action_restore -> { restorePickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")); true }
            R.id.action_readme -> { startActivity(Intent(this, ReadMeActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Snapshots the whole local database (every cache, paper, and retained key) as a zip the user can save or share. */
    private fun startBackup() {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { BackupManager.export(this@MainActivity) }
            pendingBackupUri = uri
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.action_backup)
                .setMessage(R.string.backup_ready_message)
                .setPositiveButton(R.string.action_save) { _, _ -> backupSaveLauncher.launch(BackupManager.suggestFilename()) }
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
    }

    private fun writeBackupToUri(uri: Uri) {
        val backupUri = pendingBackupUri ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(backupUri)?.use { input ->
                        contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    }
                }.isSuccess
            }
            Toast.makeText(this@MainActivity, getString(if (ok) R.string.export_saved else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Restoring overwrites every cache, paper, and key currently on this device — confirm before touching anything. */
    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_restore)
            .setMessage(R.string.restore_confirm_message)
            .setPositiveButton(R.string.action_restore) { _, _ -> performRestore(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performRestore(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BackupManager.restore(this@MainActivity, uri) }
            when (result) {
                BackupManager.RestoreResult.Success -> restartApp()
                BackupManager.RestoreResult.IncompatibleVersion ->
                    Toast.makeText(this@MainActivity, R.string.restore_incompatible, Toast.LENGTH_LONG).show()
                BackupManager.RestoreResult.InvalidFile ->
                    Toast.makeText(this@MainActivity, R.string.restore_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Every existing Room/LiveData handle still points at the connection [BackupManager.restore] just closed, so the whole process restarts rather than trying to reload state in place. */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
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
            val userLoc = LocationUtils.lastKnownLocation(this@MainActivity)

            DEMO_ITEMS.forEachIndexed { index, item ->
                val lat = userLoc?.latitude?.plus(item.latOffset) ?: item.fallbackLat
                val lng = userLoc?.longitude?.plus(item.lngOffset) ?: item.fallbackLng

                cacheDao.insert(
                    FoundCache(
                        cacheId         = item.cacheId,
                        discoveredAt    = now - (index * 60_000L),
                        hint            = item.hint,
                        filename        = item.filename,
                        mimeType        = "text/plain",
                        contentBytes    = item.content.toByteArray(),
                        collectionId    = DEMO_COLLECTION_ID,
                        collectionLabel = label,
                        collectionTag   = "demo",
                        lat             = lat,
                        lng             = lng,
                        icon            = item.icon
                    )
                )
            }
            Toast.makeText(this@MainActivity, R.string.demo_collection_added, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_FOCUS_LAT = "extra_focus_lat"
        const val EXTRA_FOCUS_LNG = "extra_focus_lng"

        private const val DEMO_COLLECTION_ID = "0000000000000001"
        private val DEMO_ITEMS = listOf(
            DemoItem(
                cacheId  = "0000000000000101",
                hint     = "Trailhead",
                filename = "trailhead.txt",
                content  = "Welcome to the TagDrop demo trail!\n\nThis sample page was added so you can " +
                    "preview the Collections, History, and Map tabs without scanning a real code. " +
                    "Delete it any time from this collection's detail screen.",
                latOffset = 0.0, lngOffset = 0.0,
                fallbackLat = 37.7694, fallbackLng = -122.4862,
                icon = "🚩"
            ),
            DemoItem(
                cacheId  = "0000000000000102",
                hint     = "Lookout Point",
                filename = "lookout.txt",
                content  = "Lookout Point — the second stop on the demo trail. Tap its pin on the Map tab to jump back here.",
                latOffset = 0.005, lngOffset = 0.005,
                fallbackLat = 37.8024, fallbackLng = -122.4058,
                icon = "🔭"
            ),
            DemoItem(
                cacheId  = "0000000000000103",
                hint     = "Finish Line",
                filename = "finish.txt",
                content  = "You've reached the end of the demo trail. Nice work!",
                latOffset = 0.01, lngOffset = 0.0,
                fallbackLat = 37.8199, fallbackLng = -122.4783,
                icon = "🏁"
            )
        )
    }
}

private data class DemoItem(
    val cacheId: String,
    val hint: String,
    val filename: String,
    val content: String,
    val latOffset: Double,
    val lngOffset: Double,
    val fallbackLat: Double,
    val fallbackLng: Double,
    val icon: String? = null
)
