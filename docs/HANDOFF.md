# 交接筆記

> 給下一個接手的人（或下一個 session 的 AI）。
>
> 規格看 [SPEC.md](SPEC.md)、變更史看 [../CHANGELOG.md](../CHANGELOG.md)、開發慣例看 [../CONTRIBUTING.md](../CONTRIBUTING.md)。
> **這份文件只放那三份裡沒有、但走過一次才知道的東西** —— 踩過的坑、待決事項、以及「哪些功能真的在實機上驗過」。

---

## 一、目前狀態：什麼真的驗過了

「已實作」與「已驗證」差很多。這張表是實機驗證狀態，不是程式碼完成度。

| 功能 | 實機驗證 | 備註 |
|---|---|---|
| 錄製 + 逐手勢補發 | ✅ 桌面與一般 app | 有 `filterTouchesWhenObscured` 的 app 仍無解，見 README 限制 3 |
| 播放、迴圈、暫停 / 繼續 / 停止 | ✅ | |
| 暫停點 + 手動介入 | ✅ | 摺疊讓出鍵盤區確認可用 |
| 復原上一步、存片段 | ✅ | |
| Android 7.0 安裝與執行 | ✅ | minSdk 24，逐項查過不影響功能 |
| 存 / 讀 / 開新的（三個對話框） | 🧪 | 實作完成，使用者測過基本流程 |
| 編輯模式拖曳標記 | 🧪 | 拖曳平移驗過；**旋轉縮放（拖滑動箭頭尖端）沒驗過** |
| 參數卡加減時長 / 重新指定座標 | 🧪 | |
| 快捷設定面板 | 🧪 | |
| 音量鍵中斷 | ⬜ **完全沒驗過** | |
| 標記持續顯示 / 黑幕 | ⬜ 沒驗過 | 兩者都需要全螢幕遮罩，會觸發限制 3 |

M3（流程層）、M4（條件等待、多指手勢、匯出匯入）未動工。

---

## 二、待決事項

**目前沒有懸而未決的。** 下面兩項是前一輪留下的，都已處理，記在這裡是因為第一項**改寫了歷史**。

### 2.1 gitflow 的違規已清掉（歷史被改寫）

`develop` 上原本有兩個 commit（`c3ebcd1`、`538ad83`）**直接推上去，沒走 feature branch**，違反 [CONTRIBUTING.md](../CONTRIBUTING.md) 定的分支模型。

已補開 `feature/service-status` 收容那兩個 commit 並 `--no-ff` 併回，其後的 23 個 commit 因此全部換了 SHA。

> **如果你手上有改寫之前的 clone，它跟遠端已經對不起來了 —— 重新 clone，不要 merge。**
>
> 改寫只動 commit 物件。三個節點（原 `538ad83`、原 `c09d790`、原分支尖端）的工作樹都逐一 `git diff` 比對過，與原本 byte-identical；`git log --first-parent --no-merges` 也確認 develop 上沒有任何裸 commit。

### 2.2 `fix/gesture-cancelled` 已併回 `develop`

8 個 commit，CI 全綠，實機驗過。內容是診斷紀錄畫面 + 手勢注入器辨識 + 幾個 overlay 效能修正。

---

## 三、踩過的坑（每一個都花掉至少一輪 build）

這一節的價值最高。

> **這一節原本的前提「沒有本機 Android SDK，每個編譯錯誤都要等一輪 CI」已經不成立了** —— 見 §五，現在本機編得起來，增量建置 2 秒。
>
> 但只有 3.4 與 3.5 是編譯期的坑，本機建置確實讓它們變便宜了。**其餘每一個都是執行期或行為上的**，本機 SDK 幫不上忙 —— 它們仍然要裝到手機上、重新綁定服務、實際操作才看得出來，一輪就是好幾分鐘。所以這些坑重踩一次的成本仍然是實際的分鐘數。

### 3.1 診斷紀錄是除錯的第一手段，不是最後手段

手機上拿不到 logcat，toast 只能講一句話。曾經連續猜四輪都猜錯，每輪出一個 build；做了 `Diag`（主畫面最下方，可一鍵複製）之後**一次就定案**。

**規則：症狀不明確時先讓引擎自己講話，不要先猜。** 加一行 `Diag.log()` 的成本遠低於一輪錯誤的假設。

### 3.2 「上一個好的 commit」可能根本沒有因果關係

那四輪的真正原因是**手機的無障礙輸入過濾鏈卡住**（重開機就好），跟 APK 無關。但因為每次測試都要重裝 APK，而重裝會讓服務重新綁定，症狀看起來完美地跟著 commit 走。

**規則：當 diff 裡找不到任何相關的東西時，先懷疑環境。** 辨識方法見 [SPEC 十四之二](SPEC.md) —— 失敗時間固定 ~1010ms 且與手勢長度無關。

### 3.3 錯誤訊息不要把假設寫成結論

原本手勢被取消時的提示寫死「該 app 拒收注入手勢，需要 root」。那只是眾多可能之一，而它**讓整輪除錯往錯方向走**。現在改成列出實際可能原因，並帶連續失敗次數（偶爾一次正常，每次都失敗才是問題）。

### 3.4 Kotlin：靜態常數不能透過 instance 解析

