package com.example.calculatorsafe

import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.calculatorsafe.utils.EncryptionUtils.getBitmapFromUri
import com.example.calculatorsafe.utils.EncryptionUtils.saveEncryptedImageToStorage
import com.example.calculatorsafe.PreferenceHelper.getAlbumId
import com.example.calculatorsafe.utils.EncryptionUtils
import com.example.calculatorsafe.utils.FileUtils
import com.example.calculatorsafe.utils.FileUtils.getImageFileCountFromAlbum
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File

class AlbumActivity : AppCompatActivity() {

    private val REQUEST_CODE_READ_MEDIA = 1001
    private lateinit var albumDirectoryPath: String
    private lateinit var album: MainActivity.Album
    private lateinit var adapter: EncryptedImageAdapter
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var selectionModeCallback: OnBackPressedCallback
    private lateinit var toolbar: Toolbar

    companion object {
        private val TAG = "AlbumActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album)
        val albumName = intent.getStringExtra("albumName") ?: ""
        albumDirectoryPath = intent.getStringExtra("albumDirectoryPath") ?: ""
        Log.e(TAG, "directoryPath: $albumDirectoryPath")
        album = MainActivity.Album(albumName, getImageFileCountFromAlbum(File(albumDirectoryPath)), getAlbumId(this, albumName) ?: "", albumDirectoryPath)

        val encryptedFiles = File(albumDirectoryPath).listFiles { _, name -> name.endsWith(".enc") }?.toList() ?: emptyList()
        FilePathManager.setFilePaths(encryptedFiles.map { it.absolutePath })
        permissionHelper = PermissionHelper(this)

        toolbar = findViewById<Toolbar>(R.id.album_toolbar)
        val albumRecyclerView = findViewById<RecyclerView>(R.id.album_RecyclerView)
        val albumFab = findViewById<FloatingActionButton>(R.id.album_fab)
        val gridLayoutManager = GridLayoutManager(this, 3)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = albumName
        supportActionBar?.subtitle = "${encryptedFiles.size} images" //TODO: update on change and count other files types

        // Calculate and set item width dynamically
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val spacing = 3 // Adjust for margins and padding
        val itemWidth = (screenWidth - (spacing * 4)) / 3

        albumRecyclerView.layoutManager = gridLayoutManager
        selectionModeCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                Log.e(TAG, "Back pressed")
                exitSelectionMode()
            }
        }
        onBackPressedDispatcher.addCallback(this, selectionModeCallback)

        adapter = EncryptedImageAdapter(
            encryptedFiles.toMutableList(),
            itemWidth,
            { file -> EncryptionUtils.decryptImage(file) }
        ) {
            enterSelectionMode()
        }.apply {
            onSelectionChanged = {
                updateSelectionSubtitle()
            }
        }

        albumRecyclerView.adapter = adapter

        albumFab.setOnClickListener {
            checkAndRequestPermissions()
            }

        toolbar.setNavigationOnClickListener {
            if (selectionModeCallback.isEnabled) {
                selectionModeCallback.handleOnBackPressed()
            } else {
                onBackPressedDispatcher.onBackPressed() // Default back behavior
            }
        }

    }

    // Inflate the menu (from menu_album.xml)
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_album, menu)  // R.menu.menu_album is your XML menu file
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        //TODO: add options for deleting,restoring,sorting
        return when (item.itemId) {
            android.R.id.home -> {
                finish() // Closes the activity and goes back to the previous one
                true
            }
            R.id.action_delete -> {
                // Handle delete action
                true
            }
            R.id.action_restore -> {
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkAndRequestPermissions() {
        //TODO: add support for selective permissions, test API levels and permissions
        val permissionsNeeded = mutableListOf<String>()
        //add if below a certain API level to check for different permission names
        if (!permissionHelper.hasPermission(READ_MEDIA_IMAGES)) {
            permissionsNeeded.add(READ_MEDIA_IMAGES)
        }

        if (!permissionHelper.hasPermission(READ_MEDIA_VIDEO)) {
            permissionsNeeded.add(READ_MEDIA_VIDEO)
        }

        if (permissionsNeeded.isNotEmpty()) {
            permissionHelper.requestPermissions(permissionsNeeded.toTypedArray(), REQUEST_CODE_READ_MEDIA)
        } else {
            // Permissions are already granted
            accessUserImages()
        }

    }

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mediaUri: Uri? = result.data?.data
            mediaUri?.let {
                // Handle the selected image or video URI
                handleSelectedMedia(it)
            }
        }
    }

    private fun accessUserImages() {
        // Your code to pick images or videos from the gallery
        val pickMediaIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/* video/*"
        }
        pickMediaLauncher.launch(pickMediaIntent)
    }

    private fun handleSelectedMedia(mediaUri: Uri) {
        val bitmap = getBitmapFromUri(contentResolver, mediaUri)
        val encryptedImage = EncryptionUtils.encryptImage(bitmap)

        val originalFileName = FileUtils.getFileNameFromUri(this, mediaUri) ?: "unknown"
        val mimeType = contentResolver.getType(mediaUri) ?: "unknown"

        //albumDirectoryPath will always have its parent directory and so it is safe to assert it, we need the parentDirectory Albums
        val newFilePath = saveEncryptedImageToStorage(encryptedImage, File(albumDirectoryPath).parentFile!!, album, originalFileName, mimeType)
        adapter.addFile(File(newFilePath))
        //deleteImageFromUri(mediaUri) //TODO: Delete file when i feel confident we have a working solution
    }

    private fun enterSelectionMode() {
        adapter.mode = EncryptedImageAdapter.Mode.SELECTION
        selectionModeCallback.isEnabled = true
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.close)
        toolbar.setNavigationOnClickListener {
            selectionModeCallback.handleOnBackPressed()
        }
        toolbar.title = "Selection Mode"
        updateSelectionSubtitle()
        // Show toolbar or action bar for operations like Restore/Delete
    }
    private fun updateSelectionSubtitle() {
        val selectedCount = adapter.selectedItems.size
        val totalCount = adapter.itemCount
        toolbar.subtitle = "$selectedCount selected out of $totalCount"
    }

    private fun exitSelectionMode() {
        adapter.mode = EncryptedImageAdapter.Mode.VIEWING
        adapter.selectedItems.clear()
        selectionModeCallback.isEnabled = false
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.back)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        toolbar.title = album.name
        toolbar.subtitle = "${album.photoCount} images"
        // Hide toolbar or action bar
    }

    class EncryptedImageAdapter(
        private val encryptedFiles: MutableList<File>,
        private val itemWidth: Int,
        private val decryptFunction: (File) -> Bitmap,
        private val onEnterSelectionMode: () -> Unit
        ) : RecyclerView.Adapter<EncryptedImageAdapter.PhotoViewHolder>() {

            enum class Mode {
                VIEWING,
                SELECTION
            }

        var mode = Mode.VIEWING
            set(value) {
                field = value
                Log.e(TAG, "Mode changed to $value")
                notifyDataSetChanged()
            }

        val selectedItems = mutableSetOf<Int>()
        var onSelectionChanged: (() -> Unit)? = null

        inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val cardView: CardView = view.findViewById(R.id.card_view)
            private val photoImageView: ImageView = view.findViewById(R.id.photo_image_view)
            private val overlay: View = itemView.findViewById(R.id.overlay) // A semi-transparent View

            fun bind(file: File,position: Int, isSelected: Boolean) {
                val decryptedBitmap = decryptFunction(file)
                photoImageView.setImageBitmap(decryptedBitmap)
                // Show or hide the selection overlay
                overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                itemView.setOnClickListener {
                    when (mode) {
                        Mode.VIEWING -> {
                            val intent = Intent(itemView.context, MediaViewActivity::class.java)
                            intent.putExtra("position", position)
                            itemView.context.startActivity(intent)
                        }
                        Mode.SELECTION -> {
                            toggleSelection(position)
                            notifyItemChanged(position)
                        }
                    }
                }
                itemView.setOnLongClickListener {
                    if (mode == Mode.VIEWING) {
                        toggleSelection(position)
                        onEnterSelectionMode()
                    }
                    true
                }
            }

            fun setItemWidth(width: Int) {
                // Set the width for the CardView
                val layoutParams = cardView.layoutParams
                layoutParams.width = width
                cardView.layoutParams = layoutParams

                layoutParams.height = width // Set the same height as the width to maintain a square shape
                cardView.layoutParams = layoutParams
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.grid_item_layout, parent, false)
            return PhotoViewHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            holder.setItemWidth(itemWidth)
            val encryptedFile = encryptedFiles[position]
            holder.bind(encryptedFile, position, selectedItems.contains(position))
        }

        fun toggleSelection(position: Int) {
            if (selectedItems.contains(position)) {
                selectedItems.remove(position)
            } else {
                selectedItems.add(position)
            }
            notifyItemChanged(position)
            onSelectionChanged?.invoke()
        }

        fun getSelectedItems(): List<File> {
            return selectedItems.map { encryptedFiles[it] }
        }

        fun addFile(file: File) {
            encryptedFiles.add(file)
            notifyItemInserted(encryptedFiles.size - 1)
            FilePathManager.setFilePaths(encryptedFiles.map { it.absolutePath })
        }

        override fun getItemCount(): Int = encryptedFiles.size
    }

}