package com.github.mofosyne.tagdrop

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.databinding.ActivityPapersBinding
import com.github.mofosyne.tagdrop.ui.PaperListAdapter
import kotlinx.coroutines.launch

class PapersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPapersBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPapersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.title_papers)

        val adapter = PaperListAdapter(
            onDelete = { paper ->
                lifecycleScope.launch {
                    AppDatabase.get(this@PapersActivity).paperDao().delete(paper)
                }
            }
        )

        binding.recyclerPapers.layoutManager = LinearLayoutManager(this)
        binding.recyclerPapers.adapter = adapter

        AppDatabase.get(this).paperDao().getAll().observe(this) { papers ->
            adapter.submitList(papers)
            binding.textEmpty.visibility = if (papers.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
