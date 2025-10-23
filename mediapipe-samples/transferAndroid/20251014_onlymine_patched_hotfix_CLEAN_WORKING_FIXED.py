#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Rehab Counter — Squat (knee-angle) & Calf Raise (ground-referenced) ONLY
-----------------------------------------------------------------------
- 移除：高抬腿 / 左右跨步 / 手臂抬舉 / 二頭彎舉 / AUTO 模式。
- 僅保留：深蹲（膝角法、含 rearm=170° 與成功/失敗分級）、提踵（toe→heel 相對地面角區間保持 N 秒後計 1 下，含分級）。
- 保留地面估計（以軀幹估水平）與疊字、即時錄影/檔案處理兩種流程。

使用方式：
1) 執行本檔，先選擇動作（深蹲/提踵），再選擇使用「攝影機」或「影片」。
2) 產生輸出於 ./output/*.mp4。
"""

import os
import os.path
import math
from collections import deque
import cv2
import numpy as np
import functools
import mediapipe as mp
import tkinter as tk
from tkinter import messagebox, filedialog, simpledialog
from PIL import Image, ImageDraw, ImageFont
import time

# === GUI root (for dialogs) ===
_tk_root = None
def _ensure_tk_root():
    global _tk_root
    if _tk_root is None:
        try:
            _tk_root = tk.Tk()
            _tk_root.withdraw()
        except Exception:
            _tk_root = False  # mark as unavailable
    return _tk_root


def choose_input_source():
    """
    Returns ("webcam", 0) or ("video", path) or ("cancel", None).
    """
    root = _ensure_tk_root()
    use_cam = False
    if root:
        try:
            use_cam = messagebox.askyesno("輸入來源", "要使用即時攝影機嗎？按「是」= 攝影機；按「否」= 選擇影片檔。")
        except Exception:
            use_cam = False
    if use_cam:
        return ("webcam", 0)

    # 檔案選擇
    path = ""
    if root:
        try:
            path = filedialog.askopenfilename(
                title="選擇影片檔",
                filetypes=[("Video Files", "*.mp4 *.mov *.avi *.mkv"), ("All Files", "*.*")]
            )
        except Exception:
            path = ""
    if not path:
        print("[info] 未選擇影片，或 GUI 不可用。")
        return ("cancel", None)
    return ("video", path)

# === Timecode parsing helper ===
def parse_timecode(val):
    """
    Accepts seconds (int/float string), "MM:SS", or "HH:MM:SS[.ms]".
    Returns float seconds.
    """
    if val is None or val == "":
        return 0.0
    if isinstance(val, (int, float)):
        return float(val)
    s = str(val).strip()
    # plain number (seconds)
    try:
        return float(s)
    except ValueError:
        pass
    parts = s.split(":")
    if len(parts) == 2:  # MM:SS(.ms)
        mm, ss = parts
        return int(mm) * 60 + float(ss)
    if len(parts) == 3:  # HH:MM:SS(.ms)
        hh, mm, ss = parts
        return int(hh) * 3600 + int(mm) * 60 + float(ss)
    # fallback
    raise ValueError(f"Unrecognized time format for --start: {val}")


def resize_to_max_height(frame, max_h=720):
    """若影像高度超過 max_h，就等比例縮小到 max_h；回傳 (resized_frame, new_w, new_h, scaled)"""
    h, w = frame.shape[:2]
    if h <= max_h:
        return frame, w, h, False
    scale = max_h / float(h)
    new_w, new_h = int(round(w * scale)), int(round(h * scale))
    resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
    return resized, new_w, new_h, True




# === Frame-level global stabilization (affine, partial 2D) ===
class GlobalStab:
    def __init__(self, max_corners=500, quality=0.01, min_distance=8, ransac_thresh=3.0):
        self.prev_gray = None
        self.prev_stab = None
        self.A = np.eye(2, 3, dtype=np.float32)  # last affine
        self.max_corners = max_corners
        self.quality = quality
        self.min_distance = min_distance
        self.ransac_thresh = ransac_thresh

    def stabilize(self, frame_bgr):
        gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
        if self.prev_gray is None:
            self.prev_gray = gray
            self.prev_stab = frame_bgr
            return frame_bgr, 0.0

        # Features on previous stabilized frame
        prev_pts = cv2.goodFeaturesToTrack(self.prev_gray, maxCorners=self.max_corners,
                                           qualityLevel=self.quality, minDistance=self.min_distance)
        if prev_pts is None or len(prev_pts) < 50:
            # not enough points; fall back
            self.prev_gray = gray
            self.prev_stab = frame_bgr
            return frame_bgr, 0.0

        # Track to current raw frame
        next_pts, st, err = cv2.calcOpticalFlowPyrLK(self.prev_gray, gray, prev_pts, None)
        good_prev = prev_pts[st.reshape(-1) == 1]
        good_next = next_pts[st.reshape(-1) == 1] if next_pts is not None else None
        if good_next is None or len(good_next) < 30:
            self.prev_gray = gray
            self.prev_stab = frame_bgr
            return frame_bgr, 0.0

        # Estimate affine from current to previous (to align current -> prev)
        M, inliers = cv2.estimateAffinePartial2D(good_next, good_prev, method=cv2.RANSAC,
                                                 ransacReprojThreshold=self.ransac_thresh)
        if M is None:
            self.prev_gray = gray
            self.prev_stab = frame_bgr
            return frame_bgr, 0.0

        h, w = gray.shape[:2]
        stabilized = cv2.warpAffine(frame_bgr, M, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)

        # Save for next round
        self.prev_gray = cv2.cvtColor(stabilized, cv2.COLOR_BGR2GRAY)
        self.prev_stab = stabilized
        self.A = M.astype(np.float32)

        # compute shift magnitude (for HUD/debug)
        dx = float(M[0,2]); dy = float(M[1,2])
        mag = (dx*dx + dy*dy) ** 0.5
        return stabilized, mag


# =====================
# 文字疊圖（含中文）
# =====================

mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils


def put_chinese_text(image, text, position, font_scale=0.7, color=(255, 255, 255), thickness=2):
    """在圖片上顯示中文文字（優先 PIL，否則退回 cv2）。"""
    try:
        pil_image = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(pil_image)
        try:
            font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", int(font_scale * 32))
        except Exception:
            try:
                font = ImageFont.truetype("C:/Windows/Fonts/msjh.ttc", int(font_scale * 32))
            except Exception:
                font = ImageFont.load_default()
        draw.text(position, text, font=font, fill=tuple(color))
        return cv2.cvtColor(np.array(pil_image), cv2.COLOR_RGB2BGR)
    except Exception:
        cv2.putText(image, text, position, cv2.FONT_HERSHEY_SIMPLEX, font_scale, color, thickness, cv2.LINE_AA)
        return image


@functools.lru_cache(maxsize=64)
def _cached_font(path, sz):
    try:
        return ImageFont.truetype(path, sz)
    except Exception:
        return ImageFont.load_default()

def draw_text_block(image, lines, anchor='lt', margin=16, max_width=None,
                    color=(0, 255, 0), bg_color=(0, 0, 0, 160),
                    max_font_px=18, min_font_px=12, line_gap=6, stroke=1):
    """
    穩定版：自動換行資訊框。
    - 支援 margin=int 或 (x, y)
    - 優先用 Pillow；失敗則退回 OpenCV + put_chinese_text（不會整塊消失）
    """
    # ---- utils ----
    def _norm_margin(m):
        if isinstance(m, (tuple, list)) and len(m) == 2:
            return int(m[0]), int(m[1])
        return int(m), int(m)

    def _anchor_xy(W, H, bw, bh, anchor, margin_xy):
        mx, my = margin_xy
        ax = anchor[0].lower() if anchor else 'l'
        ay = anchor[1].lower() if len(anchor) > 1 else 't'
        x = mx if ax == 'l' else (W - mx - bw if ax == 'r' else (W - bw)//2)
        y = my if ay == 't' else (H - my - bh if ay == 'b' else (H - bh)//2)
        return int(x), int(y)

    # ---- Pillow route ----
    try:
        @functools.lru_cache(maxsize=64)
        def _cached_font(path, size):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                return None

        def _load_font(sz: int):
            # 依你的電腦環境挑一個就好；順序會自動 fallback
            paths = [
                "C:/Windows/Fonts/msjh.ttc",   # 微軟正黑
                "C:/Windows/Fonts/msyh.ttc",   # 微軟雅黑
                "C:/Windows/Fonts/simhei.ttf", # 黑體
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            ]
            for p in paths:
                f = _cached_font(p, sz)
                if f: return f
            return ImageFont.load_default()

        pil = Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))
        W, H = pil.size
        mx, my = _norm_margin(margin)
        if max_width is None:
            max_width = max(50, W - 2*mx)

        draw = ImageDraw.Draw(pil, 'RGBA')

        # 兼容不同 Pillow 版本：textbbox 不一定支援 stroke_width
        def _textbbox(text, font):
            try:
                return draw.textbbox((0, 0), text, font=font, stroke_width=stroke)
            except TypeError:
                return draw.textbbox((0, 0), text, font=font)

        # 字串正規化
        if isinstance(lines, str):
            lines = lines.split('\n')
        lines = ["" if l is None else str(l) for l in lines]

        # 試出最大可用字體（簡單二分）
        lo, hi = int(min_font_px), int(max_font_px)
        best_font = _load_font(lo)
        def _wrap(font, src):
            out = []
            for s in src:
                if s == "":
                    out.append("")
                    continue
                buf = ""
                for ch in s:
                    test = buf + ch
                    b = _textbbox(test, font); tw = b[2]-b[0]
                    if tw <= max_width:
                        buf = test
                    else:
                        if buf: out.append(buf)
                        buf = ch
                if buf: out.append(buf)
            return out

        best_wrapped = _wrap(best_font, lines)
        while lo <= hi:
            mid = (lo + hi)//2
            f = _load_font(mid)
            wrapped = _wrap(f, lines)
            too_wide = any((_textbbox(s, f)[2]-_textbbox(s, f)[0]) > max_width for s in wrapped if s)
            if not too_wide:
                best_font, best_wrapped = f, wrapped
                lo = mid + 1
            else:
                hi = mid - 1

        # 計尺寸
        line_sizes = []
        block_w = 0
        for s in best_wrapped:
            b = _textbbox(s, best_font)
            w, h = (b[2]-b[0], b[3]-b[1])
            line_sizes.append((w, h))
            if w > block_w: block_w = w
        block_h = sum(h for _,h in line_sizes) + line_gap*max(0, len(line_sizes)-1)

        # 定位＋背景
        x, y = _anchor_xy(W, H, block_w+16, block_h+12, anchor, (mx, my))
        bg_box = (x, y, x + block_w + 16, y + block_h + 12)
        draw.rectangle(bg_box, fill=(bg_color[0], bg_color[1], bg_color[2], bg_color[3] if len(bg_color)==4 else 160))

        # 畫字
        yy = y + 6
        xx = x + 8
        for s, (w, h) in zip(best_wrapped, line_sizes):
            try:
                draw.text((xx, yy), s, font=best_font, fill=color, stroke_width=stroke, stroke_fill=(0,0,0))
            except TypeError:
                draw.text((xx, yy), s, font=best_font, fill=color)
            yy += h + line_gap

        return cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)

    except Exception:
        # ---- OpenCV fallback（永不炸）----
        if isinstance(lines, str):
            lines = lines.split('\n')
        H, W = image.shape[:2]
        mx, my = _norm_margin(margin)
        # 估計單行寬與字高（中文用 put_chinese_text 顯示，量測用 cv2 英文字寬近似）
        font_scale = max(0.5, min(1.0, max_font_px/24.0))
        line_h = int(24*font_scale + line_gap)
        # 粗略估寬
        block_w = 0
        for s in lines:
            (tw, th), _ = cv2.getTextSize(s, cv2.FONT_HERSHEY_SIMPLEX, font_scale, 2)
            block_w = max(block_w, tw)
        block_h = line_h * len(lines)

        x, y = _anchor_xy(W, H, block_w+16, block_h+12, anchor, (mx, my))
        cv2.rectangle(image, (x, y), (x+block_w+16, y+block_h+12), (0,0,0), -1)

        yy = y + 6
        xx = x + 8
        for s in lines:
            image = put_chinese_text(image, s, (xx, yy + int(20*font_scale)), font_scale=font_scale, color=color, thickness=2)
            yy += line_h
        return image



# ===============
# 幾何工具
# ===============

def calculate_angle(a, b, c):
    a = np.array(a); b = np.array(b); c = np.array(c)
    ba = a - b
    bc = c - b
    denom = (np.linalg.norm(ba) * np.linalg.norm(bc)) + 1e-8
    cosine = float(np.clip(np.dot(ba, bc) / denom, -1.0, 1.0))
    return float(np.degrees(np.arccos(cosine)))


def get_landmark_dict(landmarks):
    d = {}
    for lm in mp_pose.PoseLandmark:
        i = lm.value
        pt = landmarks[i]
        d[lm.name.lower()] = (pt.x, pt.y, pt.z, pt.visibility)
    return d


class LandmarkSmoother:
    def __init__(self, smoothing_size=5):
        self.smoothing_size = smoothing_size
        self.right_hip_history, self.right_knee_history, self.right_ankle_history = [], [], []
        self.left_hip_history, self.left_knee_history, self.left_ankle_history = [], [], []

    def _smooth(self, hist, p):
        if p is None:
            return p
        hist.append(p)
        if len(hist) > self.smoothing_size:
            hist.pop(0)
        xs = [q[0] for q in hist]
        ys = [q[1] for q in hist]
        return [sum(xs) / len(xs), sum(ys) / len(ys)]

    def smooth_right_leg(self, hip, knee, ankle):
        return self._smooth(self.right_hip_history, hip), \
               self._smooth(self.right_knee_history, knee), \
               self._smooth(self.right_ankle_history, ankle)

    def smooth_left_leg(self, hip, knee, ankle):
        return self._smooth(self.left_hip_history, hip), \
               self._smooth(self.left_knee_history, knee), \
               self._smooth(self.left_ankle_history, ankle)


# =====================================
# 動作 1：深蹲（膝關節角度 + 分級）
# =====================================

class SquatKneeAngleThresholdDetector:
    """
    規則：< down_deg 計一次；回到 > rearm_deg 才能再次計數。
    分級：以 standard_deg 為標準角，成功=±20% 內；失敗= -20%~-40%（其餘不計）。
    備註：深蹲角度「越小越深」，direction='lower'。
    """
    def __init__(self,
                 stand_up_deg=170.0,            # 站回來（re-arm）門檻
                 succ_min_deg=95.0, succ_max_deg=135.0,
                 fail_min_deg=136.0, fail_max_deg=162.0,
                 ema_alpha=0.35, standard_deg=135.0,  # standard_deg 只用於顯示
                 vis_thr=0.6, smooth_N=5):
        
        self.stand_up_deg = float(stand_up_deg)
        self.succ_min_deg, self.succ_max_deg = float(succ_min_deg), float(succ_max_deg)
        self.fail_min_deg, self.fail_max_deg = float(fail_min_deg), float(fail_max_deg)

        self.alpha = float(ema_alpha)
        self.standard_deg = float(standard_deg)
        self.vis_thr = float(vis_thr)
        self.SMOOTHING_SIZE = int(smooth_N)

        # 計數
        self.success = 0
        self.fail = 0

        # 回合狀態
        self.prev_deg = None
        self.in_rep = False
        self.min_angle_this_rep = None
        self.touched_success = False
        self.touched_fail = False

        self.landmark_smoother = LandmarkSmoother(smoothing_size=self.SMOOTHING_SIZE)
            
    def draw_overlay(self, frame, W, H):
        try:
            total = self.success + self.fail
            rate = (self.success / total * 100.0) if total > 0 else 0.0
            state_txt = "IN-REP" if self.in_rep else "IDLE"
            knee_txt = f"{self.prev_deg:.1f}°" if self.prev_deg is not None else "--"
            if self.prev_deg is not None:
                knee_txt = f"{self.prev_deg:.1f}°"
            lines = [
                "深蹲 (膝角) — 新規則",
                f"狀態: {state_txt}    當前膝角: {knee_txt}",
                f"成功區: {self.succ_min_deg:.0f}–{self.succ_max_deg:.0f}°    失敗區: {self.fail_min_deg:.0f}–{self.fail_max_deg:.0f}°    站回: ≥{self.stand_up_deg:.0f}°",
                f"成功: {self.success}    失敗: {self.fail}    總數: {total}    成功率: {rate:.1f}%"
            ]
            return draw_text_block(frame, lines, anchor='lt', margin=24, color=(255,255,255),
                                max_font_px=18, min_font_px=14, line_gap=6, stroke=2)
        except Exception:
            return frame
            
    def _best_knee_triplet(self, landmarks):
        lk = landmarks[mp_pose.PoseLandmark.LEFT_KNEE.value] if landmarks else None
        rk = landmarks[mp_pose.PoseLandmark.RIGHT_KNEE.value] if landmarks else None
        lv = lk.visibility if lk is not None else 0.0
        rv = rk.visibility if rk is not None else 0.0
        side = "left" if lv >= rv else "right"
        def L(name):
            idx = getattr(mp_pose.PoseLandmark, f"{side.upper()}_{name}".upper()).value
            p = landmarks[idx]
            return [p.x, p.y]
        hip = L("hip"); knee = L("knee"); ankle = L("ankle")
        return side, hip, knee, ankle

    def process_frame(self, landmarks, frame, W, H):
        try:
            side, hip, knee, ankle = self._best_knee_triplet(landmarks)
            if side == "left":
                hip, knee, ankle = self.landmark_smoother.smooth_left_leg(hip, knee, ankle)
            else:
                hip, knee, ankle = self.landmark_smoother.smooth_right_leg(hip, knee, ankle)

            raw = calculate_angle(hip, knee, ankle)

            # EMA 平滑
            cur = raw if self.prev_deg is None else (self.alpha * raw + (1 - self.alpha) * self.prev_deg)
            self.prev_deg = cur

            # 進入回合：當角度已經明顯低於「站立角」一些（用 fail_max 當鬆入門）
            if (not self.in_rep) and (cur <= self.fail_max_deg):
                self.in_rep = True
                self.min_angle_this_rep = cur
                self.touched_success = (self.succ_min_deg <= cur <= self.succ_max_deg)
                self.touched_fail = (self.fail_min_deg <= cur <= self.fail_max_deg)

            # 回合中：更新最低角與是否觸碰到成功/失敗區
            if self.in_rep:
                if cur < self.min_angle_this_rep:
                    self.min_angle_this_rep = cur
                if self.succ_min_deg <= cur <= self.succ_max_deg:
                    self.touched_success = True
                if (not self.touched_success) and (self.fail_min_deg <= cur <= self.fail_max_deg):
                    self.touched_fail = True

                # 結算：當角度回升並「站回來」(>= stand_up_deg)
                if cur >= self.stand_up_deg:
                    if self.touched_success:
                        self.success += 1
                        outcome = "SUCCESS"
                    elif self.touched_fail:
                        self.fail += 1
                        outcome = "FAIL_RANGE_136_162"
                    else:
                        outcome = "IGNORED"

                    print(f"[SQUAT LOG] min={self.min_angle_this_rep:.1f}°  outcome={outcome}  "
                        f"succ={self.success}  fail={self.fail}")

                    # 重置回合
                    self.in_rep = False
                    self.min_angle_this_rep = None
                    self.touched_success = False
                    self.touched_fail = False

            # 疊圖（保留你原本的資訊塊格式）
            total = self.success + self.fail
            rate = (self.success / total * 100.0) if total > 0 else 0.0
            frame = draw_text_block(
                frame,
                [
                    "深蹲（膝角法）",
                    f"膝角：{cur:.1f}°   成功：{self.success}  失敗：{self.fail}  總數：{total}  成功率：{rate:.1f}%",
                    f"規則：回合最低角達 95–135°，站回 ≥{self.stand_up_deg:.0f}° 計成；若只到 136–162° 後站回則判失敗",
                ],
                anchor='lt', margin=16, color=(0, 255, 0), max_font_px=18, min_font_px=14, line_gap=6, stroke=2,
            )
        except Exception:
            pass
        return frame

    def get_counts(self):
        total = self.success + self.fail
        return self.success, self.fail, total


# =============================================
# 動作 2：提踵（地面參考 toe→heel 角 + 分級）
# =============================================

# -*- coding: utf-8 -*-
"""
Calf Raise (提踵) baseline-angle module
- 基準：以「貼地時」的 toe 與 heel 兩點建立腳底線
- 每幀以「當前 heel 到基準線的垂直距離 h」換算角度：theta = arctan(h / L)
- 成功：20°–45° 且連續 ≥3 秒
- 失敗：18°–24°（30° 的 -40%～-20%）
"""


# Mediapipe landmark indices
L_HEEL, R_HEEL = 29, 30
L_TOE,  R_TOE  = 31, 32

class CalfSide:
    def __init__(self, side="left",
                 # ---- 新門檻：成功 7.5~45；小幅度失敗 5.0~7.4 ----
                 success_min_deg=7.5, success_max_deg=45.0,
                 fail_min_deg=5.0, fail_max_deg=7.4,
                 hold_seconds=3.0, angle_noise_max=60.0, idle_threshold=8.0,
                 ema_alpha=0.35, calib_frames=20, calib_jitter_px=4.0,
                 enforce_toe_ground=False, toe_ground_max_h=6.0):
        """
        enforce_toe_ground: True 時，若 toe 也離基準線過遠，暫停本回合計數（避免前腳掌離地 / 跳步）
        toe_ground_max_h: toe 到基準線的最大允許垂距（像素）
        """
        self.side = side
        self.SUCCESS_MIN_DEG = success_min_deg
        self.SUCCESS_MAX_DEG = success_max_deg
        self.FAIL_MIN_DEG    = fail_min_deg
        self.FAIL_MAX_DEG    = fail_max_deg
        self.HOLD_SECONDS    = hold_seconds
        self.ANGLE_NOISE_MAX = angle_noise_max
        self.IDLE_THRESHOLD  = idle_threshold
        self.EMA_ALPHA       = ema_alpha
        self.CALIB_FRAMES    = calib_frames
        self.CALIB_JITTER_PX = calib_jitter_px
        self.ENFORCE_TOE_GROUND = enforce_toe_ground
        self.TOE_GROUND_MAX_H   = toe_ground_max_h
        
        self.entered_success_zone = False  # 本回合是否曾進入成功區(>=20°)

        self.rep_peak_deg = 0.0     # 單次「回合」(raise→hold→down) 的最高角度
        self.outcome_done = False   # 本回合是否已結算（避免重複加）
        self.cooldown_frames = 0  # 放下後冷卻幀數（期間禁止任何結算）
        self.rest_frames = 0         # 連續處於「休息」（低角度）狀態的幀數
        self.can_raise = False       # 是否允許進入 RAISING（必須先休息夠久才 True）
        # Robust gating
        self.RAISE_ENTER_DEG = 15.0  # between fail and success
        self.MIN_RISE_FRAMES = 4
        
        
        # ===== 新增：每回合紀錄與流水號 =====
        self.rep_base_deg = 0.0   # 進入 RAISING 當下的「基準角度」
        self.rep_id = 0          # 流水號
        self.reset(hard=True)

    # ---------- public APIs ----------
      
    def _log_outcome(self, kind: str, fps: float):
        self.rep_id += 1
        hold_s = self.hold_frames / max(1.0, fps)
        print(
            f"[CALF LOG] #{self.rep_id:03d} "
            f"base={self.rep_base_deg:.1f}°  "
            f"peak={self.rep_peak_deg:.1f}°  "
            f"hold={hold_s:.2f}s  "
            f"outcome={kind}"
        )
    def reset(self, hard=False):
        self.state = "CALIB" if hard else "IDLE"  # CALIB -> IDLE -> RAISING -> HOLDING
        self.ema_deg = None
        self.peak_deg = 0.0
        self.hold_frames = 0
        
        self.raising_frames = 0
        self.rep_success = 0
        self.rep_fail = 0

        # baseline / calib
        self.calib_heel_q = deque(maxlen=self.CALIB_FRAMES)
        self.calib_toe_q  = deque(maxlen=self.CALIB_FRAMES)
        self.baseline_ready = False
        self.toe_base_px = None
        self.heel_base_px = None
        self.L = None  # baseline foot length (pixels)
        self.calib_deg = None  # ← 之後校正完成時設為 0.0°（或站立基準角的估計）



    def feed(self, lms, W, H, fps):
        """每幀呼叫：lms=results.pose_landmarks.landmark, W/H 影像大小, fps 幀率"""
        idx_toe, idx_heel = self._idxs()
        toe  = lms[idx_toe]; heel = lms[idx_heel]
        toe_px  = (toe.x * W,  toe.y * H)
        heel_px = (heel.x * W, heel.y * H)

        # ----- Calibration: build baseline when toe/heel vertical jitter is small -----
        if not self.baseline_ready:
            self.calib_toe_q.append(toe_px)
            self.calib_heel_q.append(heel_px)

            if len(self.calib_heel_q) == self.CALIB_FRAMES:
                heel_y_range = max(p[1] for p in self.calib_heel_q) - min(p[1] for p in self.calib_heel_q)
                toe_y_range  = max(p[1] for p in self.calib_toe_q)  - min(p[1] for p in self.calib_toe_q)

                if heel_y_range < self.CALIB_JITTER_PX and toe_y_range < self.CALIB_JITTER_PX:
                    self.heel_base_px = self._median_point(self.calib_heel_q)
                    self.toe_base_px  = self._median_point(self.calib_toe_q)
                    self.L = self._dist(self.toe_base_px, self.heel_base_px)
                    if self.L >= 1.0:
                        self.baseline_ready = True
                        self.state = "IDLE"
                        self.calib_deg = 0.0        # ★ 校正期的基準角（toe/heel 在基準線上 ⇒ 0°）
                    else:
                        # too short: re-calibrate
                        self.calib_toe_q.clear(); self.calib_heel_q.clear()
            return 0.0, self._dbg(fps)

        # ----- With baseline: compute vertical distance h from heel to baseline, convert to angle -----
        ax, ay = self.toe_base_px
        bx, by = self.heel_base_px
        px, py = heel_px

        ABx, ABy = (bx-ax), (by-ay)
        APx, APy = (px-ax), (py-ay)
        AB = math.hypot(ABx, ABy)
        if AB < 1.0:
            return 0.0, self._dbg(fps)

        cross = abs(APx * ABy - APy * ABx)  # parallelogram area
        h_heel = cross / AB

        # Optional: check toe stays near baseline to avoid jumping
        h_toe = None
        if self.ENFORCE_TOE_GROUND:
            tpx, tpy = toe_px
            APx_t, APy_t = (tpx-ax), (tpy-ay)
            h_toe = abs(APx_t * ABy - APy_t * ABx) / AB
            if h_toe > self.TOE_GROUND_MAX_H:
                # suspend this frame's counting (keep state but don't progress)
                return self._ema(0.0), self._dbg(fps, h_heel=h_heel, h_toe=h_toe, suspended=True)

        theta = math.degrees(math.atan2(h_heel, self.L))
        if theta > self.ANGLE_NOISE_MAX:
            theta = self.ANGLE_NOISE_MAX

        deg = self._ema(theta)
        self.peak_deg = max(self.peak_deg, deg)
            # ---- 就緒鎖：必須先在低角度休息 N 幀才允許再起 ----
        REST_NEED = max(3, int(0.20 * fps))  # 約 0.2 秒，你可依影片調 0.15~0.3s
        if deg <= self.IDLE_THRESHOLD:
            self.rest_frames = min(REST_NEED, self.rest_frames + 1)
        else:
            # 只要角度又抬高，立即清零，重新累積休息幀
            self.rest_frames = 0
        # 只有在完全踩穩後，才開啟下一次 RAISING 的資格
        if self.rest_frames >= REST_NEED:
            self.can_raise = True
            
            
        # ----- global cooldown 防重入（成功或失敗結算後，鎖一小段幀數）-----
        if self.state == "COOLDOWN":
            if self.cooldown_frames > 0:
                self.cooldown_frames -= 1
                return self._ema(0.0 if self.ema_deg is None else self.ema_deg), self._dbg(fps)
            # 冷卻結束 → 回到 IDLE，但先要求重新踩穩
            self.state = "IDLE"
            self.can_raise = False     # ← 新增
            self.rest_frames = 0       # ← 新增


        # 只在 RAISING/HOLDING 期間更新本回合峰值
        if self.state in ("RAISING", "HOLDING"):
            self.rep_peak_deg = max(self.rep_peak_deg, deg)

        # ----- State machine -----
        if self.state == "IDLE":
            # 開始新回合
            self.rep_peak_deg = 0.0
            self.outcome_done = False
            self.entered_success_zone = False   # ← 新增
            self.hold_frames = 0
            self.raising_frames = 0

            if deg >= self.RAISE_ENTER_DEG and self.can_raise:
                self.state = "RAISING"
                self.raising_frames = 1
                self.rep_peak_deg = deg
                self.rep_base_deg = (self.calib_deg if (self.calib_deg is not None) else deg)

                self.can_raise = False         # 一旦起跳就鎖住，等下次踩穩再解鎖

        elif self.state == "RAISING":
            self.raising_frames += 1
            # 1) 進入成功區 → 切到 HOLDING，開始用 hold_frames 計時
            if deg >= self.SUCCESS_MIN_DEG:
                self.state = "HOLDING"
                self.hold_frames = 1
                self.entered_success_zone = True
                self.rep_peak_deg = max(self.rep_peak_deg, deg)
            else:
                # 2) 還在 RAISING：更新峰值
                self.rep_peak_deg = max(self.rep_peak_deg, deg)

                # 2a) 小幅度區 (5~7.4°)：在 RAISING 也要計「維持幀數」
                if self.FAIL_MIN_DEG <= deg <= self.FAIL_MAX_DEG:
                    self.hold_frames += 1
                else:
                    # 只要離開小幅度區，清零小幅度的 hold 計數（避免斷續堆疊）
                    self.hold_frames = 0

                # 2b) 若角度掉回休息閾值以下 → 檢查是否構成「小幅度失敗」
                if deg < self.IDLE_THRESHOLD:
                    if (not self.outcome_done) and (not self.entered_success_zone) and (self.raising_frames >= self.MIN_RISE_FRAMES):
                        need = int(self.HOLD_SECONDS * fps)
                        if (self.FAIL_MIN_DEG <= self.rep_peak_deg <= self.FAIL_MAX_DEG) and (self.hold_frames >= need):
                            self.rep_fail += 1
                            self._log_outcome("FAIL_SMALL_KEPT", fps)
                            self.outcome_done = True
                    # 不論是否結算，都進入冷卻
                    self.state = "COOLDOWN"
                    self.cooldown_frames = max(1, int(0.15 * fps))
                    self.rep_peak_deg = 0.0
                    self.hold_frames = 0
                    self.raising_frames = 0

        elif self.state == "HOLDING":
            if deg >= self.SUCCESS_MIN_DEG:
                self.hold_frames += 1
            else:
                # 離開成功區（放下） → 只結算一次
                need = int(self.HOLD_SECONDS * fps)
                if not self.outcome_done:
                    if (self.hold_frames >= need) and (self.SUCCESS_MIN_DEG <= self.rep_peak_deg <= self.SUCCESS_MAX_DEG):
                        # 規則(成功)：7.5~45° 持續≥3s
                        self.rep_success += 1
                        self._log_outcome("SUCCESS", fps)
                    else:
                        # 規則(失敗-短)：>7.5° 但撐不到 3 秒
                        if self.rep_peak_deg > self.SUCCESS_MIN_DEG:
                            self.rep_fail += 1
                            self._log_outcome("FAIL_SHORT_HOLD", fps)
                        # 規則(失敗-小幅長時間) 在 RAISING 分支已處理；這裡不重覆計
                    self.outcome_done = True

                # 回待機 → 冷卻
                self.state = "COOLDOWN"
                self.cooldown_frames = max(1, int(0.15 * fps))
                self.rep_peak_deg = 0.0
                self.hold_frames = 0
                self.raising_frames = 0

  



        return deg, self._dbg(fps, h_heel=h_heel, h_toe=h_toe)

    # ---------- helpers ----------
    def _idxs(self):
        if self.side == "left":
            return L_TOE, L_HEEL
        else:
            return R_TOE, R_HEEL

    def _ema(self, v):
        if self.ema_deg is None:
            self.ema_deg = v
        else:
            self.ema_deg = self.EMA_ALPHA * v + (1 - self.EMA_ALPHA) * self.ema_deg
        return self.ema_deg

    @staticmethod
    def _median_point(points):
        xs = sorted(p[0] for p in points); ys = sorted(p[1] for p in points)
        mid = len(points) // 2
        return (xs[mid], ys[mid])

    @staticmethod
    def _dist(a, b):
        return math.hypot(a[0]-b[0], a[1]-b[1])

    def _dbg(self, fps, h_heel=None, h_toe=None, suspended=False):
        return {
            "side": self.side,
            "state": self.state,
            "deg": None if self.ema_deg is None else round(self.ema_deg, 2),
            "peak": round(self.peak_deg, 2),
            "hold_s": round(self.hold_frames / max(1.0, fps), 2),
            "ok": self.rep_success,
            "ng": self.rep_fail,
            "baseline_ready": self.baseline_ready,
            "L_px": None if self.L is None else round(self.L, 1),
            "h_heel": None if h_heel is None else round(h_heel, 2),
            "h_toe": None if h_toe is None else round(h_toe, 2),
            "suspended": suspended,
        }
        
# ================================================================= #
# =================== 請用這段【最終修正版】取代舊的 Class =================== #
# ================================================================= #

class CalfRaiseDetector:
    """
    改版：以基準腳底線 + heel 垂距角 θ=atan2(h/L)。
    成功 20–90° 且連續 ≥3 秒；失敗 10–<20°（且 RAISING 至少 MIN_RISE_FRAMES 幀）。
    """
    def __init__(self, A_min=20.0, A_max=90.0, hold_seconds=3.0, ema_alpha=0.35, standard_deg=None):
        self.A_min = float(A_min)
        self.A_max = float(A_max)
        self.hold_seconds = float(hold_seconds)
        self.alpha = float(ema_alpha)
        self.standard_deg = float(standard_deg) if (standard_deg is not None) else (0.5 * (self.A_min + self.A_max))
        self.side = None
        self._t_last = None
        self.calf = None
        self.fixed_fps = None   # ← 新增：若外部已知來源 fps，就填進來用它
        self.last_info = {'state': 'CALIB', 'deg': None, 'hold_s': 0.0, 'ok': 0, 'ng': 0, 'baseline_ready': False, 'L_px': None}


    def _dt(self):
        t = time.perf_counter()
        if self._t_last is None:
            self._t_last = t
            return 1/30.0
        dt = max(1e-6, t - self._t_last)
        self._t_last = t
        return dt

    def _pick_side(self, ld):
        def score(side):
            s = 0.0
            for k in (f"{side}_heel", f"{side}_foot_index"):
                p = ld.get(k)
                s += (p[3] if (p and len(p) >= 4 and p[3] is not None) else 0.0)
            return s
        return "left" if score("left") >= score("right") else "right"

    def process_frame(self, landmarks, frame, W, H):
        try:
            ld = get_landmark_dict(landmarks)
            if self.side is None:
                self.side = self._pick_side(ld)
                self.calf = CalfSide(self.side,
                     success_min_deg=self.A_min, success_max_deg=self.A_max,
                     fail_min_deg=5.0, fail_max_deg=7.4,     # ←← 正確
                     hold_seconds=self.hold_seconds, ema_alpha=self.alpha,
                     idle_threshold=8.0,
                     enforce_toe_ground=True,
                     calib_frames=45, calib_jitter_px=6.0)
            # 若外部有提供固定 fps（攝影機或影片檔），優先用它；否則退回 Δt 估計
            fps_used = (self.fixed_fps if (self.fixed_fps and self.fixed_fps > 0) 
                        else 1.0 / max(1e-3, self._dt()))
            deg, info = self.calf.feed(landmarks, W, H, fps_used)
            self.last_info = info if isinstance(info, dict) else self.last_info

            # 上方主 HUD
            total = info['ok'] + info['ng']
            rate = (info['ok'] / total * 100.0) if total > 0 else 0.0
            angle_txt = '--' if (info.get('deg') is None) else f"{info['deg']:.1f}°"
            status_lines = [
                f"提踵 (基準腳底線→heel 垂距角) side={self.side or '-'}",
                f"狀態: {info['state']}    保持: {info['hold_s']:.1f}s / {self.hold_seconds:.0f}s",
                f"角度 θ=atan2(h/L): {angle_txt}    區間: {self.A_min:.0f}–{self.A_max:.0f}°",
                f"基準就緒: {info['baseline_ready']}    L(px)={info['L_px']}",
                f"成功: {info['ok']}    失敗: {info['ng']}    總數: {total}    成功率: {rate:.1f}%",
            ]
            frame = draw_text_block(frame, status_lines, anchor='lt', margin=24, color=(255,255,255), max_font_px=18, min_font_px=14, line_gap=6, stroke=2)

            # 左下角固定狀態列 + 進度條
            state_txt, hold_s, need_s = info.get('state', '?'), info.get('hold_s', 0.0), float(self.hold_seconds)
            fixed_lines = [f"STATE: {state_txt}  HOLD: {hold_s:.1f}s / {need_s:.0f}s  ANGLE: {angle_txt}"]
            frame = draw_text_block(frame, fixed_lines, anchor='lb', margin=24, color=(255,255,255), max_font_px=18, min_font_px=14, line_gap=4, stroke=2)
            bar_w, bar_h = 220, 10
            px0, py0 = 24, H - 24 - 20
            prog = max(0.0, min(1.0, (hold_s / need_s) if need_s > 0 else 0.0))
            cv2.rectangle(frame, (px0-2, py0-2), (px0 + bar_w + 2, py0 + bar_h + 2), (0,0,0), -1)
            cv2.rectangle(frame, (px0, py0), (px0 + bar_w, py0 + bar_h), (80,80,80), -1)
            cv2.rectangle(frame, (px0, py0), (px0 + int(bar_w * prog), py0 + bar_h), (0,255,0), -1)

            # 腳部細節繪圖
            if self.calf:
                if self.side == "left": toe_idx, heel_idx = 31, 29
                else: toe_idx, heel_idx = 32, 30
                toe, heel = landmarks[toe_idx], landmarks[heel_idx]
                toe_pt, heel_pt  = (int(toe.x*W), int(toe.y*H)), (int(heel.x*W), int(heel.y*H))
                cv2.circle(frame, toe_pt, 5, (0,255,255), -1)
                cv2.circle(frame, heel_pt, 5, (255,255,0), -1)
                if self.calf.baseline_ready:
                    ax, ay = self.calf.toe_base_px; bx, by = self.calf.heel_base_px
                    p_base_toe = (int(ax), int(ay)); p_base_heel = (int(bx), int(by))
                    cv2.line(frame, p_base_toe, p_base_heel, (0,200,0), 3)
                    cv2.circle(frame, p_base_toe, 6, (0,200,0), -1)
                    cv2.circle(frame, p_base_heel, 6, (0,200,0), -1)
                    deg_val = info.get('deg', None)
                    if deg_val is not None:
                        ABx, ABy = (bx - ax), (by - ay)
                        AB2 = float(ABx*ABx + ABy*ABy) if (ABx or ABy) else 1.0
                        APx, APy = (heel_pt[0] - ax), (heel_pt[1] - ay)
                        t = (APx*ABx + APy*ABy) / AB2
                        proj_x, proj_y = int(ax + t * ABx), int(ay + t * ABy)
                        cv2.line(frame, (proj_x, proj_y), heel_pt, (0, 255, 255), 3)
                        label = f"{deg_val:.1f}°"
                        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.8, 2)
                        x0, y0 = heel_pt[0] + 10, heel_pt[1]
                        cv2.rectangle(frame, (x0-4, y0-th-4), (x0+tw+4, y0+4), (0,0,0), -1)
                        cv2.putText(frame, label, (x0, y0), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255,255,255), 2)
        except Exception:
            pass
        return frame

    def draw_overlay(self, frame, W, H):
        info = getattr(self, "last_info", None) or {}
        angle_txt = "--" if (info.get('deg') is None) else f"{info['deg']:.1f}°"
        total = (info.get('ok',0) + info.get('ng',0))
        rate = (info.get('ok',0) / total * 100.0) if total > 0 else 0.0
        status_lines = [
            f"提踵 (基準腳底線→heel 垂距角) side={self.side or '-'}",
            f"狀態: {info.get('state','?')}    保持: {info.get('hold_s',0.0):.1f}s / {self.hold_seconds:.0f}s",
            f"角度 θ=atan2(h/L): {angle_txt}    區間: {self.A_min:.0f}–{self.A_max:.0f}°",
            f"基準就緒: {info.get('baseline_ready', False)}    L(px)={info.get('L_px', None)}",
            f"成功: {info.get('ok',0)}    失敗: {info.get('ng',0)}    總數: {total}    成功率: {rate:.1f}%",
        ]
        frame = draw_text_block(frame, status_lines, anchor='lt', margin=24, color=(255,255,255),
                                max_font_px=18, min_font_px=14, line_gap=6, stroke=2)
        state_txt, hold_s, need_s = info.get('state','?'), float(info.get('hold_s',0.0)), float(self.hold_seconds)
        fixed_lines = [f"STATE: {state_txt}  HOLD: {hold_s:.1f}s / {need_s:.0f}s  ANGLE: {angle_txt}"]
        frame = draw_text_block(frame, fixed_lines, anchor='lb', margin=24, color=(255,255,255), max_font_px=18, min_font_px=14, line_gap=4, stroke=2)
        bar_w, bar_h = 220, 10
        px0, py0 = 24, H - 24 - 20
        prog = max(0.0, min(1.0, (hold_s / need_s) if need_s > 0 else 0.0))
        cv2.rectangle(frame, (px0-2, py0-2), (px0 + bar_w + 2, py0 + bar_h + 2), (0,0,0), -1)
        cv2.rectangle(frame, (px0, py0), (px0 + bar_w, py0 + bar_h), (80,80,80), -1)
        cv2.rectangle(frame, (px0, py0), (px0 + int(bar_w * prog), py0 + bar_h), (0,255,0), -1)
        return frame

    def get_counts(self):
        if self.calf:
            ok, ng = self.calf.rep_success, self.calf.rep_fail
        else:
            ok, ng = 0, 0
        total = ok + ng
        return ok, ng, total

# ===============================
# UI：只保留 深蹲 / 提踵 兩項
# ===============================

def select_action_group():
    root = tk.Tk()
    root.title("動作識別系統（僅：深蹲 / 提踵）")
    root.geometry("420x300")
    root.configure(bg='#f0f0f0')

    title_label = tk.Label(root, text="請選擇要處理的動作", font=('Microsoft YaHei', 16, 'bold'), bg='#f0f0f0')
    title_label.pack(pady=16)

    selected_action = tk.StringVar(value="")
    selected_video_path = tk.StringVar(value="")

    # 深蹲
    squat_frame = tk.Frame(root, bg='#f0f0f0'); squat_frame.pack(pady=6)
    tk.Radiobutton(squat_frame, text="深蹲 (角度法)", variable=selected_action, value="squat_hip_height",
                   font=('Microsoft YaHei', 12), bg='#f0f0f0', indicatoron=False, selectcolor='#2196F3').pack()
    tk.Label(squat_frame, text="• 回合最低膝角達 95–135°，站回 ≥170° 算 1 次 \n 若只到 136–162° 就站回，判定為失敗\n• 以 ±20% 成功/失敗分級", font=('Microsoft YaHei', 10), fg='#666666', bg='#f0f0f0').pack()

    # 提踵
    calf_frame = tk.Frame(root, bg='#f0f0f0'); calf_frame.pack(pady=6)
    tk.Radiobutton(calf_frame, text="提踵 (地面參考)", variable=selected_action, value="calf_raise",
                   font=('Microsoft YaHei', 12), bg='#f0f0f0', indicatoron=False, selectcolor='#FF9800').pack()
    tk.Label(calf_frame, text="• toe→heel 相對地面角於區間內連續 ≥ 3 秒\n• 離開區間時計 1 下，±20% 分級", font=('Microsoft YaHei', 10), fg='#666666', bg='#f0f0f0').pack()

    video_frame = tk.Frame(root, bg='#f0f0f0'); video_frame.pack(pady=12)

    video_title = tk.Label(video_frame, text="請選擇要處理的影片檔案（若使用攝影機可略過）", font=('Microsoft YaHei', 11, 'bold'), bg='#f0f0f0')
    video_title.pack(pady=6)

    def select_video_file():
        file_path = filedialog.askopenfilename(
            title="選擇影片檔案",
            filetypes=[
                ("影片檔案", "*.mp4 *.avi *.mov *.mkv *.wmv"),
                ("MP4檔案", "*.mp4"),
                ("AVI檔案", "*.avi"),
                ("MOV檔案", "*.mov"),
                ("所有檔案", "*.*"),
            ],
        )
        if file_path:
            selected_video_path.set(file_path)
            filename = os.path.basename(file_path)
            video_display.config(text=f"已選擇: {filename}")

    select_button = tk.Button(video_frame, text="瀏覽影片檔案", command=select_video_file, font=('Microsoft YaHei', 11), bg='#2196F3', fg='white', padx=20, pady=8)
    select_button.pack()

    video_display = tk.Label(video_frame, text="尚未選擇影片檔案", font=('Microsoft YaHei', 10), fg='#666666', bg='#f0f0f0')
    video_display.pack(pady=6)

    def confirm_selection():
        if not selected_action.get():
            messagebox.showwarning("警告", "請選擇一個動作")
            return
        root.selected_action = selected_action.get()
        root.selected_video = selected_video_path.get()
        root.quit(); root.destroy()

    confirm_btn = tk.Button(root, text="確定開始處理", command=confirm_selection, font=('Microsoft YaHei', 12, 'bold'), bg='#4CAF50', fg='white', padx=20, pady=10)
    confirm_btn.pack(pady=12)

    root.mainloop()
    action = getattr(root, 'selected_action', None)
    video_path = getattr(root, 'selected_video', None)
    return action, video_path


# ==============================
# 即時攝影機錄影（僅兩動作）
# ==============================

def run_live_record(selected_action):
    cap = cv2.VideoCapture(0)
    stab = GlobalStab()
    if not cap.isOpened():
        print("Error: 無法開啟攝影機(0)")
        return

    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)

    frame_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 1280)
    frame_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 720)
    fps = cap.get(cv2.CAP_PROP_FPS)
    try:
        fps = float(fps)
        if fps <= 0 or fps > 120:
            fps = 30.0
    except Exception:
        fps = 30.0

    if selected_action == "squat_hip_height":
        detector = SquatKneeAngleThresholdDetector(
    stand_up_deg=170.0,
    succ_min_deg=95.0, succ_max_deg=135.0,
    fail_min_deg=136.0, fail_max_deg=162.0,
    ema_alpha=0.35, standard_deg=135.0
)
        action_name = "深蹲 (角度法)"
    elif selected_action == "calf_raise":
        # 先沿用先前的 1/2 角度縮放（俯視壓縮）
        detector = CalfRaiseDetector(A_min=7.5, A_max=45.0, hold_seconds=3.0, ema_alpha=0.35, standard_deg=15.0)
        detector.fixed_fps = fps   # ← 新增：使用攝影機回報或 fallback 後的 fps
        action_name = "提踵 (地面參考)"
    else:
        print(f"未知動作: {selected_action}")
        cap.release(); return

    ts = cv2.getTickCount()
    out_dir = os.path.join(os.getcwd(), "output"); os.makedirs(out_dir, exist_ok=True)
    outfile = os.path.join(out_dir, f"live_{action_name}_{int(ts)}.mp4")

    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out = cv2.VideoWriter(outfile, fourcc, fps, (frame_width, frame_height))

    pose = mp_pose.Pose(static_image_mode=False, model_complexity=1, smooth_landmarks=True,
                        min_detection_confidence=0.5, min_tracking_confidence=0.5, enable_segmentation=False)

    print(f"攝影機解析度: {frame_width}x{frame_height} @ {fps:.1f}fps")
    print(f"輸出檔案: {outfile}")
    print("按 Q 或 ESC 結束")

    while True:
        ret, frame = cap.read()
        # --- stabilize frame before pose detection ---
        _stab_mag = 0.0
        if ret:
            frame, _stab_mag = stab.stabilize(frame)
        if not ret:
            break

        image = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        image.flags.writeable = False
        results = pose.process(image)
        image.flags.writeable = True
        image = cv2.cvtColor(image, cv2.COLOR_RGB2BGR)

        if results.pose_landmarks:
            ld = get_landmark_dict(results.pose_landmarks.landmark)
            mp_drawing.draw_landmarks(image, results.pose_landmarks, mp_pose.POSE_CONNECTIONS,
                                    mp_drawing.DrawingSpec(color=(245,117,66), thickness=2, circle_radius=2),
                                    mp_drawing.DrawingSpec(color=(245,66,230), thickness=2, circle_radius=2))
            image = detector.process_frame(results.pose_landmarks.landmark, image, frame_width, frame_height)


        image = detector.draw_overlay(image, frame_width, frame_height)

        image = draw_text_block(image, [f"{action_name} - 即時錄影", "LIVE REC ● 按 Q/ESC 結束"],
                                 anchor='rb', margin=16, color=(0, 255, 0), max_font_px=20, min_font_px=14, line_gap=4, stroke=2)

        cv2.imshow("Rehab Live", image)
        out.write(image)

        key = cv2.waitKey(1) & 0xFF
        if key in (27, ord('q'), ord('Q')):
            break

    cap.release(); out.release(); cv2.destroyAllWindows()
    print(f"已儲存: {outfile}")


# ==============================
# 影片檔案處理主流程
# ==============================


def main():
    selected_action, video_path = select_action_group()
    if not selected_action:
        print("未選擇動作，程式結束")
        return

    # 問是否使用攝影機
    use_cam = False
    try:
        use_cam = messagebox.askyesno('輸入來源', '要使用攝影機即時錄影並保存檔案嗎？\n選「是」= 攝影機、選「否」= 使用影片')
    except Exception:
        use_cam = False

    if use_cam:
        run_live_record(selected_action)
        return

    # 若在選單未挑影片，這裡再問一次（避免沒挑到就結束）
    if not video_path:
        mode, value = choose_input_source()
        if mode != "video" or not value:
            print("未選擇影片檔案，程式結束")
            return
        video_path = value


    cap = cv2.VideoCapture(video_path)
    stab = GlobalStab()
    if not cap.isOpened():
        print(f"無法開啟影片: {video_path}")
        return

    # 問 start time（只有影片模式才問）
    start_text = "0"
    root = _ensure_tk_root()
    if root:
        try:
            s = simpledialog.askstring("起始時間", "輸入開始時間（秒數或 MM:SS / HH:MM:SS），預設 0：", initialvalue="0")
            if s:
                start_text = s
        except Exception:
            pass

    try:
        start_sec = parse_timecode(start_text)
    except Exception as _e:
        print(f"[warn] 起始時間解析失敗：{_e}，將從 0 秒開始")
        start_sec = 0.0

    if start_sec > 0:
        fps_probe = cap.get(cv2.CAP_PROP_FPS) or 0
        frame_idx = int(start_sec * fps_probe) if fps_probe > 0 else None
        ok_seek = False
        if cap.set(cv2.CAP_PROP_POS_MSEC, start_sec * 1000.0):
            ok_seek = True
        if not ok_seek and frame_idx is not None:
            ok_seek = cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)
        if not ok_seek and fps_probe > 0:
            target = max(0, frame_idx or 0); skipped = 0
            while skipped < target:
                ret_skip, _ = cap.read()
                if not ret_skip: break
                skipped += 1
            print(f"[info] 手動略過 {skipped} 幀以達到起始時間 {start_sec:.3f}s")
        else:
            print(f"[info] 起始時間已定位到 {start_sec:.3f}s")

    W = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 1280)
    H = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 720)
    # 決定輸出大小：高度上限 720
    out_W, out_H = W, H
    if H > 720:
        scale = 720.0 / H
        out_W = int(round(W * scale))
        out_H = 720
        
        
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    try:
        fps = float(fps)
        if fps <= 0 or fps > 120:
            fps = 30.0
    except Exception:
        fps = 30.0
    
    # 依動作建立 detector
    if selected_action == "squat_hip_height":
        detector = SquatKneeAngleThresholdDetector(
    stand_up_deg=170.0,
    succ_min_deg=95.0, succ_max_deg=135.0,
    fail_min_deg=136.0, fail_max_deg=162.0,
    ema_alpha=0.35, standard_deg=135.0
)
        action_name = "深蹲 (角度法)"
    elif selected_action == "calf_raise":
        detector = CalfRaiseDetector(A_min=7.5, A_max=45.0, hold_seconds=3.0, ema_alpha=0.35, standard_deg=15.0)
        detector.fixed_fps = fps     # ★★ 加這行：影片模式用「檔案固有 FPS」計秒 ★★
        action_name = "提踵 (地面參考)"
    else:
        print(f"未知動作: {selected_action}")
        return
    
    out_dir = os.path.join(os.getcwd(), "output"); os.makedirs(out_dir, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    base = os.path.splitext(os.path.basename(video_path))[0]
    outfile = os.path.join(out_dir, f"{base}_{action_name}.mp4")
    out = cv2.VideoWriter(outfile, fourcc, fps, (out_W, out_H))


    pose = mp_pose.Pose(static_image_mode=False, model_complexity=1, smooth_landmarks=True,
                        min_detection_confidence=0.5, min_tracking_confidence=0.5, enable_segmentation=False)

    print(f"輸入影片: {video_path}")
    print(f"輸出檔案: {outfile}")
    print("處理中...（按 Q/ESC 中止預覽）")
    
    
    while True:
        ret, frame = cap.read()
        # --- stabilize frame before pose detection ---
        _stab_mag = 0.0
        if ret:
            frame, _stab_mag = stab.stabilize(frame)
        if not ret:
            break
        
        # 若輸入過大，這裡縮到高度 720
        frame, cur_W, cur_H, _scaled = resize_to_max_height(frame, max_h=720)
        
        image = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        image.flags.writeable = False
        results = pose.process(image)
        image.flags.writeable = True
        image = cv2.cvtColor(image, cv2.COLOR_RGB2BGR)

        if results.pose_landmarks:
            ld = get_landmark_dict(results.pose_landmarks.landmark)
            mp_drawing.draw_landmarks(image, results.pose_landmarks, mp_pose.POSE_CONNECTIONS,
                                      mp_drawing.DrawingSpec(color=(245,117,66), thickness=2, circle_radius=2),
                                      mp_drawing.DrawingSpec(color=(245,66,230), thickness=2, circle_radius=2))
            image = detector.process_frame(results.pose_landmarks.landmark, image, cur_W, cur_H)

        
        # Always draw detailed overlay even if pose is temporarily missing
        image = detector.draw_overlay(image, cur_W, cur_H)
# 左下角底部統計 HUD（提踵/深蹲都顯示基本統計）
        ok, ng, total = detector.get_counts()
        rate = (ok / total * 100.0) if total > 0 else 0.0
        image = draw_text_block(
            image,
            [f"{action_name} - 計數結果", f"成功: {ok}｜失敗: {ng}｜總數: {total}｜成功率: {rate:.1f}%"],
            anchor='lb', margin=16, color=(0, 255, 0), max_font_px=18, min_font_px=14, line_gap=6, stroke=2
        )

        image = draw_text_block(image, [f"Stab: {_stab_mag:.1f}px"], anchor='rt', margin=16,
                            color=(255,255,255), max_font_px=16, min_font_px=12, line_gap=4, stroke=2)
        cv2.imshow("Rehab Video", image)
        out.write(image)
        key = cv2.waitKey(1) & 0xFF
        if key in (27, ord('q'), ord('Q')):
            break

    cap.release(); out.release(); cv2.destroyAllWindows()
    print(f"已儲存: {outfile}")

if __name__ == "__main__":
    main()