`QuickSettingsView` 繼承 `ScrollView` 而不是 `LinearLayout`，於是 `VERTICAL` 解析失敗四次。要寫 `LinearLayout.VERTICAL`。

**改了父類別就要重新檢查所有未限定的常數。**

### 3.5 kotlinx.serialization 兩個坑

- `classDiscriminator` **不能設成 `"kind"`** —— 會跟 `GlobalStep.kind` 這個真實欄位撞名。現在用 `"type"`
- reified 的 `encodeToString` 需要 `import kotlinx.serialization.encodeToString`，否則會解析到兩參數的 member 版本然後編譯失敗

### 3.6 `FLAG_NOT_TOUCHABLE` 不等於「不算遮蔽」

觸控會穿透，但下層 app 收到的每個觸控**仍然帶 `FLAG_WINDOW_IS_OBSCURED`**。設了 `filterTouchesWhenObscured` 的 View 會直接丟掉 —— 包括使用者自己的手指。所以畫布只在錄製與編輯時掛上。

**試過兩種補救，兩種都是 regression，都已移除**（縮成 1 像素會讓全畫面座標偏移；搬移 window 會讓系統取消進行中的手勢）。詳見 CHANGELOG「第三 / 五 / 六輪」。**不要再試第三次而不先讀那三段。**

### 3.7 懸浮視窗一律 `FLAG_NOT_FOCUSABLE`

否則會搶掉下層 app 的輸入法，暫停點就失去意義了。**代價：overlay 不能有輸入框。** 所以存檔命名做成 `WorkspaceDialogActivity` 而不是 overlay 面板。

### 3.8 別在服務綁定期間寫 `serviceInfo`

`setServiceInfo` 會讓系統重算 user state，而那正是安裝輸入過濾鏈（`MotionEventInjector` 的擁有者）的時機。在綁定期間踩它就是在跟安裝競速。

也不要在讀不到 `serviceInfo` 時自己造一個空白的 —— 它的 capabilities 是 0，會直接抹掉 `canPerformGestures`。

### 3.9 `onServiceConnected()` 丟例外會讓服務被系統停用

使用者體驗是「工具列永遠打不開，畫面上毫無說明」。整個包在 `runCatching` 裡，降級啟動並把錯誤顯示在主畫面。

---

## 四、規格寫了但沒實作的東西

**這些是缺口，不是 bug。** 相關的 string resource 已經刪掉了，因為留著未使用的資源會讓人以為功能存在。

- **[SPEC §8] 解析度 / 方向不符時的提示與暫停** —— 目前只做線性座標縮放，方向對不上不會警告
- **[SPEC §1.3] 編輯時落在警示區內的標記畫成黃色** —— 斜線區與文字說明有了，個別標記的警示色沒有
- **M3 的流程清單 UI** —— `home_tab_flows` / `home_no_flows` 兩個 string 刻意留著，M3 馬上會用到

---

## 五、環境與流程

- **本機編得起來了。** SDK 在 `/opt/android-sdk`（platform-tools + `platforms;android-35` + `build-tools;35.0.0`），Gradle 8.11.1 在 `/usr/local/bin/gradle`，JDK 17。`local.properties` 指到 SDK，本來就在 `.gitignore` 裡，不會進版控

  ```bash
  ANDROID_HOME=/opt/android-sdk gradle assembleDebug
  ```

  冷啟（含抓完所有依賴）約 2 分 49 秒，之後增量 2 秒。**推之前先在本機編一次**，CI 一輪要 3 分鐘，而編譯錯誤在這裡 2 秒就知道了
- **仍然沒有 `gradle-wrapper.jar`**（二進位檔無法從純文字環境產生），所以用系統的 `gradle` 而不是 `./gradlew`。CI 也是。用 Android Studio 開過一次專案它就會補上
- **CI 仍然是產 APK 的地方**，也是唯一會被記錄下來的建置
- **版本識別**：CI 傳 `-PbuildId=${GITHUB_SHA}` → `versionName` → `BuildConfig.VERSION_NAME`，主畫面與診斷紀錄的複製內容都會顯示。**回報問題時一定要附這個**，否則分不清測的是哪一版
- **讀 CI 失敗的方法**：check-run annotation 不需要認證就能讀，raw Actions log 需要（會 403）。CI 有一個 failure-to-annotation 步驟就是為了這件事
- **未認證的 GitHub API 是 60 次/小時**，查 CI 狀態很容易用完

---

## 六、如果你想把「對話」也搬過去

Claude Code 的對話紀錄**不在專案資料夾裡**，在 `~/.claude/projects/<把路徑的 : 和分隔符都換成 - >/`。
`D:\touch` 對應 `D--touch`；Linux 上的 `/home/me/tapflow` 對應 `-home-me-tapflow`。

所以複製 `D:\touch` 不會帶走任何對話。要帶就是複製那個目錄到 server 上對應的新名字，然後在專案目錄下 `claude --resume`。

**但這份文件的存在就是為了讓你不需要那麼做。** 11M 的原始對話裡絕大部分是已經作廢的假設，真正的結論都在這裡跟 CHANGELOG 裡 —— 而且它們跟著 `git clone` 走，不依賴任何一台機器。
