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
## 🧠 動作檢測邏輯詳細分析

### 1️⃣ 深蹲（SquatDetector）

#### 偵測方法
使用左右膝關節三點角度（`hip-knee-ankle`）進行動作辨識，選取可見度高者作為主要側。
```kotlin
val (rawAngle, side) = kneeAngleWithSide(landmarks)
```
透過 **指數平滑 (EMA)**：
```kotlin
emaAngle = α * rawAngle + (1 - α) * emaAngle_prev
```
以移除瞬間抖動。

#### 狀態變化
| 狀態 | 轉換條件 | 說明 |
|------|-----------|------|
| STAND → DOWN | 角度 ≤ standUpDeg - 5° | 開始下蹲 |
| DOWN → STAND | 角度 ≥ standUpDeg | 完成一次回合 |

#### 成功條件
底部角度（minAngleThisRep）在範圍：
- `succMinDeg ~ succMaxDeg` → 計為成功  
- `failMinDeg ~ failMaxDeg` → 計為失敗（不夠深）  
- 其他 → 無效深度。

結果記錄：
```kotlin
RepLog(outcome = "SUCCESS", minAngleThisRep = value)
```

#### HUD 資訊
- 角度（當前膝角）
- 狀態（STAND / DOWN）
- 成功、失敗次數
- 活動腿側（L/R）
- 平均 FPS

#### 🏋️ 深蹲 (SquatParams) — 參數與用途詳解

這組參數控制膝關節角度的變化判斷邏輯，決定動作階段辨識與成功計數規則。

| 參數名稱 | 預設值 | 說明 |
|------------|----------|-----------|
| **standUpDeg** | `170f` | 判定「站立完成」的膝關節角度，角度高於此視為直立。 |
| **succMinDeg** | `95f` | 成功下蹲範圍的最小角度（越低表示下蹲越深）。 |
| **succMaxDeg** | `135f` | 成功下蹲範圍的最大角度，超過此代表動作太淺。 |
| **failMinDeg** | `136f` | 失敗動作判定下限角度：此角度以上視為沒下蹲夠深。 |
| **failMaxDeg** | `162f` | 失敗動作判定上限角度。若膝角未逼近站立角就回彈會被算失敗。 |
| **emaAlpha** | `0.35f` | 角度平滑參數（指數移動平均）：越大反應越快速但雜訊越多。 |
| **smoothN** | `5` | 額外平滑步數（多幀平均），降低瞬間角度波動誤差。 |

##### 動作邏輯
- 當膝角下降至 `standUpDeg - 5°` 以下 → 進入「下蹲階段」(DOWN)。  
- 當膝角再次上升超過 `standUpDeg` → 回到「站立階段」(STAND)，記錄一回合結果。  
- 判斷使用回合中達到的最小角度 (`minAngleThisRep`) 決定成功/失敗。

##### 成功區間與失敗條件
- 成功角度：`succMinDeg ≤ minAngle ≤ succMaxDeg` → **SUCCESS**
- 失敗角度 1：`failMinDeg ≤ minAngle ≤ failMaxDeg` → **FAIL_RANGE**
- 失敗角度 2：其他角度區間 → **FAIL_INVALID_DEPTH**（太深或根本沒下去）

> 📈 下蹲演算法以雙腳膝關節中可見度最高的一側為主；角度由 hip、knee、ankle 三點向量計算，單回合結束時的 `RepLog` 會記錄最小角、時間戳與結果。

---

### 2️⃣ 提踵運動（CalfDetector）

#### 偵測核心
以腳跟與腳尖距離（`heel-to-toe baseline`）為基準，偵測腳跟高度變化。

**垂距公式**：
```kotlin
heelLift = |APx * ABy - APy * ABx| / √(ABx² + ABy²)
```
將 heel 相對於 toe-heel 基線的變化角度轉換成度數（θ）。  

**平滑化**與 **Δ角運算**：
```kotlin
deltaDeg = max(0f, absAngle - repBaseDeg)
```

#### 狀態流程
| 狀態 | 進入條件 | 動作說明 |
|-------|------------|----------|
| CALIB | 準備→基準線校正 | 偵測腳安定與足長 |
| IDLE | 靜止狀態 | 等待下一次提踵 |
| RAISING | 抬腳啟動後 | Δ角上升中 |
| HOLDING | 抬腳維持 | 持續判斷 holdSeconds 是否達標 |
| COOLDOWN | 結束回合冷卻期 | 準備下次動作 |

#### 成功與失敗判斷
- 成功：`holdSeconds` 內持續抬腳至 `aMin~aMax` 間  
- 失敗：
  - 提踵不足：`deltaDeg < aMin`
  - 抬太高不穩：`deltaDeg > aMax + tol`
  - 拖地（腳尖離地）超限：`TOE_OFF_GROUND`

