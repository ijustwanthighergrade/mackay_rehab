# MediaPipe Samples

This repo hosts the official MediaPipe samples with a goal of showing the fundamental steps involved to create apps with our machine learning platform.

External PRs for fixes are welcome, however new sample/demo PRs will likely be rejected to maintain the simplicity of this repo for ongoing maintenance. It is strongly recommended that contributors who are interested in submitting more complex samples or demos host their samples in their own public repos and create written tutorials to share with the community. Contributors can also submit these projects and tutorials to the [Google DevLibrary](https://devlibrary.withgoogle.com/)

MediaPipe Solutions streamlines on-device ML development and deployment with flexible low-code / no-code tools that provide the modular building blocks for creating custom high-performance solutions for cross-platform deployment. It consists of the following components:
* MediaPipe Tasks (low-code): create and deploy custom e2e ML solution pipelines
* MediaPipe Model Maker (low-code): create custom ML models from advanced solutions
* MediaPipe Studio (no-code): create, evaluate, debug, benchmark, prototype, deploy advanced production-level solutions

## Pose Landmarker Android Project

### Overview

The Pose Landmarker Android project demonstrates the use of MediaPipe for real-time pose detection. It includes components for camera control, pose detection, and result storage.

### Features

- **Camera Control**: Utilizes CameraX for camera initialization and switching.
- **Pose Detection**: Real-time pose detection using MediaPipe.
- **Result Storage**: Saves detection results in JSON format for further analysis.
- **UI Management**: Enhanced UI maintainability and readability using ViewBinding.
- **Diagnostics**: Supports recording and exporting diagnostic data as CSV.

### Usage

1. Launch `MainActivity` and select an action type (e.g., SQUAT, CALF).
2. `DetectionActivity` handles camera binding and pose detection.
3. Detection results are automatically saved as JSON files.

### Integration Guide

- Ensure the main system supports CameraX and MediaPipe.
- Integrate `SessionResultWriter` logic into the main system's data management module.
- Ensure the main system's UI supports ViewBinding.
- Integrate diagnostic features for analysis.

## 專案概述

這個專案是一個復健偵測系統，使用 CameraX 和 MediaPipe 進行姿勢偵測。以下是專案的主要功能和技術架構：

### 功能特色
- **訓練結果儲存**: 使用 `SessionResultWriter` 將訓練結果安全地寫入 JSON 檔案。
- **相機管理**: `CameraController` 負責相機的初始化和切換。
- **姿勢偵測**: `DetectionActivity` 整合 CameraX 和 MediaPipe 進行即時姿勢偵測。
- **狀態管理**: `DetectionViewModel` 管理偵測器狀態和 HUD 更新。
- **主界面**: `MainActivity` 提供動作選擇，使用 ViewBinding 提升維護性。

### 技術架構
- **Kotlin**: 使用 Kotlin 語言開發，提升程式碼可讀性和維護性。
- **CameraX**: 用於相機操作，提供高效的影像處理。
- **MediaPipe**: 用於姿勢偵測，提供即時的偵測能力。
- **ViewModel**: 分離 UI 和邏輯，提升應用的穩定性。

### 使用方法

1. **安裝依賴**: 確保已安裝 Android Studio 並配置好 Android 開發環境。
2. **開啟專案**: 在 Android Studio 中打開專案目錄。
3. **運行應用**: 使用 Android Studio 的運行按鈕將應用部署到連接的 Android 設備上。

### 整合指南

1. **模組化**: 將 `pose_landmarker` 模組作為獨立模組整合到主系統中。
2. **相機權限**: 確保主系統已獲得相機權限。
3. **資料儲存**: 使用 `SessionResultWriter` 來儲存訓練結果，確保資料的安全性和完整性。
4. **擴展功能**: 可以根據需要擴展 `DetectionViewModel` 和 `CameraController` 的功能，以適應不同的偵測需求。
