package com.splayer.video.ui.player

import android.content.Context
import android.util.AttributeSet
import androidx.media3.ui.DefaultTimeBar

/**
 * ExoPlayer의 PlayerControlView 내부 업데이트를 차단하고
 * 구간 재생 모드에서 segment-local 값만 표시하기 위한 커스텀 TimeBar
 */
class SegmentTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DefaultTimeBar(context, attrs, defStyleAttr) {

    /** true이면 setPosition/setDuration 호출을 무시 (ExoPlayer 내부 업데이트 차단) */
    var blockExternalUpdates = false

    override fun setPosition(position: Long) {
        if (blockExternalUpdates) return
        super.setPosition(position)
    }

    override fun setDuration(duration: Long) {
        if (blockExternalUpdates) return
        super.setDuration(duration)
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (blockExternalUpdates) return
        super.setBufferedPosition(bufferedPosition)
    }

    /** blockExternalUpdates와 무관하게 강제로 position 설정 */
    fun forceSetPosition(position: Long) {
        super.setPosition(position)
    }

    /** blockExternalUpdates와 무관하게 강제로 duration 설정 */
    fun forceSetDuration(duration: Long) {
        super.setDuration(duration)
    }

    /** blockExternalUpdates와 무관하게 강제로 bufferedPosition 설정 */
    fun forceSetBufferedPosition(bufferedPosition: Long) {
        super.setBufferedPosition(bufferedPosition)
    }
}