##### 特殊防誤：
- **硬性冷卻（COOLDOWN ≥ 1s）**  
- **快速再校準（Fast Recalib）**  
- **雙重防抖 (holdLowFrames + holdDropGraceSec)**  

#### 記錄資訊
- 每回合記錄包括：
  - 起始角 (`baseDeg`)
  - 峰值 Δ角 (`peakDeg`)
  - 持續秒數 (`holdSec`)
  - 結果 (`SUCCESS` / `FAIL_*`)
- 儲存在 `RepLog` 中，於完成後輸出 JSON。

---
#### 🦶 提踵 (CalfParams) — 參數與用途詳解

這套參數控制「提踵」動作的全流程自動判定，從初次校正、起舉、維持、冷卻與重新校正皆適用。

| 參數名稱 | 預設值 | 功能說明 |
|------------|----------|-----------|
| **aMin** | `7.5f` | 抬腳成功窗口角度下限（Δ角大於此視為開始抬腳）。 |
| **aMax** | `60f` | 抬腳成功窗口角度上限，過高代表不穩定或誤判動作。 |
| **idleThreshold** | `6f` | 視為「腳已放下」的角度門檻。低於此會進入 IDLE 狀態。 |
| **holdSeconds** | `3f` | 抬腳後需維持在成功窗口內的秒數。 |
| **emaAlpha** | `0.35f` | 均值平滑係數，影響角度反應速度（越大越即時）。 |
| **angleNoiseMax** | `60f` | 絕對角上限鉗制值：避免圖像尖峰造成錯誤角度。 |

---

##### 🟢 起跳與前置休息
| 參數 | 預設值 | 說明 |
|------|---------|------|
| **raiseEnterDeg** | `3f` | 允許起跳的最低絕對角。 |
| **restNeedSec** | `0.15f` | 進入 RAISING 前必須在低角度靜止至少此秒數。 |

此機制模擬人體「放鬆→起舉」的自然節奏，避免動作銜接過快誤判。

---

##### ⚙️ 初次基線校正 (CALIB)
| 參數 | 預設值 | 功能 |
|------|---------|------|
| **standStillFrames** | `8` | 需連續穩定幀數以確認使用者站穩。 |
| **standStillSpeedPx** | `8f` | toe/heel 移動速度上限 (像素/秒)。 |
| **standStillSlopeMaxDeg** | `20f` | 足部傾斜坡度上限。 |
| **standStillAngleMax** | `6f` | 足部仰角上限。 |
| **maxCalibWaitMs** | `2500L` | 校正最長等待時間（毫秒）。 |
| **minFootLenPx** | `40f` | 足長最小像素距離。 |
| **minFootLenRatio** | `0.08f` | 足長佔整個畫面的最小比例。 |

此區參數確保在站穩時初始化 baseToe/baseHeel 作為基準線。

---

##### 🧭 IDLE 狀態自動重新校正
| 參數 | 預設值 | 意義 / 用途 |
|------|---------|-------------|
| **softDriftPx** | `15f` | 微量飄移（toe/heel 位移）觸發「軟校正」。 |
| **softLenDriftRatio** | `0.25f` | 足長變化允許比率上限。 |
| **softSlopeMaxDeg** | `25f` | 偵測到傾斜過度即啟動重新基線拉回。 |
| **hardDriftPx** | `40f` | 硬性重新校正觸發（明顯位移）。 |
| **hardLenDriftRatio** | `0.50f` | 足長變化過大之界線。 |
| **hardSlopeMaxDeg** | `45f` | 傾斜過度觸發直接回 Calib 狀態。 |
| **recalibFastFrames** | `4` | 快速校正模式需穩定影格數。 |
| **recalibFastTimeoutMs** | `1200L` | 快速重校正超時時間。 |
| **recalibCooldownAfterIdleMs** | `1200L` | 每次校正後冷卻時間，防止頻繁重置。 |
| **recalibNeedStationaryFrames** | `6` | 偵測靜止所需影格數。 |
| **recalibRequireConsecFrames** | `8` | 硬校正需連續達標影格數。 |

---

##### 🦵 抬腳維持階段 (HOLDING)
| 參數 | 預設值 | 功能 |
|------|---------|------|
| **holdDropGraceSec** | `0.25f` | 當 Δ角短暫下降時仍允許持續記秒（抗抖動寬限）。 |
| **holdExitBelowFrames** | `2` | 連續幾幀低於 idleThreshold 即結束 HOLDING。 |
| **holdBandToleranceDeg** | `5f` | 視為成功角度區間的雙向寬容值。 |
| **allowOvershootHold** | `true` | 若角度超出 aMax 但未嚴重偏移仍繼續計時。 |

此階段以穩定角度為主，強調在視窗區間內長時間維持。

---

##### 🔁 冷卻與結束階段 (COOLDOWN)
| 參數 | 預設值 | 功能 |
|------|---------|------|
| **cooldownExitFrames** | `2` | 連續幀數低於 idleThreshold 即回 IDLE。 |
| **cooldownMaxSec** | `1.0f` | 若持續高角度超過此秒數強制結束。 |

