package com.giangle.memorygame

import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.GridLayoutManager
import com.giangle.memorygame.databinding.ActivityMainBinding
import com.giangle.memorygame.models.BoardSize
import com.giangle.memorygame.models.MemoryGame
import com.giangle.memorygame.models.UserImageList
import com.giangle.memorygame.utils.EXTRA_BOARD_SIZE
import com.giangle.memorygame.utils.EXTRA_GAME_NAME
import com.github.jinatonic.confetti.CommonConfetti
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.squareup.picasso.Picasso

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: MemoryBoardAdapter
    private lateinit var memoryGame: MemoryGame
    private lateinit var binding: ActivityMainBinding
    private var boardSize = BoardSize.EASY
    private val db = Firebase.firestore
    private var gameName: String? = null
    private var customGameImages: List<String>? = null

    companion object {
        private const val CREATE_REQUEST_CODE = 1
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        setupBoard()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.mi_refresh -> {
                if (memoryGame.getNumMoves() > 0 && !memoryGame.haveWonGame())
                    showAlertDialog("Quit your current game?", null, View.OnClickListener {
                        setupBoard()
                    })
                else
                    setupBoard()
                return true
            }

            R.id.mi_new_size -> {
                showNewSizeDialog()
                return true
            }

            R.id.mi_custom -> {
                showCreationDialog()
                return true
            }

            R.id.mi_download ->{
                showDownloadDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showDownloadDialog() {
        val boardDownloadView = LayoutInflater.from(this).inflate(R.layout.dialog_download_board,null)
        showAlertDialog("Fetch memory game",boardDownloadView) {
            //Grab the text of the game name that the user wants to download
            val edtDownloadGame = boardDownloadView.findViewById<EditText>(R.id.edt_download_game)
            val gameToDownload = edtDownloadGame.text.toString()
            downloadGame(gameToDownload)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateGameWithFlip(position: Int) {
        if (memoryGame.haveWonGame()) {
            Snackbar.make(binding.clRoot, "You already won!", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (memoryGame.isCardFaceUp(position)) {
            Snackbar.make(binding.clRoot, "Invalid move!", Snackbar.LENGTH_SHORT).show()

            return
        }
        if (memoryGame.flipCard(position)) {
            val color = ArgbEvaluator().evaluate(
                memoryGame.numPairsFound.toFloat() / boardSize.getNumPairs(),
                ContextCompat.getColor(this, R.color.color_progress_none),
                ContextCompat.getColor(this, R.color.color_progress_full)
            ) as Int
            binding.tvNumPairs.setTextColor(color)
            binding.tvNumPairs.text =
                "Pairs : ${memoryGame.numPairsFound} / ${boardSize.getNumPairs()}"
            if (memoryGame.haveWonGame()){
                Snackbar.make(binding.clRoot, "You won! Congratulations.", Snackbar.LENGTH_LONG)
                    .show()
                CommonConfetti.rainingConfetti(binding.clRoot, intArrayOf(Color.YELLOW,Color.GREEN,Color.MAGENTA)).oneShot()
            }
        }
        binding.tvNumMoves.text = "Move : ${memoryGame.getNumMoves()}"
        adapter.notifyDataSetChanged()
    }

    @SuppressLint("SetTextI18n")
    private fun setupBoard() {
        supportActionBar?.title = gameName ?: getString(R.string.app_name)
        when (boardSize) {
            BoardSize.EASY -> {
                binding.tvNumMoves.text = "Easy : 4 x 2"
                binding.tvNumPairs.text = "Pairs : 0 / 4"
            }
            BoardSize.MEDIUM -> {
                binding.tvNumMoves.text = "Easy : 6 x 3"
                binding.tvNumPairs.text = "Pairs : 0 / 9"
            }
            BoardSize.HARD -> {
                binding.tvNumMoves.text = "Easy : 6 x 4"
                binding.tvNumPairs.text = "Pairs : 0 / 12"
            }
        }
        binding.tvNumPairs.setTextColor(ContextCompat.getColor(this, R.color.color_progress_none))
        memoryGame = MemoryGame(boardSize,customGameImages)
        adapter = MemoryBoardAdapter(this, boardSize, memoryGame.cards, object :
            MemoryBoardAdapter.CardClickListener {
            override fun onCardClicked(position: Int) {
                updateGameWithFlip(position)
            }
        })
        binding.rvBoard.adapter = adapter
        binding.rvBoard.layoutManager = GridLayoutManager(this, boardSize.getWidth())
        binding.rvBoard.setHasFixedSize(true)
    }

    private fun showNewSizeDialog() {
        val boardSizeView = LayoutInflater.from(this).inflate(R.layout.dialog_board_size, null)
        val radioGroupSize = boardSizeView.findViewById<RadioGroup>(R.id.radio_group)
        when (boardSize) {
            BoardSize.EASY -> radioGroupSize.check(R.id.rb_easy)
            BoardSize.MEDIUM -> radioGroupSize.check(R.id.rb_medium)
            BoardSize.HARD -> radioGroupSize.check(R.id.rb_hard)
        }
        showAlertDialog("Choose new size", boardSizeView) {
            boardSize = when (radioGroupSize.checkedRadioButtonId) {
                R.id.rb_easy -> BoardSize.EASY
                R.id.rb_medium -> BoardSize.MEDIUM
                else -> BoardSize.HARD
            }
            gameName = null
            customGameImages = null
            setupBoard()
        }
    }


    private fun showAlertDialog(
        title: String,
        view: View?,
        positiveClickListener: View.OnClickListener
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Ok") { _, _ ->
                positiveClickListener.onClick(view)
            }.show()
    }

    private fun showCreationDialog() {
        val boardSizeView = LayoutInflater.from(this).inflate(R.layout.dialog_board_size, null)
        val radioGroupSize = boardSizeView.findViewById<RadioGroup>(R.id.radio_group)
        showAlertDialog("Create your own memory board", boardSizeView) {
            val desiredBoardSize = when (radioGroupSize.checkedRadioButtonId) {
                R.id.rb_easy -> BoardSize.EASY
                R.id.rb_medium -> BoardSize.MEDIUM
                else -> BoardSize.HARD
            }
            // Navigate to a new activity
            val intent = Intent(this, CreateActivity::class.java)
            intent.putExtra(EXTRA_BOARD_SIZE, desiredBoardSize)
            startActivityForResult(intent, CREATE_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CREATE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val customGameName = data?.getStringExtra(EXTRA_GAME_NAME)
            if (customGameName == null) {
                Log.e(TAG, "Got null custom game from CreateActivity")
                return
            }
            downloadGame(customGameName)
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun downloadGame(customGameName: String) {
        db.collection("games").document(customGameName).get().addOnSuccessListener {
            val userImageList = it.toObject(UserImageList::class.java)
            if(userImageList?.images == null){
                Log.e(TAG,"Invalid custom game data from Firebase")
                Snackbar.make(binding.clRoot,"Sorry, we couldn't find any such game, '$gameName'",Snackbar.LENGTH_LONG).show()
                return@addOnSuccessListener
            }
            val numCards = userImageList.images.size*2
            boardSize = BoardSize.getByValue(numCards)
            customGameImages = userImageList.images
            for(imageUrl in userImageList.images){
                Picasso.get().load(imageUrl).fetch()
            }
            Snackbar.make(binding.clRoot,"You're now playing '$customGameName'",Snackbar.LENGTH_SHORT).show()
            gameName = customGameName
            setupBoard()
        }.addOnFailureListener{
            Log.e(TAG,"Exception when retrieving game",it)
        }
    }
}