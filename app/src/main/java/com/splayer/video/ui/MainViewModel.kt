package com.splayer.video.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.splayer.video.data.model.Video
import com.splayer.video.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val videoRepository: VideoRepository
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "sort_prefs"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_SORT_ASCENDING = "sort_ascending"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortMode = MutableStateFlow(loadSortMode())
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _sortAscending = MutableStateFlow(prefs.getBoolean(KEY_SORT_ASCENDING, false))
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    init {
        Log.d(TAG, "MainViewModel initialized")
        loadVideos()
    }

    fun loadVideos() {
        viewModelScope.launch {
            Log.d(TAG, "loadVideos() started")
            _isLoading.value = true
            try {
                val videoList = videoRepository.getAllVideos()
                Log.d(TAG, "Repository returned ${videoList.size} videos")

                if (videoList.isEmpty()) {
                    Log.w(TAG, "⚠️ NO VIDEOS FOUND - Repository returned empty list")
                    Log.w(TAG, "Possible reasons:")
                    Log.w(TAG, "  1. No video files in device storage")
                    Log.w(TAG, "  2. Permission not granted")
                    Log.w(TAG, "  3. MediaStore query failed")
                }

                _videos.value = sortVideos(videoList, _sortMode.value, _sortAscending.value)
                Log.d(TAG, "Videos sorted and emitted: ${_videos.value.size}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ ERROR loading videos", e)
                e.printStackTrace()
                _videos.value = emptyList()
            } finally {
                _isLoading.value = false
                Log.d(TAG, "loadVideos() completed")
            }
        }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
        _videos.value = sortVideos(_videos.value, mode, _sortAscending.value)
    }

    fun setSortAscending(ascending: Boolean) {
        _sortAscending.value = ascending
        prefs.edit().putBoolean(KEY_SORT_ASCENDING, ascending).apply()
        _videos.value = sortVideos(_videos.value, _sortMode.value, ascending)
    }

    private fun loadSortMode(): SortMode {
        val name = prefs.getString(KEY_SORT_MODE, SortMode.DATE_MODIFIED.name)
        return try {
            SortMode.valueOf(name!!)
        } catch (_: Exception) {
            SortMode.DATE_MODIFIED
        }
    }

    private fun sortVideos(videos: List<Video>, mode: SortMode, ascending: Boolean = false): List<Video> {
        val sorted = when (mode) {
            SortMode.NAME -> videos.sortedBy { it.displayName }
            SortMode.DATE_ADDED -> videos.sortedBy { it.dateAdded }
            SortMode.DATE_MODIFIED -> videos.sortedBy { it.dateModified }
            SortMode.SIZE -> videos.sortedBy { it.size }
            SortMode.DURATION -> videos.sortedBy { it.duration }
        }
        return if (ascending) sorted else sorted.reversed()
    }

    enum class SortMode {
        NAME, DATE_ADDED, DATE_MODIFIED, SIZE, DURATION
    }
}

class MainViewModelFactory(
    private val application: Application,
    private val videoRepository: VideoRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application, videoRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
