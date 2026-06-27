package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.RetainedKey
import com.github.mofosyne.tagdrop.databinding.ActivityRetainedKeysBinding
import com.github.mofosyne.tagdrop.databinding.ItemRetainedKeyBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists all AES-256-GCM keys retained from scanning key codes (SPEC §9).
 * Users can delete individual keys or clear them all.
 */
class RetainedKeysActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRetainedKeysBinding
    private val adapter = KeyAdapter(
        onDelete = { key -> confirmDeleteKey(key) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRetainedKeysBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_retained_keys)

        binding.recyclerKeys.layoutManager = LinearLayoutManager(this)
        binding.recyclerKeys.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadKeys()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_retained_keys, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_delete_all_keys -> { confirmDeleteAll(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadKeys() {
        lifecycleScope.launch {
            val keys = AppDatabase.get(this@RetainedKeysActivity).keyDao().getAll()
            adapter.submitList(keys)
            binding.textEmpty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerKeys.visibility = if (keys.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun confirmDeleteKey(key: RetainedKey) {
        val label = key.hint ?: (key.keyHex.take(16) + "…")
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, label))
            .setPositiveButton(R.string.button_delete) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@RetainedKeysActivity).keyDao().delete(key)
                    loadKeys()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        val count = adapter.itemCount
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_keys_confirm_title)
            .setMessage(getString(R.string.delete_all_keys_confirm_message, count))
            .setPositiveButton(R.string.action_delete_all_keys) { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@RetainedKeysActivity).keyDao().deleteAll()
                    loadKeys()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Adapter ----

    private class KeyAdapter(
        private val onDelete: (RetainedKey) -> Unit
    ) : RecyclerView.Adapter<KeyAdapter.ViewHolder>() {

        private var items: List<RetainedKey> = emptyList()

        fun submitList(list: List<RetainedKey>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRetainedKeyBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(private val binding: ItemRetainedKeyBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(key: RetainedKey) {
                binding.textKeyHint.text = key.hint
                    ?: binding.root.context.getString(R.string.retained_keys_hint)
                binding.textKeyDate.text = dateFormat().format(Date(key.discoveredAt))
                binding.textKeyPrefix.text = binding.root.context.getString(
                    R.string.retained_key_prefix_format,
                    key.keyHex.take(16).chunked(4).joinToString(" ")
                )
                binding.buttonDeleteKey.setOnClickListener { onDelete(key) }
            }
        }

        companion object {
            private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        }
    }
}
