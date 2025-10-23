package rehabcore.domain

import rehabcore.domain.model.*

interface Detector {
    /** 傳入單幀的 Pose 關鍵點（正規化座標 0..1），回傳疊圖資訊與當前統計 */
    fun onFrame(landmarks: PoseLandmarks, width: Int, height: Int, fps: Float, nowNanos: Long = System.nanoTime()): FrameHud
    /** 取得目前的計數結果（成功/失敗/總數） */
    fun getCounts(): Triple<Int, Int, Int>
    /** 取出目前的回合流水帳（給 JSON 匯出用） */
    fun drainRepLogs(): List<RepLog>
    /** 重置狀態（開始新 session 用） */
    fun reset()
}