# 🏥 復健偵測系統整合說明書

本說明文件提供 **Pose Landmarker Android 專案** 的技術總覽、用途、模組說明與整合方式，協助後續開發者快速上手及整合進主系統。

---

## 📘 專案概述

本專案為復健用的姿勢偵測與動作評估應用，基於 **MediaPipe Pose Landmarker** 技術，開發於 Android 平台，協助復健患者即時監測與分析動作表現。  

### 🎯 開發目標
- **動作辨識與精準偵測**：判斷使用者是否正確執行深蹲、提踵、復健提踵等動作。  
- **即時視覺回饋**：透過骨架圖與 HUD 提供即時運動分析。  
- **客製化復健規則**：以角度與持續時間等參數判定成果。  
- **結果記錄與匯出**：儲存訓練歷程（JSON/CSV）。

### 💡 設計理念
採用 **模組化架構 (Modular Architecture)**：
- `rehabcore.domain` — 偵測邏輯。
- `rehabcore.mediapipe` — 模型推論控制。
- `rehabcore.overlay` — 畫面繪製。
- `app.ui` — 使用者互動。

優點：
- 易於維護與擴充。
- 各層可獨立測試與重用。
- 模型、參數、UI 可模組化整合。

---

## ⚙️ 功能特色

1. **多動作支援**  
   - 深蹲 (Squat)  
   - 提踵 (Calf Raise)  
   - 復健提踵 (Rehab Calf)

2. **即時姿勢分析**  
   [`PoseLandmarkerClient`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/rehabcore/mediapipe/PoseLandmarkerClient.kt:23) 使用 Live Stream 處理鏡頭幀，依 landmarks 更新顯示。

3. **HUD 與骨架繪製**  
   [`SkeletonOverlay`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/rehabcore/overlay/SkeletonOverlay.kt:11) 負責視覺呈現。

4. **訓練結果紀錄**  
   [`SessionResultWriter`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/app/data/SessionResultWriter.kt) 輸出 JSON/CSV 統計。

---

## 🧱 技術架構

主流程：
```
CameraX ▶ PoseLandmarkerClient ▶ Detector ▶ HUD/Overlay ▶ ViewModel ▶ UI
```

### 模組分層
- **MediaPipe 層**  
  負責影像轉換、姿勢推論。
- **Domain 層**  
  判斷姿勢成功/失敗、計數、角度。
- **Overlay 層**  
  儲存繪製狀態並於 Canvas 即時呈現。
- **UI 層**  
  提供互動、參數設定及結果儲存。

---

## 📁 主要元件

| 模組 | 功能 | 範例檔案 |
|-------|-------|-----------|
| `app/ui` | 主畫面與偵測流程控制 | [`MainActivity.kt`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/app/ui/MainActivity.kt:15) |
| `app/data` | 資料輸出與儲存 | [`SessionResultWriter.kt`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/app/data/SessionResultWriter.kt) |
| `rehabcore/domain` | 動作邏輯與狀態監測 | [`Detector.kt`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/rehabcore/domain/Detector.kt:5) |
| `rehabcore/mediapipe` | 姿勢推論封裝 | [`PoseLandmarkerClient.kt`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/rehabcore/mediapipe/PoseLandmarkerClient.kt:23) |
| `rehabcore/overlay` | HUD、骨架繪製 | [`SkeletonOverlay.kt`](mediapipe-samples/examples/pose_landmarker/android/app/src/main/java/rehabcore/overlay/SkeletonOverlay.kt:11) |

---

## 🚀 使用方法

### 1️⃣ 需求
- Android Studio Arctic Fox+
- API ≥ 24  
- Gradle ≥ 7  
- Kotlin ≥ 1.7  
- MediaPipe Tasks Vision

### 2️⃣ 建置步驟
```bash
# 匯入專案
open mediapipe-samples/examples/pose_landmarker/android/
# 執行
Run -> MainActivity
```

偵測後會自動生成結果檔：
```
/data/data/<package>/files/sessions/YYYYMMDD_HHmm_<ACTION>.json
```

---

## 🔗 整合指南

### 與主系統整合
可作為獨立模組導入：
```gradle
include(":pose_module")
project(":pose_module").projectDir = file("mediapipe-samples/examples/pose_landmarker/android/app")
```

或直接啟動：
```kotlin
startActivity(
  Intent(this, DetectionActivity::class.java)
    .putExtra(DetectionActivity.EXTRA_ACTION, "CALF")
)
```

### 結果回傳與上傳
整合後可由主系統監聽：
```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (resultCode == RESULT_OK) { uploadSessionJson() }
}
```

---

## 🧩 進階開發與除錯技巧

- **FPS低落** → 改用 `pose_landmarker_lite.task`  
- **鏡像異常** → 調整 `mirror` flag  
- **骨架閃爍** → 在 `invalidate()` 地方節流重繪  
- **新增動作** → 實作新 Detector 並補充至 `ActionType`

### 未來方向
- AI 自動糾正姿勢  
- 復健進度雲端統計  
- 跨平台程式庫封裝  
- Node/Flutter 整合版本

---

👤 **維護責任人**：Mackay Rehab R&D  
📅 文件更新日期：2025/10/27