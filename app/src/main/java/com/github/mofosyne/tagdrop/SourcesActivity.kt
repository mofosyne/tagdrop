package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.data.DropEntryCache
import com.github.mofosyne.tagdrop.data.RelatedSource
import com.github.mofosyne.tagdrop.data.SourceFetcher
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.DropSource
import com.github.mofosyne.tagdrop.databinding.ActivitySourcesBinding
import com.github.mofosyne.tagdrop.databinding.ItemSourceBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists all registered drop sources and lets the user toggle, refresh, or delete each one.
 * Enabled sources are fetched on refresh; their entries populate [DropEntryCache] and appear
 * as pins on the map tab.
 */
class SourcesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourcesBinding
    private val adapter = SourceAdapter(
        onRefresh  = { source -> refreshSource(source) },
        onEnable   = { source -> setEnabled(source, true) },
        onDisable  = { source -> setEnabled(source, false) },
        onDelete   = { source -> confirmDelete(source) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left   = systemBars.left,
                top    = systemBars.top,
                right  = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_sources)

        binding.recyclerSources.layoutManager = LinearLayoutManager(this)
        binding.recyclerSources.adapter = adapter

        AppDatabase.get(this).dropSourceDao().getAll().observe(this) { sources ->
            adapter.submitList(sources)
            binding.textEmpty.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerSources.visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.fabAddSource.setOnClickListener { addSourceManually() }
    }

    private fun addSourceManually() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val urlInput = EditText(this).apply {
            hint = "https://example.com/drops.json"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.source_name_hint)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(nameInput)
            addView(urlInput)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_source_manually)
            .setView(container)
            .setPositiveButton(R.string.add_source_confirm) { _, _ ->
                val url = urlInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifEmpty { url }
                if (url.isNotEmpty()) {
                    lifecycleScope.launch {
                        val db = AppDatabase.get(this@SourcesActivity)
                        val sourceId = db.dropSourceDao().insert(
                            DropSource(name = name, url = url, enabled = true)
                        )
                        val json = SourceFetcher.fetch(url)
                        if (json != null) {
                            DropEntryCache.update(sourceId, json.drops)
                            db.dropSourceDao().update(
                                DropSource(id = sourceId,
                                           name = json.label ?: name,
                                           url = url,
                                           enabled = true,
                                           lastFetchedAt = System.currentTimeMillis(),
                                           entryCount = json.drops.size)
                            )
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sources, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh_all     -> { refreshAll(); true }
        R.id.action_reload_defaults -> { reloadDefaults(); true }
        R.id.action_browse_sources  -> { browseRecommended(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshSource(source: DropSource) {
        if (!source.enabled) return
        lifecycleScope.launch {
            val db = AppDatabase.get(this@SourcesActivity)
            val json = SourceFetcher.fetch(source.url)
            if (json == null) {
                db.dropSourceDao().update(source.copy(lastFetchFailed = true))
                Toast.makeText(this@SourcesActivity,
                    R.string.source_fetch_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            DropEntryCache.update(source.id, json.drops)
            db.dropSourceDao().update(
                source.copy(
                    name            = json.label ?: source.name,
                    lastFetchedAt   = System.currentTimeMillis(),
                    entryCount      = json.drops.size,
                    lastFetchFailed = false
                )
            )
            if (json.relatedSources.isNotEmpty()) {
                showSourcePickerDialog(
                    getString(R.string.source_related_title, json.label ?: source.name),
                    json.relatedSources
                )
            }
        }
    }

    private fun setEnabled(source: DropSource, enabled: Boolean) {
        lifecycleScope.launch {
            AppDatabase.get(this@SourcesActivity).dropSourceDao().update(source.copy(enabled = enabled))
            if (!enabled) DropEntryCache.remove(source.id)
        }
    }

    private fun refreshAll() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@SourcesActivity)
            val sources = db.dropSourceDao().getEnabled()
            var anyFailed = false
            for (source in sources) {
                val json = SourceFetcher.fetch(source.url)
                if (json == null) {
                    db.dropSourceDao().update(source.copy(lastFetchFailed = true))
                    anyFailed = true
                    continue
                }
                DropEntryCache.update(source.id, json.drops)
                db.dropSourceDao().update(
                    source.copy(
                        name            = json.label ?: source.name,
                        lastFetchedAt   = System.currentTimeMillis(),
                        entryCount      = json.drops.size,
                        lastFetchFailed = false
                    )
                )
            }
            if (anyFailed) {
                Toast.makeText(this@SourcesActivity,
                    R.string.source_fetch_some_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun browseRecommended() {
        lifecycleScope.launch {
            val dir = SourceFetcher.fetchDirectory()
            if (dir == null) {
                Toast.makeText(this@SourcesActivity,
                    R.string.source_browse_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showSourcePickerDialog(dir.label ?: getString(R.string.source_browse_title), dir.sources)
        }
    }

    private suspend fun showSourcePickerDialog(title: String, candidates: List<RelatedSource>) {
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.source_browse_none, Toast.LENGTH_SHORT).show()
            return
        }
        val db = AppDatabase.get(this)
        val existingUrls = db.dropSourceDao().getAllOnce().map { it.url }.toSet()
        val newCandidates = candidates.filter { it.url !in existingUrls }
        if (newCandidates.isEmpty()) {
            Toast.makeText(this, R.string.source_browse_all_added, Toast.LENGTH_SHORT).show()
            return
        }

        val padding = (16 * resources.displayMetrics.density).toInt()
        val checks = newCandidates.map { source ->
            CheckBox(this).apply {
                isChecked = true
                text = source.name
            }
        }
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            newCandidates.forEachIndexed { i, source ->
                addView(checks[i])
                if (source.description != null) {
                    addView(TextView(this@SourcesActivity).apply {
                        text = source.description
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                        setPadding(padding * 2, 0, 0, padding / 2)
                    })
                }
            }
        }
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.source_browse_add) { _, _ ->
                lifecycleScope.launch {
                    var added = 0
                    newCandidates.forEachIndexed { i, source ->
                        if (checks[i].isChecked) {
                            db.dropSourceDao().insert(
                                DropSource(name = source.name, url = source.url, enabled = false)
                            )
                            added++
                        }
                    }
                    if (added > 0) {
                        Toast.makeText(this@SourcesActivity,
                            resources.getQuantityString(R.plurals.source_browse_added, added, added),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reloadDefaults() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@SourcesActivity)
            val existing = db.dropSourceDao().getAllOnce().map { it.url }.toSet()
            DEFAULT_SOURCES.forEach { source ->
                if (source.url !in existing) {
                    db.dropSourceDao().insert(source)
                }
            }
            Toast.makeText(this@SourcesActivity,
                R.string.source_reload_defaults_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(source: DropSource) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, source.name))
            .setPositiveButton(R.string.source_action_delete) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@SourcesActivity).dropSourceDao().delete(source)
                    DropEntryCache.remove(source.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Adapter ----

    private class SourceAdapter(
        private val onRefresh : (DropSource) -> Unit,
        private val onEnable  : (DropSource) -> Unit,
        private val onDisable : (DropSource) -> Unit,
        private val onDelete  : (DropSource) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

        private var items: List<DropSource> = emptyList()

        fun submitList(list: List<DropSource>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(private val binding: ItemSourceBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(source: DropSource) {
                val ctx = binding.root.context

                binding.textSourceName.text = source.name
                binding.textSourceUrl.text = source.url
                binding.textSourceEntryCount.text = ctx.getString(
                    R.string.source_entry_count, source.entryCount
                )
                binding.textSourceLastFetched.text = when {
                    source.lastFetchFailed -> ctx.getString(R.string.source_fetch_failed_label)
                    source.lastFetchedAt != null -> ctx.getString(
                        R.string.source_last_fetched, dateFormat().format(Date(source.lastFetchedAt))
                    )
                    else -> ctx.getString(R.string.source_never_fetched)
                }

                // Grey out content area when disabled; only "..." stays fully visible
                val contentAlpha = if (source.enabled) 1f else 0.4f
                binding.textSourceName.alpha = contentAlpha
                binding.textSourceUrl.alpha = contentAlpha
                binding.textSourceEntryCount.alpha = contentAlpha
                binding.textSourceLastFetched.alpha = contentAlpha
                binding.textSourceDisabledLabel.visibility =
                    if (source.enabled) View.GONE else View.VISIBLE

                // Refresh button — inactive when source disabled
                binding.buttonRefreshSource.isEnabled = source.enabled
                binding.buttonRefreshSource.alpha = if (source.enabled) 1f else 0.25f
                binding.buttonRefreshSource.setOnClickListener { onRefresh(source) }

                // Map pin indicator — inactive when source disabled (display only, not a toggle)
                binding.buttonToggleMap.isEnabled = source.enabled
                binding.buttonToggleMap.alpha = if (source.enabled) 1f else 0.25f
                binding.buttonToggleMap.setOnClickListener(null)

                // "..." overflow popup — always active
                binding.buttonSourceMenu.setOnClickListener { anchor ->
                    val popup = PopupMenu(anchor.context, anchor)
                    popup.menuInflater.inflate(R.menu.menu_source_item, popup.menu)
                    popup.menu.findItem(R.id.action_source_enable)?.isVisible  = !source.enabled
                    popup.menu.findItem(R.id.action_source_disable)?.isVisible = source.enabled
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_source_enable  -> { onEnable(source);  true }
                            R.id.action_source_disable -> { onDisable(source); true }
                            R.id.action_source_delete  -> { onDelete(source);  true }
                            else -> false
                        }
                    }
                    popup.show()
                }
            }
        }

        companion object {
            private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        }
    }

    companion object {
        private val DEFAULT_SOURCES = listOf(
            DropSource(name = "TagDrop Community Drops",
                       url  = "https://mofosyne.github.io/tagdrop/db/drops.json",
                       enabled = false),
            DropSource(name = "TagDrop Demo Drops",
                       url  = "https://mofosyne.github.io/tagdrop/db/drops_demo.json",
                       enabled = false)
        )
    }
}
