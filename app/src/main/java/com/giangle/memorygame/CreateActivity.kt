package com.giangle.memorygame

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.GridLayoutManager
import com.giangle.memorygame.databinding.ActivityCreateBinding
import com.giangle.memorygame.models.BoardSize
import com.giangle.memorygame.utils.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.ByteArrayOutputStream

class CreateActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CreateActivity"
        private const val READ_EXTERNAL_PHOTOS_CODE = 3
        private const val PICK_PHOTO_CODE = 2
        private const val READ_PHOTO_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE
        private const val MIN_GAME_NAME_LENGTH = 3
        private const val MAN_GAME_NAME_LENGTH = 14
    }

    private lateinit var adapter: ImagePickerAdapter
    private lateinit var binding: ActivityCreateBinding
    private lateinit var boardSize: BoardSize
    private var numImagesRequired = -1
    private val chosenImageUris = mutableListOf<Uri>()
    private val storage = Firebase.storage
    private val db = Firebase.firestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_create)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        boardSize = intent.getSerializableExtra(EXTRA_BOARD_SIZE) as BoardSize
        numImagesRequired = boardSize.getNumPairs()
        supportActionBar?.title = "Chose pics (0 / $numImagesRequired)"

        binding.btnSave.setOnClickListener {
            saveDataToFirebase()
        }
        binding.edtGameName.filters = arrayOf(InputFilter.LengthFilter(MAN_GAME_NAME_LENGTH))
        binding.edtGameName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                binding.btnSave.isEnabled = shouldEnableButton()
            }
        })

        adapter = ImagePickerAdapter(
            this,
            chosenImageUris,
            boardSize,
            object : ImagePickerAdapter.ImageClickListener {
                override fun onPlaceHolderClicked() {
                    if (isPermissionGranted(this@CreateActivity, READ_PHOTO_PERMISSION)) {
                        launchIntentForPhotos()
                    } else {
                        requestPermission(
                            this@CreateActivity, READ_PHOTO_PERMISSION,
                            READ_EXTERNAL_PHOTOS_CODE
                        )
                    }
                }
            })
        binding.rcvImagePicker.adapter = adapter
        binding.rcvImagePicker.setHasFixedSize(true)
        binding.rcvImagePicker.layoutManager = GridLayoutManager(this, boardSize.getWidth())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == READ_EXTERNAL_PHOTOS_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchIntentForPhotos()
            } else {
                Toast.makeText(
                    this,
                    "In order to create a custom game, you need to provide access to your photos",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun launchIntentForPhotos() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(intent, "Choose pics"), PICK_PHOTO_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHOTO_CODE || resultCode != RESULT_OK || data == null) {
            Log.w(
                TAG,
                "Did not get data back from the launched activity, user likely canceled flow"
            )
            return
        }
        val selectedUri = data.data
        val clipData = data.clipData
        if (clipData != null) {
            Log.i(TAG, "clipData numImages ${clipData.itemCount}: $clipData")
            for (i in 0 until clipData.itemCount) {
                val clipItem = clipData.getItemAt(i)
                if (chosenImageUris.size < numImagesRequired) {
                    chosenImageUris.add(clipItem.uri)
                }
            }
        } else if (selectedUri != null) {
            Log.i(TAG, "data: $selectedUri")
            chosenImageUris.add(selectedUri)
        }
        adapter.notifyDataSetChanged()
        supportActionBar?.title = "Choose pics (${chosenImageUris.size}/$numImagesRequired)"
        binding.btnSave.isEnabled = shouldEnableButton()
    }

    private fun shouldEnableButton(): Boolean {
        //Check if we should enable the save button or not
        if (chosenImageUris.size != numImagesRequired)
            return false
        if (binding.edtGameName.text.isBlank() || binding.edtGameName.text.length < MIN_GAME_NAME_LENGTH)
            return false
        return true
    }

    private fun saveDataToFirebase() {
        binding.btnSave.isEnabled = false
        val customGameName = binding.edtGameName.text.toString()
        //check that we're not over writing someone else's data
        db.collection("games").document(customGameName).get().addOnSuccessListener {
            if (it != null && it.data != null) {
                AlertDialog.Builder(this)
                    .setTitle("Name taken")
                    .setMessage("A game already exists with the name '$customGameName'. Please choose another")
                    .setPositiveButton("OK",null)
                    .show()
                binding.btnSave.isEnabled = true
            }
            else{
                handleImageUploading(customGameName)
            }
        }.addOnFailureListener{
            Log.e(TAG,"Encountered error while saving memory game",it)
            Toast.makeText(this,"Encountered error while saving memory game",Toast.LENGTH_SHORT).show()
            binding.btnSave.isEnabled = true
        }
    }

    private fun handleImageUploading(gameName: String) {
        binding.pbUploading.visibility = View.VISIBLE
        var didEncounterError = false
        val uploadedImageUrls = mutableListOf<String>()
        for ((index, photoUri) in chosenImageUris.withIndex()) {
            val imageByteArray = getImageByteArray(photoUri)
            val filePath = "images/$gameName/${System.currentTimeMillis()}-${index}.jpg"
            val photoReference = storage.reference.child(filePath)
            photoReference.putBytes(imageByteArray)
                .continueWithTask { photoUploadTask ->
                    Log.i(TAG, "Upload bytes: ${photoUploadTask.result?.bytesTransferred}")
                    photoReference.downloadUrl
                }.addOnCompleteListener {
                    if (!it.isSuccessful) {
                        Log.e(TAG, "Exception with Firebase storage", it.exception)
                        Toast.makeText(this, "Failed to upload image", Toast.LENGTH_SHORT).show()
                        didEncounterError = true
                        return@addOnCompleteListener
                    }
                    if (didEncounterError){
                        binding.pbUploading.visibility = View.GONE
                        return@addOnCompleteListener
                    }
                    val downloadUrl = it.result.toString()
                    uploadedImageUrls.add(downloadUrl)
                    binding.pbUploading.progress = uploadedImageUrls.size*100/chosenImageUris.size
                    Log.i(
                        TAG,
                        "Finished uploading $photoUri, num uploaded ${uploadedImageUrls.size}"
                    )
                    if (uploadedImageUrls.size == chosenImageUris.size) {
                        handleAllImagesUploaded(gameName, uploadedImageUrls)
                    }
                }
        }
    }

    private fun handleAllImagesUploaded(gameName: String, imageUrls: MutableList<String>) {
        db.collection("games").document(gameName)
            .set(mapOf("images" to imageUrls))
            .addOnCompleteListener {
                binding.pbUploading.visibility = View.GONE
                if (!it.isSuccessful) {
                    Log.e(TAG, "Exception with game creation", it.exception)
                    Toast.makeText(this, "Failed game creation", Toast.LENGTH_SHORT).show()
                    return@addOnCompleteListener
                }
                Log.i(TAG, "Successfully created game $gameName")
                AlertDialog.Builder(this)
                    .setTitle("Upload complete! Let's play your game '$gameName'")
                    .setPositiveButton("OK") { _, _ ->
                        val resultData = Intent()
                        resultData.putExtra(EXTRA_GAME_NAME, gameName)
                        setResult(Activity.RESULT_OK, resultData)
                        finish()
                    }.show()
            }

    }

    private fun getImageByteArray(photoUri: Uri): ByteArray {
        val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, photoUri)
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(contentResolver, photoUri)
        }
        Log.i(TAG, "Original width ${originalBitmap.width} and height ${originalBitmap.height}")
        val scaledBitmap = BitmapScaler.scaleToFitHeight(originalBitmap, 250)
        Log.i(TAG, "Scaled width ${scaledBitmap.width} and height ${scaledBitmap.height}")
        val byteOutputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteOutputStream)
        return byteOutputStream.toByteArray()
    }
}