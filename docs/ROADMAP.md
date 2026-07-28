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

**已完成**(都還沒實機驗過,見專案根目錄的 `UNVERIFIED.local.md`):

- ~~暫停點與等待成為可選取的標記~~ —— [CHANGELOG](../CHANGELOG.md)「暫停點刪不掉」
- ~~視窗配置 + 待機 / 錄製按鍵組~~ —— [CHANGELOG](../CHANGELOG.md)「工具列與播放面板不再同時出現」「每個模式各自一組工具列按鍵」。定案的結果寫進了 [SPEC §3.4](SPEC.md)

**還沒動工:**

1. **第 2 項**(暫停點備註)—— 資料層已完成,複用 `WorkspaceDialogActivity` 的既有模式
2. **第 1 項**(插入等待)—— 與第 2 項共用輸入畫面。`WaitStep` 的標記已經畫好了,只差插入入口
3. **第 3 項**(指定起始步)—— 引擎便宜,語意已拍板
4. **第 5 項**(Shizuku)—— 大工程,等前面穩了再評估

1 → 2 只是效率考量(共用輸入畫面),可以調整。

**編輯模式那一組按鍵還沒定。** 它跟第 1、2 項的插入入口綁在一起 —— 暫停點現在選得到了,所以「插在選取的後面」談得下去。目前編輯組維持原本的 7 顆,沒有動。

---

## 規格寫了但沒實作的缺口

這些不是使用者回饋,是 [HANDOFF](HANDOFF.md) 第四節記的既有缺口,**不需要實機就能做**:

- **[SPEC §8] 解析度 / 方向不符時的提示與暫停** —— 目前只做線性座標縮放,方向對不上不會警告
- **M3 的流程清單 UI** —— `home_tab_flows` / `home_no_flows` 兩個 string 刻意留著
