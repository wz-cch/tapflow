# 開發列表

> 已規劃但尚未動工的項目。**這裡只放「要做的」** —— 已知無解或不在 app 能修範圍的，開在 [GitHub Issues](https://github.com/wz-cch/tapflow/issues) 並標 `known-issue`。
>
> 里程碑分期見 [SPEC.md](SPEC.md) 第十三節。這份列表是使用者回饋來的，不完全對應 M3 / M4。
>
> **沒有階段釋出計畫** —— 整個開發完才算一個完整版本,所以這裡不排 milestone。

---

## 1. 插入「等待 N 秒」

**現況:資料層已經完成。** `WaitStep` 在 [Model.kt](../app/src/main/java/com/tapflow/android/data/Model.kt) 有定義,[Player.kt](../app/src/main/java/com/tapflow/android/engine/Player.kt) 會執行,[StepText.kt](../app/src/main/java/com/tapflow/android/text/StepText.kt) 會顯示成「等待 N 秒」,參數卡也能調它的前置延遲。

**缺的只有插入的入口。**

### 為什麼不直接用 `delayBefore`

每個 `Step` 本來就有 `delayBefore`,參數卡也能加減 —— 所以「第 1 步和第 2 步之間等 n 秒」理論上今天就能做到。但兩者刻意分開:

- `delayBefore` 是**錄製時錄到的節奏**。改它等於竄改錄到的東西
- `WaitStep` 是**你刻意插入的等待**,在步驟清單裡是獨立一行,自我說明

而且現有路徑實務上不好用:`DELAY_STEP_MS = 100`,加 5 秒要按 50 下,上限還被 `Timing.MAX_RECORDED_GAP_MS` 夾在 30 秒。

### 待做

- 工具列或參數卡上的插入入口
- **能直接打數字的秒數輸入畫面** —— 加減鍵不適合這個量級

輸入框需要焦點,而懸浮視窗一律 `FLAG_NOT_FOCUSABLE`,所以走 `WorkspaceDialogActivity` 那條路(見第 2 項)。

---

## 2. 暫停點的提示文字

**現況:資料層已經完成。** `PauseStep.note` 有欄位,`StepText` 有「有備註」與「空白時顯示預設提示」兩條路徑,`prompt()` 也寫好了。

**缺的只有編輯它的地方。** 使用者要求的「不填也可以」已經是現況。

### SPEC §9 的結論已經過時

那裡寫「暫停點的備註只能在主 app 編輯」,理由是 overlay 一律 `FLAG_NOT_FOCUSABLE`、不能有輸入框。

**但 `WorkspaceDialogActivity` 就是為了解決這個問題而生的**(存檔命名需要輸入框)。它是透明主題的 Activity,拿得到焦點。把備註編輯做成它的第四種模式即可 —— 已經驗證過的模式,成本很低。

### 待做

- `WorkspaceDialogActivity` 增加備註編輯模式
- 編輯模式選取暫停點時的入口(暫停點沒有螢幕標記,所以入口要放步驟文字清單或參數卡)
- 動工時順手修掉 SPEC §9 那句過時的話

**與第 1 項共用同一個輸入畫面,建議一起設計,不要做兩次。**

---

## 3. 指定從第幾步開始播放

長腳本除錯時不必每次從頭跑。

### 已拍板

- **只有第 1 圈從第 N 步起跑,第 2 圈起回到第 1 步。** 否則「從第 5 步開始跑 3 圈」會永遠跳過 1–4,那通常不是使用者的意圖

### 待決

- 起始步的 `delayBefore` 要不要跳過?**建議跳過**,改用 `startDelayMs` 倒數 —— 那個延遲是相對前一步的,而前一步沒跑

### 待做

- `Player` 接受起始索引。引擎這邊便宜:它是單一 coroutine 跑本地索引
- 入口建議放參數卡 —— 那已經是「選取後能對這一步做的事」的集中地

### 風險

從中間開始時,畫面狀態不一定對得上(腳本假設前面步驟已經執行過)。這要使用者自己負責,但 UI 上值得講一句。

---

## 4. 播放與暫停時的視窗配置

**已定案。可以動工。**

### 問題

同一個動作在兩個地方出現:

- **播放中** —— 工具列的 `▶`(暫停)、`⊙`(停止) 跟頂部面板的 ⏸ / ■ 完全重複
- **錄製中** —— 工具列的 `⊙`(停止錄製) 跟頂部面板的 ■ 完全重複

而這不只是視覺問題。SPEC §10.3 與 [README](../README.md) 已知限制 2:**工具列、播放面板、步驟清單覆蓋的區域,錄製時錄不到、播放時該步驟會被吃掉。** 每多一個懸浮物就多一塊死區。

### 決定

> **錄製情境用工具列(展開 → 球),播放情境用頂部面板(工具列完全退場)。兩者不混。**

| 狀態 | 工具列 | 頂部面板 |
|---|---|---|
| 待機 | 展開 | — |
| 錄製中 | 展開 | — |
| 錄製暫停(`⏸+` 後) | **球**(⊙ 繼續錄製) | — |
| 播放倒數 | — | 倒數 + ■ |
| 播放中 | — | ⏸ ■ 進度 計時 |
| 播放暫停 | — | ▶ ■ + 暫停提示文字 |
| 編輯 | 展開(7 顆) | — |

由此得到一個不變式,現在的行為沒有這個性質,而那正是到處重複的原因:

> **工具列與頂部面板永遠不會同時出現。**

### 為什麼錄製暫停維持用球,而不是改用面板

一度考慮過「所有暫停狀態都用面板」,但那會弄壞錄製暫停。

`onInsertPausePoint()` 呼叫 `stopRecording(collapseForInput = true)`,把模式設成 `Mode.IDLE` 並收成球。**`IDLE` 時面板本來就不顯示**,所以若把球也拿掉、又不補面板,錄製暫停後螢幕上會什麼都不剩 —— 打完驗證碼沒有任何東西可以按來接續。而那個流程是 SPEC §13 說的產品差異點。

照上表的分法就沒有這個問題:錄製側完全不動。而且**錄製暫停的鍵盤讓位是 [HANDOFF](HANDOFF.md) 第一節唯一標 ✅「摺疊讓出鍵盤區確認可用」的東西,不碰它就是零風險。**

連帶結論:**不需要為錄製暫停新增 `Mode`。** `⏸+` 繼續共用 `IDLE`。

### 錄製中不再顯示步數

面板在錄製時只有 ■ 與「錄製中 N 步」(沒有暫停鍵、沒有計時器)。■ 與工具列的 `⊙` 重複,而步數不需要補到別處:

- 標記本來就帶序號,`全部` 與 `精簡` 密度看得到
- `隱藏` 密度會留最近 1 個並淡出(SPEC §8.2),所以最後一步的序號仍然看得到

### 實作註記

- **面板**:`syncTransport()` 的條件從 `mode != Mode.IDLE` 改成 `EngineState.isReplaying` —— 那已經是 `PLAYING || PAUSED || COUNTDOWN`,語意剛好吻合
- **工具列**:**不要**新增 `ToolbarForm.HIDDEN`。改成在 `render()` 裡由 mode 推導:

  ```kotlin
  val hidden = mode == Mode.PLAYING || mode == Mode.PAUSED || mode == Mode.COUNTDOWN
  expanded.visibility = if (!hidden && form == EXPANDED) VISIBLE else GONE
  ball.visibility = if (!hidden && form == BALL) VISIBLE else GONE
  ```

  **理由是踩過的坑**:SPEC §3.1 記著「`✕` 收起後工具列再也叫不回來」,起因就是 `toolbarForm` 是存起來的狀態而且沒被重設。多一個存起來的 HIDDEN 就是把那個 bug 的可能性再種一次。由 mode 推導則不可能卡住 —— 播放一結束 mode 就變,工具列自己回來
- **`onPausedChanged()`** 的播放側分支(`collapseToBall(RESUME_PLAYBACK)`)可以移除;`stopRecording()` 那條 `RESUME_RECORDING` 保留
- 移除後 `BallIntent.RESUME_PLAYBACK` 就沒有使用者了,一併清掉

### 副作用(是好的)

播放與錄製各少一個懸浮物,直接縮小 README 已知限制 2 的死區。剩下的頂部面板本來就「刻意做窄,降低擋到手勢座標的機率」(SPEC §3.2)。

---

## 5. 支援 Shizuku

**排最後,價值待評估。**

Shizuku 提供一個 ADB 權限等級的行程,可以繞過 `AccessibilityService.dispatchGesture` 直接注入輸入事件。

### 會解決什麼、不會解決什麼

| 目前的問題 | Shizuku |
|---|---|
| [#1](https://github.com/wz-cch/tapflow/issues/1) 手勢注入器卡住(固定 ~1010ms) | ✅ **完全解決** —— 直接繞過 `dispatchGesture` |
| app 偵測無障礙服務是否啟用 | ⚠️ 部分 —— 注入可以不靠 a11y,但 overlay 要從 `TYPE_ACCESSIBILITY_OVERLAY` 退回 `SYSTEM_ALERT_WINDOW` |
| [#2](https://github.com/wz-cch/tapflow/issues/2) `filterTouchesWhenObscured` | ❌ **完全沒解** |

**#2 沒解的原因很重要:** `FLAG_WINDOW_IS_OBSCURED` 是關於**畫面上有沒有蓋著視窗**,跟手勢從哪裡注入無關。錄製時還是得掛全螢幕畫布才拿得到座標,還是會遮蔽,那些 app 還是會丟掉觸控 —— 連使用者自己的手指也一樣。

### 成本

- 非 root 的話,**每次重開機都要重新透過 ADB 啟動 Shizuku**。Android 11+ 可用無線偵錯自啟,但仍是門檻
- 使用者要額外安裝 Shizuku app

### 架構影響

小。`GestureDispatcher` 本來就是獨立類別,加一層注入抽象、後面掛兩種實作即可,不需要動 `Player` 或 overlay。

---

## 建議順序

1. **第 4 項**(視窗配置)—— **已定案,可以動工**。最獨立,而且順帶修掉一個真實的遮蔽問題
2. **第 2 項**(暫停點備註)—— 資料層已完成,複用既有模式
3. **第 1 項**(插入等待)—— 與第 2 項共用輸入畫面
4. **第 3 項**(指定起始步)—— 引擎便宜,語意已大致拍板
5. **第 5 項**(Shizuku)—— 大工程,等前面穩了再評估

---

## 規格寫了但沒實作的缺口

這些不是使用者回饋,是 [HANDOFF](HANDOFF.md) 第四節記的既有缺口,**不需要實機就能做**:

- **[SPEC §8] 解析度 / 方向不符時的提示與暫停** —— 目前只做線性座標縮放,方向對不上不會警告
- **[SPEC §1.3] 編輯時落在警示區內的標記畫成黃色** —— 斜線區與文字說明有了,個別標記的警示色沒有
- **M3 的流程清單 UI** —— `home_tab_flows` / `home_no_flows` 兩個 string 刻意留著