---

##### 🧮 統計與回合判斷
| 參數 | 預設值 | 功能 |
|------|----------|------|
| **countSmallAsFail** | `true` | 若 Δ角不足 (小於 aMin) 但仍維持超過 holdSeconds，是否記失敗。 |
| **toeLiftMaxRatio** | `0.06f` | 腳尖離地比例容許上限，依據足長計算。 |
| **fastRaiseOverrideSec** | `1.2f` | 若休息不足但快速達 aMin+2° 並持續此秒數，即視為有效起舉。 |

---

這些 CalfParams 設計用於：
- 強調「動作穩定」與「實際足部幾何」的準確性；
- 考慮光線與相機誤差下之自動調整；
- 同時支持從初次校正→動作維持→冷卻結束的完整狀態機運作。

### 3️⃣ 復健提踵（RehabCalfDetector）

#### 偵測概述
簡化版 CalfDetector，專注於恢復期使用者（緩速穩定、動態平衡重點）。

**關鍵角度來源：**
```kotlin
angle = ∠(ankle, heel, foot_index)
```
左右腳平均角（Double → Float）。

#### 狀態循環
| 狀態 | 條件 | 說明 |
|-------|-------|------|
| STAND | 初始狀態 | 檢測角度待提升 |
| RAISING | angle ≥ raiseEnterDeg | 抬升運動階段 |
| HOLDING | angle ≥ holdMinDeg | 穩定維持狀態，計時 holdSec |
| LOWERING | 角度下降至 idleThreshold | 回歸原位 |

#### 成功條件
- **條件一**：holdSec ≥ `holdSeconds`
- **條件二**：操作過程角度平滑、下降速度低於 `maxLowerSpeedDeg`
成功時：
```kotlin
commitSuccess(angle, holdSec)
```
若提前下降或速度過快：
```kotlin
commitFail("EARLY_LOWER" or "UNSTABLE_RAISE")
```

#### 特點
- 加入速度控制 (`dtheta`) 以判斷抬升穩定度。  
- 適合病患使用，偏重「穩定保持」而非高度。  
- 每回合動作顯示於 `FrameHud` 含平滑角度、fps 與速度。

---

### 🎯 統一統計輸出

所有 Detector 類別皆會：
- 回傳 `FrameHud(angle, state, holdSec, success, fail)`
- 內含詳細統計欄位（Extra Map）：
  - 當前角度、Δ角或基準角
  - FPS
  - 動作側、狀態標籤、持續秒數

完成後由 Session 管理層（`DetectionActivity`）透過：
```kotlin
viewModel.getCounts()
```
匯總出成功 / 失敗 / 總數，並以 `SessionResultWriter` 自動儲存。

---

## 🧘‍♂️ 復健提踵 (RehabCalfParams) — 參數與用途詳解

該組參數適用於病患的緩速柔和復健流程，重點在穩定保持與下降控制，而非高度。

| 參數名稱 | 預設值 | 說明 |
|------------|----------|-----------|
| **emaAlpha** | `0.35f` | 平滑角度變化，抑制影像抖動產生的角度尖峰。 |
| **holdSeconds** | `3.0f` | 抬腳需維持在有效區間內的秒數以計入成功。 |
| **raiseEnterDeg** | `3f` | 進入「抬升狀態」(RAISING) 的最低仰角門檻。 |
| **holdMinDeg** | `5f` | 進入「保持階段」(HOLDING) 的最小仰角。 |
| **lowerExitDeg** | `6f` | 若角度降至此值以下，視為提前結束，回 STAND 狀態並可能記為 FAIL。 |
| **idleThreshold** | `3f` | 定義靜止區，小於此角度代表站穩或未動作。 |
| **maxLowerSpeedDeg** | `40f` | 最大允許下降速度（度/秒），若超出則判為動作不穩或掉落。 |

#### 動作流程狀態機
| 狀態 | 進入條件 | 動作描述 |
|-------|-----------|-----------|
| **STAND** | 起始角 ≤ idleThreshold | 等待提踵開始。 |
| **RAISING** | 角度 ≥ raiseEnterDeg | 抬腳過程持續上升。 |
| **HOLDING** | 角度 ≥ holdMinDeg 且時間累積 ≥ holdSeconds | 維持腳尖抬高狀態。 |
| **LOWERING** | 角度下降或提早放下 | 回復站立或紀錄 FAIL。 |

#### 使用建議
- `raiseEnterDeg` 控制啟動靈敏度，適合不同運動幅度患者；
- `holdSeconds` 可視肌耐力強度調整；
- `maxLowerSpeedDeg` 提供對病患穩定控制力的監測指標。

這些 RehabCalfParams 可用於臨床自動評估腳踝穩定度，以及康復過程中的平衡與控制力訓練。


---
