package com.extremis.browser

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.extremis.browser.adapters.FileBrowserAdapter
import com.extremis.browser.databinding.ActivityFileBrowserBinding
import com.extremis.browser.models.FileEntry
import com.extremis.browser.utils.BrowserPreferences
import com.extremis.browser.utils.FileUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Storage browser, ported from KoiBrowser's `activities/FileBrowserActivity` +
 * `activities/StorageListActivity` + `fragment/FileListFragment`.
 *
 * KoiBrowser split this over three screens: a storage picker, then a fragment-based browser, then
 * a separate folder chooser. One activity with a path bar covers all three.
 *
 * Launch with [EXTRA_PICK_FOLDER] to use it as the download-location chooser; the chosen absolute
 * path comes back in [EXTRA_SELECTED_PATH].
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var adapter: FileBrowserAdapter
    private lateinit var preferences: BrowserPreferences

    private var currentDirectory: File? = null
    private var pickingFolder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = BrowserPreferences.get(this)
        pickingFolder = intent?.getBooleanExtra(EXTRA_PICK_FOLDER, false) == true

        binding.toolbar.title = getString(
            if (pickingFolder) R.string.choose_folder else R.string.file_browser
        )
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.selectFolderButton.visibility = if (pickingFolder) View.VISIBLE else View.GONE
        binding.selectFolderButton.setOnClickListener { returnCurrentFolder() }

        adapter = FileBrowserAdapter(
            onOpen = { entry -> openEntry(entry) },
            onMenu = { entry, anchor -> showEntryMenu(entry, anchor) }
        )
        binding.filesRecycler.layoutManager = LinearLayoutManager(this)
        binding.filesRecycler.adapter = adapter

        binding.pathBar.setOnClickListener { navigateUp() }

        // Back should walk up the tree before leaving the screen. Using the dispatcher rather than
        // overriding onBackPressed() keeps this working under the predictive-back gesture on 33+.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDirectory != null) {
                    navigateUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Start where the user last chose, else at the download folder.
        val start = when {
            savedInstanceState != null -> savedInstanceState.getString(STATE_PATH)?.let(::File)
            else -> null
        }
        val initial = start?.takeIf { it.exists() }
            ?: FileUtils.defaultDownloadDirectory(this, preferences)
        showDirectory(initial)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDirectory?.let { outState.putString(STATE_PATH, it.absolutePath) }
    }

    private fun showDirectory(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            showStorageRoots()
            return
        }
        currentDirectory = directory
        binding.pathText.text = directory.absolutePath

        val children = directory.listFiles()
            ?.map { FileEntry.from(it) }
            ?.filter { !it.name.startsWith(".") }
            .orEmpty()

        adapter.submit(children)
        binding.emptyView.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
    }

    /** The root picker KoiBrowser's StorageListActivity presented. */
    private fun showStorageRoots() {
        val roots = FileUtils.storageRoots(this)
        if (roots.isEmpty()) {
            adapter.submit(emptyList())
            binding.emptyView.visibility = View.VISIBLE
            binding.pathText.text = getString(R.string.storage)
            return
        }
        currentDirectory = null
        binding.pathText.text = getString(R.string.storage)
        adapter.submit(
            roots.map { (_, file) ->
                FileEntry(
                    name = file.name.ifEmpty { file.absolutePath },
                    path = file.absolutePath,
                    isDirectory = true,
                    sizeBytes = 0L,
                    lastModified = file.lastModified(),
                    itemCount = file.list()?.size ?: 0
                )
            }
        )
        binding.emptyView.visibility = View.GONE
    }

    private fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            showDirectory(entry.file)
            return
        }
        try {
            FileUtils.openFile(this, entry.file)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_app_to_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateUp() {
        val directory = currentDirectory ?: return showStorageRoots()
        val parent = directory.parentFile
        if (parent == null || parent.absolutePath == directory.absolutePath) {
            showStorageRoots()
        } else {
            showDirectory(parent)
        }
    }

    private fun showEntryMenu(entry: FileEntry, anchor: View) {
        val actions = if (entry.isDirectory) {
            arrayOf(
                getString(R.string.open),
                getString(R.string.share),
                getString(R.string.delete)
            )
        } else {
            arrayOf(
                getString(R.string.open),
                getString(R.string.share),
                getString(R.string.delete)
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openEntry(entry)
                    1 -> shareEntry(entry)
                    2 -> confirmDelete(entry)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareEntry(entry: FileEntry) {
        if (entry.isDirectory) return
        try {
            FileUtils.shareFile(this, entry.file)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_app_to_open_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(entry: FileEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(entry.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = FileUtils.deleteRecursively(entry.file)
                Toast.makeText(
                    this,
                    if (deleted) R.string.delete else R.string.file_not_found,
                    Toast.LENGTH_SHORT
                ).show()
                currentDirectory?.let { showDirectory(it) } ?: showStorageRoots()
            }
            .show()
    }

    private fun returnCurrentFolder() {
        val directory = currentDirectory
        if (directory == null) {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_SELECTED_PATH, directory.absolutePath)
        )
        finish()
    }

    companion object {
        const val EXTRA_PICK_FOLDER = "pick_folder"
        const val EXTRA_SELECTED_PATH = "selected_path"
        private const val STATE_PATH = "current_path"

        fun pickFolderIntent(context: android.content.Context): Intent =
            Intent(context, FileBrowserActivity::class.java)
                .putExtra(EXTRA_PICK_FOLDER, true)
    }
}
