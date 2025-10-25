package app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rehabcore.domain.*
import rehabcore.domain.model.FrameHud
import rehabcore.domain.model.PoseLandmarks
import rehabcore.domain.model.RepLog

/**
 * 將運動邏輯與 UI 分離的核心 ViewModel。
 * 負責管理 Detector 狀態與 HUD 更新資料。
 */
class DetectionViewModel : ViewModel() {

    private var detector: Detector? = null
    private val _hud = MutableStateFlow(FrameHud(null, "INIT", 0f, 0, 0))
    val hud: StateFlow<FrameHud> = _hud
    fun peekRecentRepLogs(limit: Int = 10): List<RepLog> =
        detector?.peekRecentRepLogs(limit) ?: emptyList()

    fun drainRepLogs(): List<RepLog> =
        detector?.drainRepLogs() ?: emptyList()

    fun initDetector(actionName: String) {
        detector = when (ActionType.valueOf(actionName)) {
            ActionType.SQUAT -> SquatDetector()
            ActionType.CALF -> CalfDetector()
            ActionType.REHAB_CALF -> try {
                RehabCalfDetector()
            } catch (e: Exception) {
                CalfDetector() // fallback，避免崩潰
            }
        }
    }

    /**
     * 處理 MediaPipe pose landmark 資料並更新狀態。
     */
    fun processFrame(lm: PoseLandmarks, width: Int, height: Int, fps: Float, tsMs: Long) {
        viewModelScope.launch {
            val tsNs = tsMs * 1_000_000
            val result = detector?.onFrame(lm, width, height, fps, tsNs)
            result?.let { _hud.value = it.copy(extra = it.extra + ("fps" to fps)) }
        }
    }

    fun getCounts(): Triple<Int, Int, Int> = detector?.getCounts() ?: Triple(0, 0, 0)
    fun shutdown() { detector = null }
}