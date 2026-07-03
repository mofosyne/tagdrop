package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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
        onToggle = { source -> toggleSource(source) },
        onDelete = { source -> confirmDelete(source) }
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
        R.id.action_refresh_all -> { refreshAll(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleSource(source: DropSource) {
        lifecycleScope.launch {
            val updated = source.copy(enabled = !source.enabled)
            AppDatabase.get(this@SourcesActivity).dropSourceDao().update(updated)
            if (!updated.enabled) DropEntryCache.remove(source.id)
        }
    }

    private fun refreshAll() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@SourcesActivity)
            val sources = db.dropSourceDao().getEnabled()
            for (source in sources) {
                val json = SourceFetcher.fetch(source.url) ?: continue
                DropEntryCache.update(source.id, json.drops)
                db.dropSourceDao().update(
                    source.copy(
                        name          = json.label ?: source.name,
                        lastFetchedAt = System.currentTimeMillis(),
                        entryCount    = json.drops.size
                    )
                )
            }
        }
    }

    private fun confirmDelete(source: DropSource) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, source.name))
            .setPositiveButton(R.string.button_delete) { _, _ ->
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
        private val onToggle: (DropSource) -> Unit,
        private val onDelete: (DropSource) -> Unit
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
                binding.textSourceName.text = source.name
                binding.textSourceUrl.text = source.url
                binding.textSourceEntryCount.text = binding.root.context.getString(
                    R.string.source_entry_count, source.entryCount
                )
                binding.textSourceLastFetched.text = if (source.lastFetchedAt != null) {
                    binding.root.context.getString(
                        R.string.source_last_fetched,
                        dateFormat().format(Date(source.lastFetchedAt))
                    )
                } else {
                    binding.root.context.getString(R.string.source_never_fetched)
                }
                binding.switchSourceEnabled.isChecked = source.enabled
                binding.switchSourceEnabled.setOnCheckedChangeListener(null)
                binding.switchSourceEnabled.setOnCheckedChangeListener { _, _ -> onToggle(source) }
                binding.buttonDeleteSource.setOnClickListener { onDelete(source) }
            }
        }

        companion object {
            private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        }
    }
}
