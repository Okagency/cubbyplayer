package com.splayer.video.plugin.converter

import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ConverterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvFileName: TextView
    private lateinit var tvFileFormat: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvFileDuration: TextView
    private lateinit var layoutFileInfo: View
    private lateinit var btnSelectFile: MaterialButton
    private lateinit var chipGroupFormat: ChipGroup
    private lateinit var layoutProgress: View
    private lateinit var tvProgressStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var btnConvert: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var sourceUri: Uri? = null
    private var sourcePath: String? = null
    private var sourceDurationMs: Long = 0L
    private var convertJob: Job? = null
    private val cancelled = AtomicBoolean(false)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setSourceFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_converter)
        initViews()

        // Intent에서 URI 수신 (data 또는 extra)
        val videoUri = intent.data ?: intent.getStringExtra(EXTRA_VIDEO_URI)?.let { Uri.parse(it) }
        if (videoUri != null) {
            setSourceFile(videoUri)
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        tvFileName = findViewById(R.id.tvFileName)
        tvFileFormat = findViewById(R.id.tvFileFormat)
        tvFileSize = findViewById(R.id.tvFileSize)
        tvFileDuration = findViewById(R.id.tvFileDuration)
        layoutFileInfo = findViewById(R.id.layoutFileInfo)
        btnSelectFile = findViewById(R.id.btnSelectFile)
        chipGroupFormat = findViewById(R.id.chipGroupFormat)
        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        progressBar = findViewById(R.id.progressBar)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        btnConvert = findViewById(R.id.btnConvert)
        btnCancel = findViewById(R.id.btnCancel)

        toolbar.setNavigationOnClickListener { finish() }

        btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("video/*"))
        }

        btnConvert.setOnClickListener {
            if (sourceUri == null) {
                Toast.makeText(this, getString(R.string.msg_no_file), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startConversion()
        }

        btnCancel.setOnClickListener {
            cancelled.set(true)
            try { FFmpegKit.cancel() } catch (_: Throwable) {}
        }
    }

    private fun setSourceFile(uri: Uri) {
        sourceUri = uri

        // 파일명/크기 조회
        var displayName = "알 수 없음"
        var size = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }

        tvFileName.text = displayName
        tvFileFormat.text = displayName.substringAfterLast(".", "?").uppercase()
        tvFileSize.text = formatFileSize(size)
        layoutFileInfo.visibility = View.VISIBLE

        // MediaStore에서 duration 조회
        sourceDurationMs = getVideoDuration(uri)
        tvFileDuration.text = if (sourceDurationMs > 0) formatDuration(sourceDurationMs) else "?"
    }

    private fun getVideoDuration(uri: Uri): Long {
        // content:// URI에서 duration 조회 시도
        try {
            val projection = arrayOf(MediaStore.Video.Media.DURATION)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                    if (idx >= 0) return cursor.getLong(idx)
                }
            }
        } catch (_: Exception) {}

        // MediaMetadataRetriever fallback
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            return duration?.toLongOrNull() ?: 0L
        } catch (_: Exception) {}

        return 0L
    }

    private fun getSelectedOutputFormat(): String {
        return when (chipGroupFormat.checkedChipId) {
            R.id.chipMp4 -> "mp4"
            R.id.chipMkv -> "mkv"
            R.id.chipAvi -> "avi"
            R.id.chipMov -> "mov"
            else -> "mp4"
        }
    }

    private fun getFFmpegArgs(inputPath: String, outputPath: String, format: String): Array<String> {
        return when (format) {
            "mp4" -> arrayOf(
                "-y", "-i", inputPath,
                "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                outputPath
            )
            "mkv" -> arrayOf(
                "-y", "-i", inputPath,
                "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                outputPath
            )
            "avi" -> arrayOf(
                "-y", "-i", inputPath,
                "-c:v", "mpeg4", "-q:v", "3",
                "-c:a", "mp3", "-b:a", "128k",
                outputPath
            )
            "mov" -> arrayOf(
                "-y", "-i", inputPath,
                "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                outputPath
            )
            else -> arrayOf("-y", "-i", inputPath, "-c:v", "libx264", "-c:a", "aac", outputPath)
        }
    }

    private fun startConversion() {
        val uri = sourceUri ?: return
        val format = getSelectedOutputFormat()

        cancelled.set(false)
        setConvertingUI(true)

        convertJob = lifecycleScope.launch(Dispatchers.IO) {
            var tempInputFile: File? = null
            var tempOutputFile: File? = null

            try {
                // 1. content:// URI를 임시파일로 복사
                withContext(Dispatchers.Main) {
                    tvProgressStatus.text = "파일 준비 중..."
                    progressBar.isIndeterminate = true
                }

                val inputName = tvFileName.text.toString()
                val inputExt = inputName.substringAfterLast(".", "tmp")
                tempInputFile = File(cacheDir, "convert_input_${System.currentTimeMillis()}.$inputExt")

                contentResolver.openInputStream(uri)?.use { input ->
                    tempInputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("파일을 읽을 수 없습니다")

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                // 2. FFmpeg 변환 실행
                val baseName = inputName.substringBeforeLast(".")
                val outputFileName = "${baseName}_converted.${format}"
                tempOutputFile = File(cacheDir, "convert_output_${System.currentTimeMillis()}.$format")

                withContext(Dispatchers.Main) {
                    tvProgressStatus.text = "변환 중..."
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    tvProgressPercent.text = "0%"
                }

                FFmpegKitConfig.enableStatisticsCallback { stats ->
                    if (sourceDurationMs > 0) {
                        val progress = ((stats.time.toFloat() / sourceDurationMs) * 100).toInt().coerceIn(0, 100)
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = progress
                            tvProgressPercent.text = "${progress}%"
                        }
                    }
                }

                val args = getFFmpegArgs(tempInputFile.absolutePath, tempOutputFile.absolutePath, format)
                val session = FFmpegKit.executeWithArguments(args)

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                if (!ReturnCode.isSuccess(session.returnCode)
                    || !tempOutputFile.exists()
                    || tempOutputFile.length() == 0L
                ) {
                    throw Exception("FFmpeg 변환 실패: ${session.logsAsString?.takeLast(200)}")
                }

                // 3. MediaStore에 저장
                withContext(Dispatchers.Main) {
                    tvProgressStatus.text = "저장 중..."
                    progressBar.progress = 100
                    tvProgressPercent.text = "100%"
                }

                val mimeType = when (format) {
                    "mp4" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    "avi" -> "video/x-msvideo"
                    "mov" -> "video/quicktime"
                    else -> "video/mp4"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    }
                    val insertUri = contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                    )
                    if (insertUri != null) {
                        contentResolver.openOutputStream(insertUri)?.use { os ->
                            tempOutputFile.inputStream().use { it.copyTo(os) }
                        }
                    }
                } else {
                    val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    outputDir?.mkdirs()
                    tempOutputFile.copyTo(File(outputDir, outputFileName), overwrite = true)
                }

                withContext(Dispatchers.Main) {
                    tvProgressStatus.text = "변환 완료: $outputFileName"
                    Toast.makeText(this@ConverterActivity, "변환 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                    setConvertingUI(false)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    tvProgressStatus.text = "변환 취소됨"
                    Toast.makeText(this@ConverterActivity, "변환이 취소되었습니다", Toast.LENGTH_SHORT).show()
                    setConvertingUI(false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvProgressStatus.text = "변환 실패: ${e.message}"
                    Toast.makeText(this@ConverterActivity, "변환 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    setConvertingUI(false)
                }
            } finally {
                tempInputFile?.delete()
                tempOutputFile?.delete()
            }
        }
    }

    private fun setConvertingUI(converting: Boolean) {
        if (converting) {
            layoutProgress.visibility = View.VISIBLE
            btnConvert.visibility = View.GONE
            btnCancel.visibility = View.VISIBLE
            btnSelectFile.isEnabled = false
            chipGroupFormat.isEnabled = false
            for (i in 0 until chipGroupFormat.childCount) {
                chipGroupFormat.getChildAt(i).isEnabled = false
            }
        } else {
            btnConvert.visibility = View.VISIBLE
            btnCancel.visibility = View.GONE
            btnSelectFile.isEnabled = true
            chipGroupFormat.isEnabled = true
            for (i in 0 until chipGroupFormat.childCount) {
                chipGroupFormat.getChildAt(i).isEnabled = true
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        convertJob?.cancel()
        try { FFmpegKit.cancel() } catch (_: Throwable) {}
    }
}
