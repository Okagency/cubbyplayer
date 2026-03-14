package com.splayer.video.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.splayer.video.R
import com.splayer.video.data.model.Video
import com.splayer.video.data.repository.VideoRepository
import com.splayer.video.ui.adapter.MergeItem
import com.splayer.video.ui.adapter.VideoAdapter
import com.splayer.video.ui.adapter.VideoMergeAdapter
import com.splayer.video.ui.player.PlayerActivity
import com.splayer.video.utils.CrashLogger
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.loadVideos()
        } else {
            Toast.makeText(this, "영상 목록을 보려면 저장소 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.d(TAG, "onCreate called")
            CrashLogger.logInfo(this, TAG, "MainActivity onCreate started")

            // 외부에서 비디오 파일을 열려고 하는 경우 처리
            if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
                handleExternalVideoIntent()
                return
            }

            // 직접 실행: 영상 목록 표시
            setContentView(R.layout.activity_main)
            setupViews()
            setupViewModel()
            checkPermissionAndLoad()

        } catch (e: Exception) {
            CrashLogger.logError(this, TAG, "Error in onCreate", e)
            finish()
        }
    }

    private fun handleExternalVideoIntent() {
        val videoUri = intent.data
        Log.d(TAG, "Opened with URI: $videoUri")
        CrashLogger.logInfo(this, TAG, "External video opened: $videoUri")

        val subtitleUris = arrayListOf<String>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val itemUri = clip.getItemAt(i).uri?.toString() ?: continue
                if (itemUri != videoUri.toString()) {
                    subtitleUris.add(itemUri)
                }
            }
        }
        if (subtitleUris.isNotEmpty()) {
            Log.d(TAG, "ClipData에서 자막 URI ${subtitleUris.size}개 발견")
        }

        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, videoUri.toString())
            if (subtitleUris.isNotEmpty()) {
                putStringArrayListExtra("subtitle_uris", subtitleUris)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = intent.clipData
        }
        startActivity(playerIntent)
        finish()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> onVideoClicked(video) },
            onVideoLongClick = { video -> onVideoLongClicked(video) }
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = videoAdapter
        }

        swipeRefresh.setOnRefreshListener {
            viewModel.loadVideos()
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_refresh -> { viewModel.loadVideos(); true }
                R.id.action_settings -> { showSettingsDialog(); true }
                R.id.action_split -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.split.VideoSplitActivity::class.java))
                    true
                }
                R.id.action_merge -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.merge.VideoMergeActivity::class.java))
                    true
                }
                R.id.action_replace -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.replace.VideoReplaceActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupViewModel() {
        val factory = MainViewModelFactory(VideoRepository(contentResolver))
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]

        lifecycleScope.launch {
            viewModel.videos.collect { videos ->
                videoAdapter.submitList(videos)
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                progressBar.visibility = if (isLoading && videoAdapter.itemCount == 0) View.VISIBLE else View.GONE
                swipeRefresh.isRefreshing = isLoading && videoAdapter.itemCount > 0
            }
        }
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadVideos()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun onVideoClicked(video: Video) {
        if (videoAdapter.isSelectionMode) {
            videoAdapter.toggleSelection(video.id)
            updateSelectionUI()
        } else {
            val playerIntent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            }
            startActivity(playerIntent)
        }
    }

    private fun onVideoLongClicked(video: Video) {
        if (!videoAdapter.isSelectionMode) {
            videoAdapter.enterSelectionMode(video.id)
            updateSelectionUI()
        } else {
            videoAdapter.toggleSelection(video.id)
            updateSelectionUI()
        }
    }

    private fun updateSelectionUI() {
        if (videoAdapter.isSelectionMode) {
            val count = videoAdapter.getSelectedCount()
            toolbar.title = "${count}개 선택"
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationOnClickListener {
                exitSelectionMode()
            }
            // 메뉴 변경: 합치기 버튼
            toolbar.menu.clear()
            toolbar.menu.add(0, 100, 0, "합치기").apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                isEnabled = count >= 2
            }
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    100 -> {
                        val selected = videoAdapter.getSelectedVideos()
                        if (selected.size >= 2) {
                            showMergeOrderDialog(selected)
                        } else {
                            Toast.makeText(this, "2개 이상 선택해주세요.", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    else -> false
                }
            }
        } else {
            exitSelectionMode()
        }
    }

    private fun exitSelectionMode() {
        videoAdapter.clearSelection()
        toolbar.title = getString(R.string.app_name)
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        toolbar.menu.clear()
        menuInflater.inflate(R.menu.main_menu, toolbar.menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_refresh -> { viewModel.loadVideos(); true }
                R.id.action_settings -> { showSettingsDialog(); true }
                R.id.action_split -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.split.VideoSplitActivity::class.java))
                    true
                }
                R.id.action_merge -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.merge.VideoMergeActivity::class.java))
                    true
                }
                R.id.action_replace -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.replace.VideoReplaceActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun showSortDialog() {
        val sortLabels = arrayOf("이름", "추가 날짜", "수정 날짜", "크기", "재생시간")
        val sortModes = MainViewModel.SortMode.values()
        val currentMode = viewModel.sortMode.value
        val currentAscending = viewModel.sortAscending.value

        val dialogView = layoutInflater.inflate(R.layout.dialog_sort, null)
        val sortModeContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.sortModeContainer)
        val sortOrderContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.sortOrderContainer)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog
        )
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            navigationBarColor = android.graphics.Color.parseColor("#1A1A2E")
        }
        dialog.behavior.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        // 닫기 버튼
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseSortDialog)?.setOnClickListener {
            dialog.dismiss()
        }

        // 정렬 기준 항목 추가
        val currentIndex = sortModes.indexOf(currentMode)
        sortLabels.forEachIndexed { index, label ->
            val isSelected = index == currentIndex
            val item = TextView(this).apply {
                text = if (isSelected) "$label  ✓" else label
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor(if (isSelected) "#64B5F6" else "#E0E0E0"))
                setPadding(40, 30, 40, 30)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_settings_card)?.constantState?.newDrawable()?.mutate()
                background?.alpha = 0
                setOnClickListener {
                    viewModel.setSortMode(sortModes[index])
                    dialog.dismiss()
                    showSortDialog()
                }
            }
            sortModeContainer.addView(item)
        }

        // 정렬 순서 항목 추가
        val orderLabels = arrayOf("오름차순", "내림차순")
        orderLabels.forEachIndexed { index, label ->
            val isSelected = if (index == 0) currentAscending else !currentAscending
            val item = TextView(this).apply {
                text = if (isSelected) "$label  ✓" else label
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor(if (isSelected) "#64B5F6" else "#E0E0E0"))
                setPadding(40, 30, 40, 30)
                setOnClickListener {
                    viewModel.setSortAscending(index == 0)
                    dialog.dismiss()
                    showSortDialog()
                }
            }
            sortOrderContainer.addView(item)
        }

        dialog.show()
    }

    // =============================================
    // 비디오 합치기 기능
    // =============================================

    private fun showMergeOrderDialog(videos: List<Video>) {
        val items = videos.map { video ->
            MergeItem(video.uri, video.displayName, video.duration)
        }.toMutableList()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(0, 16, 0, 16)
        }

        val adapter = VideoMergeAdapter(
            items = items,
            onMoveUp = { pos ->
                if (pos > 0) {
                    val temp = items[pos]
                    items[pos] = items[pos - 1]
                    items[pos - 1] = temp
                    recyclerView.adapter?.notifyItemMoved(pos, pos - 1)
                    recyclerView.adapter?.notifyItemChanged(pos)
                    recyclerView.adapter?.notifyItemChanged(pos - 1)
                }
            },
            onMoveDown = { pos ->
                if (pos < items.size - 1) {
                    val temp = items[pos]
                    items[pos] = items[pos + 1]
                    items[pos + 1] = temp
                    recyclerView.adapter?.notifyItemMoved(pos, pos + 1)
                    recyclerView.adapter?.notifyItemChanged(pos)
                    recyclerView.adapter?.notifyItemChanged(pos + 1)
                }
            },
            onRemove = { pos ->
                items.removeAt(pos)
                recyclerView.adapter?.notifyItemRemoved(pos)
                recyclerView.adapter?.notifyItemRangeChanged(pos, items.size - pos)
            }
        )

        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("비디오 합치기 (${items.size}개)")
            .setView(recyclerView)
            .setPositiveButton("합치기") { _, _ ->
                if (items.size >= 2) {
                    exitSelectionMode()
                    mergeVideos(items)
                } else {
                    Toast.makeText(this, "2개 이상의 비디오가 필요합니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun mergeVideos(items: List<MergeItem>) {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val firstBaseName = items.first().displayName.substringBeforeLast(".")
        val outputFileName = "merged_${firstBaseName}_${items.size}files_$timestamp.mp4"

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("비디오 합치기")
            setMessage("준비 중...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            isIndeterminate = true
            setCancelable(false)
            setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "취소") { _, _ ->
                cancelled.set(true)
                try { com.arthenica.ffmpegkit.FFmpegKit.cancel() } catch (_: Throwable) {}
            }
            show()
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            val concatListFile = File(cacheDir, "concat_list_${System.currentTimeMillis()}.txt")
            val tempOutputFile = File(cacheDir, "ffmpeg_merge_${System.currentTimeMillis()}.mp4")

            try {
                // 1단계: content:// URI를 임시파일로 복사
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.isIndeterminate = false
                    progressDialog.setMessage("파일 복사 중...")
                }

                for ((index, item) in items.withIndex()) {
                    if (cancelled.get()) break

                    val tempFile = File(cacheDir, "merge_input_${index}_${System.currentTimeMillis()}.mp4")
                    tempFiles.add(tempFile)

                    contentResolver.openInputStream(item.uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("파일을 읽을 수 없습니다: ${item.displayName}")

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressDialog.progress = ((index + 1) * 30 / items.size)
                        progressDialog.setMessage("파일 복사 중... (${index + 1}/${items.size})")
                    }
                }

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                // 2단계: concat list 파일 작성
                val concatContent = tempFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" }
                concatListFile.writeText(concatContent)

                // 3단계: FFmpeg concat 실행
                val totalDurationMs = items.sumOf { it.duration }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.setMessage("[FFmpeg] 합치는 중...\n$outputFileName")
                    progressDialog.progress = 30
                }

                com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                    if (totalDurationMs > 0) {
                        val progress = 30 + ((stats.time.toFloat() / totalDurationMs) * 70).toInt().coerceIn(0, 70)
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            if (progressDialog.isShowing) {
                                progressDialog.progress = progress
                            }
                        }
                    }
                }

                val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-y",
                        "-f", "concat",
                        "-safe", "0",
                        "-i", concatListFile.absolutePath,
                        "-c", "copy",
                        "-avoid_negative_ts", "make_zero",
                        tempOutputFile.absolutePath
                    )
                )

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)
                    || !tempOutputFile.exists()
                    || tempOutputFile.length() == 0L
                ) {
                    throw Exception("FFmpeg 합치기 실패")
                }

                // 4단계: MediaStore에 저장
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES)
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            tempOutputFile.inputStream().use { it.copyTo(os) }
                        }
                    }
                } else {
                    val outputDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                    outputDir?.mkdirs()
                    tempOutputFile.copyTo(File(outputDir, outputFileName), overwrite = true)
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "합치기 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                    viewModel.loadVideos()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "합치기 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "합치기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "Merge failed", e)
            } finally {
                tempFiles.forEach { it.delete() }
                concatListFile.delete()
                tempOutputFile.delete()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (videoAdapter.isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // 영상 목록이 있고 외부 인텐트가 아닌 경우 새로고침
        if (::viewModel.isInitialized) {
            viewModel.loadVideos()
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("splayer_settings", MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        // 컴포넌트 초기화
        val spinnerPlayerEngine = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerPlayerEngine)
        val switchSound = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSound)
        val switchStartMute = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStartMute)
        val switchStartMaxBrightness = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStartMaxBrightness)
        val spinnerSkipTime = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerSkipTime)
        val spinnerSensitivity = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerSensitivity)
        val switchBrightnessSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBrightnessSwipe)
        val switchVolumeSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchVolumeSwipe)
        val switchContinuousPlay = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchContinuousPlay)
        val spinnerBufferMode = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerBufferMode)
        val spinnerSeekMode = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerSeekMode)
        val spinnerCastMode = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerCastMode)

        // 내장 자막 - 메인화면에서는 숨김
        dialogView.findViewById<View>(R.id.layoutEmbeddedSubtitles)?.visibility = View.GONE

        // 구간 재생 관리
        val segmentManager = com.splayer.video.util.SegmentManager(this)
        val editSegmentSavePath = dialogView.findViewById<android.widget.EditText>(R.id.editSegmentSavePath)
        val editSegmentFilePath = dialogView.findViewById<android.widget.EditText>(R.id.editSegmentFilePath)

        val currentSavePath = prefs.getString("segment_save_path", "/storage/emulated/0/Movies") ?: "/storage/emulated/0/Movies"
        editSegmentSavePath.setText(currentSavePath)
        editSegmentFilePath.setText(segmentManager.getSegmentFilePath())

        editSegmentSavePath.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("segment_save_path", s.toString()).apply()
            }
        })

        // 플레이어 엔진
        val engineOptions = arrayOf("ExoPlayer", "VLC")
        val engineAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, engineOptions)
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPlayerEngine.adapter = engineAdapter
        spinnerPlayerEngine.setSelection(if (prefs.getBoolean("use_vlc_engine", false)) 1 else 0)
        spinnerPlayerEngine.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putBoolean("use_vlc_engine", position == 1).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 현재 설정값 적용
        switchSound.isChecked = prefs.getBoolean("sound_enabled", true)
        switchStartMute.isChecked = prefs.getBoolean("start_with_mute", false)
        switchStartMaxBrightness.isChecked = prefs.getBoolean("start_with_max_brightness", false)
        switchBrightnessSwipe.isChecked = prefs.getBoolean("brightness_swipe_enabled", true)
        switchVolumeSwipe.isChecked = prefs.getBoolean("volume_swipe_enabled", true)
        switchContinuousPlay.isChecked = prefs.getBoolean("continuous_play_enabled", false)

        // 스위치 리스너
        switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sound_enabled", isChecked).apply()
            switchVolumeSwipe.isChecked = isChecked
        }
        switchStartMute.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("start_with_mute", isChecked).apply()
        }
        switchStartMaxBrightness.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("start_with_max_brightness", isChecked).apply()
            switchBrightnessSwipe.isChecked = !isChecked
        }
        switchBrightnessSwipe.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("brightness_swipe_enabled", isChecked).apply()
        }
        switchVolumeSwipe.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("volume_swipe_enabled", isChecked).apply()
        }
        switchContinuousPlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("continuous_play_enabled", isChecked).apply()
        }

        // 스킵 시간
        val skipTimeOptions = arrayOf("5초", "10초", "15초", "20초", "30초", "60초")
        val skipTimeValues = arrayOf(5, 10, 15, 20, 30, 60)
        val skipTimeAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, skipTimeOptions)
        skipTimeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSkipTime.adapter = skipTimeAdapter
        val currentSkip = prefs.getInt("skip_seconds", 10)
        spinnerSkipTime.setSelection(skipTimeValues.indexOf(currentSkip).let { if (it == -1) 1 else it })
        spinnerSkipTime.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("skip_seconds", skipTimeValues[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 스와이프 민감도
        val sensitivityOptions = (1..20).map { level ->
            when {
                level <= 5 -> "$level"
                level <= 10 -> "$level"
                level <= 15 -> "$level"
                else -> "$level"
            }
        }.toTypedArray()
        val sensAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, sensitivityOptions)
        sensAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSensitivity.adapter = sensAdapter
        spinnerSensitivity.setSelection(prefs.getInt("swipe_sensitivity", 5) - 1)
        spinnerSensitivity.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("swipe_sensitivity", position + 1).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 버퍼링 모드
        val bufferModeOptions = arrayOf("안정", "빠른 시작")
        val bufferAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, bufferModeOptions)
        bufferAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBufferMode.adapter = bufferAdapter
        spinnerBufferMode.setSelection(prefs.getInt("buffer_mode", 0))
        spinnerBufferMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("buffer_mode", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 시크 모드
        val seekModeOptions = arrayOf("정확", "빠름")
        val seekAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, seekModeOptions)
        seekAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSeekMode.adapter = seekAdapter
        spinnerSeekMode.setSelection(prefs.getInt("seek_mode", 0))
        spinnerSeekMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("seek_mode", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 캐스팅 방식
        val castModeOptions = arrayOf("Chromecast", "DLNA")
        val castAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, castModeOptions)
        castAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCastMode.adapter = castAdapter
        val savedCastMode = prefs.getString("cast_mode", "chromecast") ?: "chromecast"
        spinnerCastMode.setSelection(if (savedCastMode == "dlna") 1 else 0)
        spinnerCastMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("cast_mode", if (position == 1) "dlna" else "chromecast").apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 자막 캐시
        val switchSubtitleCache = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSubtitleCache)
        val cachePathText = dialogView.findViewById<TextView>(R.id.cachePathText)
        val cacheFileCountText = dialogView.findViewById<TextView>(R.id.cacheFileCountText)
        val btnClearCache = dialogView.findViewById<android.widget.Button>(R.id.btnClearCache)

        switchSubtitleCache.isChecked = prefs.getBoolean("subtitle_cache_enabled", true)
        switchSubtitleCache.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("subtitle_cache_enabled", isChecked).apply()
        }

        // 캐시 정보
        val cacheDir = File(cacheDir, "converted_subtitles")
        cachePathText.text = cacheDir.absolutePath
        val fileCount = cacheDir.listFiles()?.size ?: 0
        cacheFileCountText.text = "${fileCount}개 파일"

        btnClearCache.setOnClickListener {
            val count = cacheDir.listFiles()?.size ?: 0
            if (count == 0) {
                Toast.makeText(this, "삭제할 캐시 파일이 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("캐시 삭제")
                .setMessage("${count}개의 변환된 자막 파일을 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    var deleted = 0
                    cacheDir.listFiles()?.forEach { if (it.delete()) deleted++ }
                    Toast.makeText(this, "${deleted}개 파일 삭제 완료", Toast.LENGTH_SHORT).show()
                    cacheFileCountText.text = "0개 파일"
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // BottomSheetDialog 생성 (재생시 설정과 동일)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog
        )
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            navigationBarColor = android.graphics.Color.parseColor("#1A1A2E")
        }
        dialog.behavior.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            peekHeight = (resources.displayMetrics.heightPixels * 0.8).toInt()
            skipCollapsed = true
        }

        // 닫기 버튼
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCloseSettings)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
