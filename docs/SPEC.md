# TapFlow(觸控流)規格書

> v2.0 ‧ 2026-07-25 ‧ 參考同類產品「點擊助手」的互動模式修訂
>
> 開發慣例(Git Flow、commit 格式、分層與語系規則)見 [../CONTRIBUTING.md](../CONTRIBUTING.md)。

Android 按鍵精靈:錄製使用者的觸控操作並自動重播,**不需要 root**,靠 `AccessibilityService` 實現。

**技術棧:純 Kotlin + Jetpack Compose。** 曾考慮 Flutter/Dart,但錄製、重播、懸浮視窗、無障礙服務全都必須原生實作,Flutter 只剩「畫清單」的價值,換來雙倍膠水程式碼與跨 runtime 除錯成本,因此放棄。

**發佈方式:自用側載 APK**,不上 Google Play(Play 對 `AccessibilityService` 的政策要求「必須用於協助身障使用者」,自動化 / 巨集類 app 被下架比例很高)。

---

## 〇、產品形態一句話

> 螢幕上浮著一排工具列。按錄製,你的每個觸控被記下來,同時真的送到下層 app 讓畫面前進;
> 錄完,每一步變成螢幕上一個可拖曳的準心或曲線,你直接拖就改座標;
> 錄到需要輸入驗證碼那種地方,插一個**暫停點** —— 跑到這裡自動停,你手動處理完按繼續;
> 滿意了按儲存變成一個**片段**;多個片段可以串成一條**流程**;
> 按播放,它照著跑。

---

## 一、技術基礎與關鍵決策

### 1.1 懸浮視窗的實作路徑

工具列與面板**都是**懸浮視窗。「懸浮」有兩種實作路徑:

|  | `SYSTEM_ALERT_WINDOW` | `TYPE_ACCESSIBILITY_OVERLAY` |
|---|---|---|
| 是懸浮視窗 | 是 | 是,行為一模一樣 |
| 需要權限 | 要,且需跳系統設定頁手動開 | **不用** |
| 層級 | 一般 | 更高,可蓋在系統 UI 上 |
| 會被使用者關掉 | 會(「顯示在其他應用上層」開關) | 不會 |
| ROM 相容性 | 成熟穩定 | 小米 / 華為 / OPPO 偶有雷 |

**決策:主用 `TYPE_ACCESSIBILITY_OVERLAY`,掛載失敗時 fallback 到 `SYSTEM_ALERT_WINDOW`。**

- Manifest 宣告 `SYSTEM_ALERT_WINDOW`,但**不主動要求**
- `OverlayHost.attach()` 先試 accessibility overlay;捕捉到 `WindowManager.BadTokenException` / `InvalidDisplayException` 時檢查 `Settings.canDrawOverlays()`,沒有就引導使用者開,再用 `TYPE_APPLICATION_OVERLAY` 重試
- 兩條路徑共用同一份 View 與 `LayoutParams` 建構邏輯,只差 `type` 欄位

一般裝置只要開一個無障礙開關就能用;有雷的 ROM 也不會整個掛掉。兩種都不需要前景服務與常駐通知。

### 1.2 錄製方式:逐手勢補發

無障礙服務**拿不到**其他 app 的原始觸控座標(Android 只給元件層級的 `AccessibilityEvent`,沒有 x/y)。要拿座標只能用全螢幕 overlay 攔截,但攔截到的觸控就不會傳給下層 app。

因此採「盲錄 + 逐手勢補發」:

```
你按下 → overlay 記錄座標 → 你放開
  → overlay 暫時設為 FLAG_NOT_TOUCHABLE
  → 等一個影格,確保 window 重排完成
  → dispatchGesture 把剛剛那個手勢補發給下層 app
  → app 真的反應,畫面前進
  → 清除 FLAG_NOT_TOUCHABLE,等你錄下一步
```

補發期間 overlay 不可觸控,所以**不會有「自己注入的事件被自己攔截」的回音問題**。

用 `FLAG_NOT_TOUCHABLE` 切換而不是 `removeView` / `addView`:文件保證不可觸控的 window 不參與觸控分派,而且沒有 window 重建的閃爍與延遲成本。

代價:每步約 100~200ms 頓挫,快速連續滑動會失準 → 設定裡保留「純盲錄」開關。

### 1.3 面板遮擋問題:靠使用者自己避開

播放中工具列保持可觸控,但**注入的觸控只要落在工具列或播放面板矩形內就會被吃掉,那一步失效** —— Android 觸控分派規則,無法繞過。

**決策:不做自動避讓,靠使用者把工具列拖到空白處。** 自動避讓要處理「移動 window → 等重排 → dispatch → 移回」的時序,複雜度與出錯機會都不低,而把工具列拖到角落就解決了。

改為做三件成本很低的事:

1. **錄製時**,畫布上把工具列與面板矩形畫成斜線警示區,標「此區域無法錄製」
2. **編輯時**,若某步驟座標落在警示區內,該標記畫成黃色警示色
3. **README 與首次啟動說明**寫明這個限制

自動避讓列為 **M4 可選項**。

**備援中斷手段:音量鍵。** 音量下 = 暫停 / 繼續,長按音量下 1 秒 = 停止。需要 `canRequestFilterKeyEvents="true"` + `flagRequestFilterKeyEvents`。**只在播放中 / 暫停中攔截**,待機與錄製時不攔截,免得平常調不了音量。

### 1.4 只有一種暫停狀態

全 app 只有**一個**暫停機制:`PauseStep`(暫停點)。不管是重播跑到它、錄製時按 `⏸+` 插入它、使用者按暫停鍵、還是 `AwaitTextNode` 等不到目標而超時 —— 進入的都是同一個 `PAUSED` 狀態,只是提示文字不同。

刻意**不做**流程層的獨立暫停節點,也**不做** `Clip.pauseAtEnd`。要在片段之間停下來,就在前一個片段的尾端放一個 `PauseStep`。一個概念一個名字,執行引擎一套暫停 / 恢復邏輯。

### 1.5 編輯模式是切換式的

標記可拖曳的同時,下層 app 不可能還正常操作 —— 一個全螢幕 window 要嘛全吃觸控、要嘛全不吃,沒有中間值。

三種可能做法:

| 做法 | 體驗 | 風險 |
|---|---|---|
| **切換式編輯模式** ✅ | 多按一下 | 無,100% 可靠 |
| 每個標記各自一個小 window | 最好 | 錄 100 步 = 100 個 window,效能與 ROM 相容性未知 |
| `addOnComputeInternalInsetsListener` 設 touchable region | 最好 | 非公開 API,Android 9+ 非 SDK 限制可能擋掉 |

**決策:切換式。** 平常畫布 `FLAG_NOT_TOUCHABLE` 完全穿透(看得到標記、拖不動,下層 app 正常用);按工具列的 `✎` 切進編輯模式才整螢幕攔截、才能拖標記。

---

## 二、架構

單一 process,兩個入口共用 `Repo`(同 process,不需 IPC):

| 元件 | 職責 |
|---|---|
| `MainActivity` (Compose) | 權限引導、片段 / 流程清單、流程編排、全域設定 |
| `TapFlowService : AccessibilityService` | **唯一的執行主體**:掛 overlay、錄製、重播、攔截按鍵 |
| `Repo` (object + StateFlow) | 共享狀態與 JSON 持久化 |

服務負責一切的原因:它是系統綁定的,開關開著就活著不會被殺;只有它能 `dispatchGesture`,也只有它能掛 `TYPE_ACCESSIBILITY_OVERLAY`。

Overlay 全部用**原生 View 而非 Compose**:在 `WindowManager` 上跑 Compose 要自己補 `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner`,服務又沒有 Activity 生命週期,容易踩 recomposer 卡死。主 app UI 才用 Compose。

### 2.1 分層硬規則

| 套件 | 規則 |
|---|---|
| `data\` | **純 Kotlin,不得 `import android.*`,不得含任何使用者可見字串。** 目的是能在純 JVM 上做單元測試,而且所有使用者讀得到的文字都必須落在 `strings.xml`。想在 model 裡放 `label(): String` 就是違規 —— 那是 `text\` 的工作。 |
| `text\` | 使用者可見字串的格式化,吃 `Resources`。Compose 與原生 overlay 共用,所以它不歸在 `ui\` 底下。 |
| `engine\` | 唯一能呼叫 `dispatchGesture` 的地方。 |
| `overlay\` | 原生 View,不用 Compose。 |
| `ui\` | Compose,只有主 app 畫面。 |

### 2.2 語系

介面支援**英文(預設語系)**與**正體中文**,跟隨系統語言,不在 app 內提供切換。

- 所有使用者可見字串都在 `res\values\strings.xml` 與 `res\values-zh-rTW\strings.xml`,**兩邊必須同時更新**,key 順序保持一致以便對照
- 帶參數的字串一律用位置參數(`%1$d`、`%2$s`),不同語言詞序不同
- 這條規則也適用於畫在 overlay 上的字 —— 原生 View 一樣走 `context.getString()`
- 程式碼註解與 commit message 一律英文;規格書與 README 用正體中文

---

## 三、三個懸浮視窗

只用三個 window,每個承擔多種形態,避免 window 數量爆炸。

### 3.1 `ToolbarWindow` — 左側垂直工具列

可拖曳、自動吸附左右邊緣,位置持久化。三種形態:

**展開態**(由上到下)

| 鈕 | 待機 | 錄製中 | 播放中 | 編輯模式 |
|---|---|---|---|---|
| ▶ | 播放 | — | ⏸ 暫停 | 隱藏 |
| ⊙ | 錄製(接續到尾端) | ■ 停止錄製 | ■ 停止播放 | 隱藏 |
| ⏸+ | 插入暫停點 | 插入暫停點並停止錄製 | — | 隱藏 |
| ↩ | 復原上一步 | 復原上一步 | — | 隱藏 |
| ✎ | 進入編輯模式 | — | — | 離開編輯模式 |
| ＋ | 隱藏 | 隱藏 | 隱藏 | 新增點擊點 |
| − | 隱藏 | 隱藏 | 隱藏 | 刪除選取 |
| ⊕ | 開新的片段 | — | — | 隱藏 |
| 💾 | 儲存片段 | — | — | 儲存片段 |
| 📂 | 讀取片段 | — | — | 隱藏 |
| 👁 | 顯示密度三段切換 | 顯示密度 | 顯示密度 | 顯示密度 |
| ⚙ | 快捷設定面板 | 快捷設定 | 快捷設定 | 快捷設定 |
| ✕ | 關閉懸浮工具列 | — | — | 隱藏 |
| ∧ | 摺疊成 56dp 圓球 | 摺疊 | 摺疊 | 摺疊 |

**按鍵區可捲動。** 14 排按鍵在橫向螢幕放不下,所以按鍵區包在一個高度上限夾在螢幕內的 ScrollView 裡。**拖曳把手刻意留在捲動區外面** —— 放在裡面 ScrollView 會攔走垂直拖曳,工具列就再也拖不動,等於把一個摸不到的控制換成另一個。

**編輯模式換一整組按鍵**,不是把不相關的按鍵變灰 —— `＋` `−` 只在編輯模式出現,`▶` `⊙` `⏸+` `↩` `✕` 只在編輯模式外出現。詳見 §9。

**快捷設定面板(`⚙`)** —— 放「人在現場才會想改」的設定:迴圈次數、速度、逐手勢補發、螢幕常亮、黑幕、計時器、工具列大小與不透明度。

> 這是實機回饋加的。原本所有設定只在主 app 裡,結果要改個迴圈次數或開黑幕,就得切出目標 app、改一個數字、再切回來。判斷標準是**設定的效果是否需要當場看到**:需要的放面板;只在「新增動作時」才被讀取的(預設點擊 / 滑動時長)留在 app,那裡才有空間解釋。面板底部有連結直接跳到完整設定。
>
> 面板上的開關用自繪的 pill 而不是 `Switch`:`Switch` 會吃 app 的主題(淺色),放在這些深色面板上會像壞掉的。數值加減先對齊到刻度再 clamp —— 浮點反覆相加會飄,`0.7000001` 會卡在範圍邊界動不了。

**沒有獨立的「暫停錄製」鍵** —— 因為 `⊙` 是附加語意,`⊙ → ■ → 做別的事 → ⊙` 本身就是暫停與接續。少一顆鍵。

`↩ 復原上一步` 從 M1 就要有:錄製恢復是立即生效不倒數的(§10.1),誤錄在所難免,而完整的選取刪除排在 M2。實作只是工作區尾端 pop,可連按。

**摺疊態** — 56dp 圓球,顏色示意狀態(灰 待機 / 紅 錄製 / 綠 播放 / 黃 暫停),球面顯示當下唯一有意義的動作(`▶ 繼續` 或 `⊙ 繼續錄製`)。可拖曳,點一下展開。

除了手動按 `∧`,進入暫停態時會**自動**摺疊並移到螢幕上半部,把鍵盤區讓出來 —— 見 §10.3,這是人工介入能不能真的用的關鍵。

**`✕` 是關閉,不是縮小。** 按下去會把懸浮開關關掉,並 toast 告知回主 app 重新開啟。

> **這裡原本的設計是錯的,實機驗證後改掉。** 原規格是「`✕` 收起成一條 6dp 寬的邊緣細條,點一下彈回」。實機上 6dp 根本不是可觸控的尺寸,而且因為 `toolbarForm` 沒有被重設,按下 `✕` 之後**沒有任何辦法把工具列叫回來** —— 主 app 的開關關掉再開、甚至把無障礙服務關掉再開,回來的都還是同一條點不到的細條。
>
> 現在:摺疊只有圓球一種形態(56dp,高於 48dp 最小觸控目標);`✕` 語意改成誠實的關閉,主 app 的開關會同步反映;而且**每次掛載 overlay 都強制重設回展開態**並重新 clamp 位置,所以開關永遠會給出一個看得到、按得到的工具列。

**位置容錯** — overlay 掛上時,以及 view 量測完成後,都會把座標 clamp 回螢幕範圍內。存在偏好裡的位置可能來自不同尺寸的螢幕或不同方向,不夾一次會停在畫面外。

大小與不透明度由全域設定調整(§7)。

### 3.2 `TransportWindow` — 頂部橫向播放面板

窄扁形狀,預設貼頂部中央,可拖曳。刻意做窄,降低擋到手勢座標的機率。

```
┌──────────────────────────────────────────┐
│  ■   ⏸   │  🔁 02      ⤳ 07 / 23         │
│          │       00 : 01 : 34            │
└──────────────────────────────────────────┘
   停止 暫停   迴圈次數    步驟進度   計時器
```

只在錄製 / 播放 / 暫停時顯示,待機時隱藏。計時器可在設定關閉。

### 3.3 `CanvasWindow` — 標記畫布(一個 View 三種模式)

全螢幕。同一個 `CanvasView` 依模式改變行為:

| 模式 | 觸控 | 畫什麼 |
|---|---|---|
| **唯讀**(待機 / 播放) | `FLAG_NOT_TOUCHABLE` 完全穿透 | 所有步驟的標記;播放中高亮當前步驟;可選亞光黑幕 |
| **編輯**(按 `✎`) | 整螢幕攔截 | 標記 + 選取框 + 參數卡 + 警示斜線區 |
| **錄製**(按 `⊙`) | 整螢幕攔截 + 逐手勢補發 | 半透明底色 + 即時新增的標記 + 警示斜線區 |

亞光黑幕、參數卡、警示區都畫在這個 View 上,不需要額外 window。

---

## 四、工作區與儲存模型

**工作區(Workspace)= 記憶體中「目前螢幕上這組動作」。** 這是使用者直接操作的對象。

```
⊙ 錄製      新錄的動作【附加】到工作區尾端(不清空)
＋          在螢幕中心新增一個點擊點,套用全域預設時長
−           刪除選取的標記
拖曳標記     改座標
點選標記     彈參數卡:序號 / 類型 / 座標 / 時長 / 前置延遲 / 刪除
▶ 播放      直接跑工作區內容,不需要先儲存
💾 儲存      存成片段(Clip)—— 可覆蓋來源片段,或另存新片段並命名
```

**錄完不自動存。** 留在工作區讓你拖曳微調,滿意了才按 `💾`。這正是「可拖曳編輯」存在的理由:錄完馬上就能修。

### 4.1 存 / 讀 / 開新的

三顆按鍵,各自跳一個只做一件事的小畫面:

| 鍵 | 畫面 |
|---|---|
| `💾 儲存` | 名稱輸入框。載入自某片段時預設**覆蓋**它,勾選才另存新的 |
| `📂 讀取` | 片段清單,點一下載入。工作區有未儲存變更時先警告讀取會取代 |
| `⊕ 開新的` | 「會清空畫面上的 N 個動作」→ 清空 |

**做成 Activity 而不是 overlay 面板。** 命名需要輸入框,輸入框需要輸入焦點,而這裡的懸浮視窗一律 `FLAG_NOT_FOCUSABLE` —— 那正是暫停點能用的前提(不搶下層 app 的輸入法)。一個 Activity 三種模式,共用同一個透明主題與 manifest 條目。

`⊕ 開新的` 這顆的存在理由:`⊙` 是附加語意,所以在有它之前,錄完一段**沒有任何方式**開始下一段。

### 4.2 自動草稿

工作區每次變動就寫入 `filesDir/workspace.json`,**但只有 dirty(有未儲存變更)才會在服務重啟後還原。**

> 原本是無條件還原,結果存過的片段也會自己回來,看起來像 app 擅自開檔。草稿的用意是保護**還沒存過**的錄製 —— 存過的已經是片段了,要它回來就按 `📂 讀取`。

---

## 五、資料模型(三層)

### 5.1 最小動作層

多指手勢要支援,所以**統一成 stroke 模型**,不分 Tap / Swipe 類別:

```kotlin
@Serializable data class Pt(val x: Float, val y: Float, val t: Long)  // t = 相對該 stroke 起點的 ms

@Serializable data class Stroke(
    val points: List<Pt>,
    val startOffset: Long = 0,   // 相對整個手勢起點的 ms(多指時各指下手時間不同)
)

@Serializable sealed interface Step {
    val id: String          // 穩定識別,拖曳 / 選取 / 排序都靠它
    val delayBefore: Long   // 執行前要等多久
}

@SerialName("gesture") data class GestureStep(val id: String, val strokes: List<Stroke>, override val delayBefore: Long) : Step
@SerialName("global")  data class GlobalStep(val id: String, val kind: GlobalKind, override val delayBefore: Long) : Step
@SerialName("wait")    data class WaitStep(val id: String, val ms: Long, override val delayBefore: Long = 0) : Step
@SerialName("pause")   data class PauseStep(val id: String, val note: String = "", override val delayBefore: Long = 0) : Step

enum class GlobalKind { BACK, HOME, RECENTS, NOTIFICATIONS }
```

**統一 stroke 的理由**:點擊是 1 條 1 點的 stroke,滑動是 1 條多點的 stroke,雙指縮放是 2 條 stroke。重播引擎因此只有一條程式路徑(逐 stroke `GestureDescription.addStroke`),不用寫三份。UI 顯示與繪製時用 `describe()` / `markerOf()` 反推語意:

| 條件 | 顯示文字 | 螢幕標記 |
|---|---|---|
| 1 stroke、位移小、< 500ms | 點擊 (x, y) | 藍色十字準心 + 序號 |
| 1 stroke、位移小、≥ 500ms | 長按 (x, y) ‧ 800ms | 準心 + 外圈進度環 |
| 1 stroke、位移大 | 滑動 (x₁,y₁) → (x₂,y₂) ‧ 240ms | 橙圈起點 + 灰色粗曲線 + 箭頭終點 |
| 2+ strokes | 2 指手勢 ‧ 480ms | 同序號多條軌跡,色相不同 |

`delayBefore` 由錄製時前後兩次觸控的時間差算出,重播才有原本的節奏。

### 5.2 片段層(Clip)= 儲存下來的一組動作

```kotlin
@Serializable data class Clip(
    val id: String,
    val name: String,
    val steps: List<Step>,
    val screen: ScreenSpec,             // 錄製當下的螢幕條件
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable data class ScreenSpec(val width: Int, val height: Int, val rotation: Int)
```

### 5.3 流程層(Flow)= 把片段串起來(M3)

```kotlin
@Serializable data class Flow(
    val id: String,
    val name: String,
    val nodes: List<Node>,
    val loopCount: Int = 1,        // 0 = 無限循環
    val startDelayMs: Long = 3000, // 按播放後倒數,讓你切到目標 app
    val speed: Float = 1f,
    val createdAt: Long,
)

@Serializable sealed interface Node

@SerialName("clip")   data class ClipNode(val clipId: String, val repeat: Int = 1) : Node
@SerialName("wait")   data class WaitNode(val ms: Long) : Node
@SerialName("await")  data class AwaitTextNode(val text: String, val matchMode: MatchMode, val timeoutMs: Long = 15_000) : Node
@SerialName("global") data class GlobalNode(val kind: GlobalKind) : Node

enum class MatchMode { CONTAINS, EXACT, VIEW_ID }
```

**兩層迴圈**:`Flow.loopCount` 是整體重播次數,`ClipNode.repeat` 是該片段自己重複幾次。例:登入片段跑 1 次,點擊片段跑 500 次。

**序列化注意**:`Json { classDiscriminator = "type" }`。**不能用 `"kind"`**,會跟 `GlobalStep.kind` / `GlobalNode.kind` 撞名,kotlinx.serialization 會在執行期丟例外。

儲存:`filesDir/clips.json`、`filesDir/flows.json`、`filesDir/workspace.json`;overlay 位置與偏好放 SharedPreferences。

---

## 六、執行引擎

### 6.1 狀態機

```
IDLE
 │ play(工作區 或 片段 或 流程)
 ▼
COUNTDOWN(3→1) ──stop──▶ IDLE
 │
 ▼
RUNNING ──使用者按暫停 / 跑到 PauseStep / AwaitText 超時──▶ PAUSED
 │  ▲                                                                    │
 │  └──────────────── resume ──────────────────────────────────────────┘
 │                                                                       │
 └──stop──▶ IDLE ◀──stop(游標清除,下次從頭)──────────────────────────┘
 │
 ▼ 跑完
DONE ──▶ IDLE
```

「結束」= 回到待機、游標清空;下次按播放**從頭跑**,不是立刻自動重跑。

游標:

```kotlin
data class Cursor(val loop: Int, val nodeIndex: Int, val nodeRepeat: Int, val stepIndex: Int)
```

**實作要點:暫停不要 cancel job。** 用單一 coroutine Job 跑整個流程,暫停時在檢查點上 `pauseFlag.first { !it }` 掛起 —— 游標自然保留在區域變數裡,不需要序列化或還原。`stop` 才 `job.cancel()`。這比「cancel 後從序列化游標恢復」簡單一個數量級。檢查點放在每個 step 執行前、每個 node 進入前。

同時只能跑一個流程。

### 6.2 座標處理管線

每個 stroke 的座標依序經過:

```
原始座標
  → 解析度縮放(curW/recW, curH/recH)
  → 隨機位置抖動(整條軌跡套用同一個位移向量)
  → clamp 到螢幕範圍
  → GestureDescription.StrokeDescription
```

**抖動是整條軌跡一起偏移,不是每個點各自抖** —— 每點各自抖會讓滑動軌跡變成鋸齒。

時間軸同樣套用:`delayBefore` 與 stroke duration 各自 ±`jitterTimePercent`%,再乘上 `speed` 倍率。

### 6.3 螢幕方向與解析度

`Clip.screen` 記錄錄製當下的 width / height / rotation。播放前:

- **rotation 不同** → 進入 `PAUSED`,提示「此片段錄製於橫向,請轉回橫向後按繼續」
- **尺寸不同** → 線性縮放,UI 上標示「已縮放」

不做圖像比對,跨機型可靠度有限。

### 6.4 螢幕常亮與亞光

長時間掛機時螢幕一熄,`dispatchGesture` 全部無效。

- **常亮**:錄製 / 播放期間,`CanvasWindow` 的 `LayoutParams` 加 `FLAG_KEEP_SCREEN_ON`,結束移除。零權限、零額外宣告。
- **亞光**:設定開啟後,`CanvasView` 在唯讀模式下畫一層黑色 `alpha = dimAlpha` 的全螢幕矩形。因為播放時畫布是 `FLAG_NOT_TOUCHABLE`,**不會吃掉注入的手勢**。工具列與播放面板在其上方仍可見可按。

亞光會讓你看不到目標 app,只適合純掛機 —— UI 上要說明。

---

## 七、全域設定

```kotlin
@Serializable data class Settings(
    // 新增動作時套用的預設值
    val defaultGapMs: Long = 50,        // 動作間隔
    val defaultTapMs: Long = 75,        // 輕觸並按住
    val defaultSwipeMs: Long = 300,     // 滑動持續
    val defaultPinchMs: Long = 3000,    // 兩指縮放持續

    // 執行
    val defaultLoopCount: Int = 1,      // 0 = 直到按停止
    val speed: Float = 1f,
    val startDelayMs: Long = 3000,

    // 隨機抖動
    val jitterRadiusPx: Int = 0,        // 0 = 關閉,上限 150
    val jitterTimePercent: Int = 0,     // 延遲與時長 ±N%

    // 錄製
    val replayEachGesture: Boolean = true,  // 逐手勢補發;關掉就是純盲錄
    val replayDelayMs: Long = 80,           // 補發後等目標 app 動畫的時間

    // 外觀
    val uiScale: Float = 1f,            // 0.7 ~ 1.5
    val uiOpacity: Float = 1f,          // 0.3 ~ 1.0
    val showTimer: Boolean = true,
    val markerDensity: MarkerDensity = MarkerDensity.RECENT,

    // 螢幕
    val keepScreenOn: Boolean = true,
    val dimOverlay: Boolean = false,
    val dimAlpha: Float = 0.85f,
)

enum class MarkerDensity { ALL, RECENT, HIDDEN }
```

主 app 提供「恢復成預設值」。

---

## 八、標記與視覺回饋

### 8.1 標記樣式

| 類型 | 樣式 |
|---|---|
| 點擊 | 藍色十字準心,中央白底圓 + 序號 |
| 長按 | 準心外加一圈進度環,序號旁標時長 |
| 滑動 | 橙色圓圈(起點,帶序號)+ 灰色粗曲線 + 終點箭頭 |
| 多指 | 同序號多條軌跡,`3a` / `3b` 區分,色相不同 |
| 暫停點 | 沒有座標,螢幕上不畫標記;只出現在步驟文字清單,與序號連接虛線上的一個小 ⏸ 節點 |
| 選取中 | 橙色高亮 + 四角框 |
| 落在警示區 | 整個標記轉黃 |
| 播放中的當前步 | 放大 1.3 倍 + 脈動 |

序號之間畫細虛線連接,看得出操作路徑順序。

### 8.2 顯示密度(`👁` 三段循環)

| 段位 | 螢幕標記 | 步驟文字清單 |
|---|---|---|
| 全部 | 全部序號都留著 | 顯示 |
| 精簡(預設) | 只留最近 10 個 | 顯示 |
| 隱藏 | 只留最近 1 個、900ms 淡出 | 收起 |

「隱藏」是為了需要看清楚下層 app 內容的情況。

### 8.3 步驟文字清單

工具列旁可捲動的半透明小視窗(畫在 `CanvasWindow` 上):

```
 8  點擊 (540, 1180)
 9  滑動 (540,1180) → (540, 620) ‧ 240ms
10  長按 (128, 402) ‧ 820ms
11  ⏸ 暫停點
```

最新一筆在底部,自動捲到底並高亮 800ms。高度上限約螢幕 30%。

### 8.4 繪製效能

`CanvasView` 是單一自訂 View,標記存成輕量 data class 清單,`onDraw` 走訪繪製。超過 200 個標記時只畫最近 200 個,文字清單不受此限。

---

## 九、編輯模式的互動

按 `✎` 進入後,整螢幕由畫布攔截。

| 操作 | 行為 |
|---|---|
| 點一下標記 | 選取,彈出參數卡 |
| 拖曳序號徽章 | 整個手勢平移 |
| 拖曳滑動的箭頭尖端 | 改變方向與長度,**繞起點做旋轉縮放**,所以曲線形狀會保留 |
| 點空白處 | 取消選取、收起參數卡 |
| 工具列 `＋` | 螢幕中心新增點擊點,套全域預設時長,並自動選取(參數卡直接開在剛建立的那一步上) |
| 工具列 `−` | 刪除選取的標記,後續序號自動遞補 |

**實作與原規格的差異(實作時的判斷,已生效):**

- **沒有 120ms 長按門檻。** 改用跟工具列同一套規則:放開時依淨位移判定是點選還是拖曳。M1 踩過的教訓 —— 一旦用「跨過 slop 就鎖定成拖曳」的寫法,手指晃一下再回來就會把 tap 吃掉。同一套規則兩處共用,行為一致。
- **只有兩個抓取點:序號徽章與滑動箭頭尖端。** 原規格另有「拖曳曲線本體整條平移」與「拖曳起點圓只改起點」。起點圓上就是序號徽章,把它定義成「移動整個手勢」最直覺;曲線本體不當抓取目標,hit-test 才不會有歧義。
- **警示區用斜線陰影,不是把標記塗黃。** 編輯模式下直接畫出工具列覆蓋的斜線區,比逐一判斷哪個標記落在裡面更直接,也順便涵蓋播放面板與參數卡。
- **參數卡是獨立的 window,不是畫在畫布上的圖。** 十來個控制項若要自己做 hit-test,geometry 的量遠超過收益,而且會跟標記的 hit-test 打結。代價是它跟其他 overlay 一樣 non-focusable,所以**暫停點的備註只能在主 app 編輯** —— overlay 拿焦點會搶掉下層 app 的輸入法。
- **編輯模式下不畫步驟文字清單。** 位置會跟參數卡重疊,而且此時要看的是標記本身。
- **編輯模式強制把顯示密度開到「全部」**,使用者自己的設定不動。看不到的東西沒辦法編輯。
- **工具列在編輯模式換一整組按鍵**,不是把不相關的按鍵變灰。播放與錄製在拖曳標記時沒有意義,`＋`/`−` 在編輯模式外也沒有意義,所以沒有任何一顆按鍵是「明明可以直接不顯示卻只變灰」。這讓工具列維持 10 排以內,而不是長到 13 排。

**參數卡**(畫在畫布上,不另開 window):

```
┌─ 步驟 7 ─────────────────┐
│ 類型   點擊               │
│ 座標   540, 1180   [重指定]│
│ 時長   75 ms       −  +   │
│ 前置延遲 320 ms    −  +   │
│                          │
│      [ 刪除 ]  [ 完成 ]   │
└──────────────────────────┘
```

「重指定」= 參數卡暫時隱藏,你在螢幕上點一下新位置。

**手動新增只做點擊點。** 曲線滑動與兩指手勢只能靠錄製產生(錄完可以拖端點微調)。手動畫曲線需要貝茲控制點 UI,成本與收益不成比例。

---

## 十、錄製流程

`CanvasView` 切到錄製模式,全螢幕攔截,半透明底色提示正在攔截。工具列與播放面板必須疊在畫布之上 —— 同型 overlay 的 z-order 依加入順序,所以進錄製時先確保畫布已加入,再把工具列 remove + add 一次。

一次手勢的生命週期:

```
ACTION_DOWN          記 downTime,開新 stroke
ACTION_POINTER_DOWN  開新 stroke(startOffset = now − downTime)
ACTION_MOVE          各 pointer 取樣,位移 > 4dp 才記(避免點數爆炸)
ACTION_POINTER_UP    該 stroke 收尾
ACTION_UP            全部收尾
  ├─ delayBefore = downTime − 上一手勢結束時間(上限 30s)
  ├─ 產生 GestureStep,【附加】到工作區尾端
  ├─ 加入標記與文字清單一筆
  └─ 若逐手勢補發開啟:
       ├─ 畫布加 FLAG_NOT_TOUCHABLE → updateViewLayout
       ├─ 等一個影格
       ├─ dispatchGesture 並 await callback
       ├─ delay(replayDelayMs) 讓目標 app 完成動畫
       └─ 清除 FLAG_NOT_TOUCHABLE
ACTION_CANCEL        丟棄該手勢
```

錄製開始**不倒數**(你人已經在目標 app);播放才倒數(你人在主 app 或工具列)。

### 10.1 暫停點

**使用者看到的,就只有這一件事:**

> 插一個暫停點。跑到這裡自動停。手動處理完,按繼續。

沒有選項、沒有對話框、沒有設定。按 `⏸+` 就插入,插完錄製自動停下讓你操作,做完按 `⊙` 接續。

**錄製暫停與重播暫停是同一件事的兩面** —— 錄的時候你手動做,播的時候你也手動做。所以「插入暫停點」這一個動作同時定義了這兩個時刻。

```
錄製中,按 ⏸+
  → 插入 PauseStep,錄製停止,觸控放行
  → 你真的輸入驗證碼、真的按下一步,畫面真的前進
  → 按 ⊙ 接續錄後面的步驟

重播中,跑到 PauseStep
  → 停下來,觸控放行
  → 你真的輸入驗證碼
  → 按 ▶ 繼續,接著跑後面的步驟
```

`⏸+` 連帶停止錄製的理由:插它的唯一原因就是接下來要手動做事。純粹想暫停而不插點(滑錯了要滑回來、要看個通知)按 `■` 就好。

**在非錄製狀態按 `⏸+`**:有選取的標記就插在它後面,沒有就插在尾端。

**暫停點不畫螢幕標記** —— 它沒有座標。只出現在步驟文字清單,以及序號連接虛線上的一個小 ⏸ 節點。

`PauseStep.note` 預設空白、插入時不問。一個腳本裡有好幾個暫停點時容易忘記各自是幹嘛的,事後在主 app 想填再填;空白就顯示「已暫停,請手動處理後按繼續」。

### 10.2 實作註記:讓暫停點真的能用的三件事

這三件是**實作需求,不是功能** —— 使用者永遠不會看到它們,也不該在 UI 上出現任何對應的開關。但少了任何一件,上面那顆按鍵就是壞的。

**(1) 觸控必須真的放行。** 暫停時 `CanvasWindow` 設 `FLAG_NOT_TOUCHABLE`。這個 flag 的語意是「這個 window 永遠收不到觸控」,所以觸控**穿透下去**給下層 app 與輸入法 —— 名字容易誤讀成「鎖住觸控」,實際是相反的。提示條也畫在這個畫布上,所以它純粹是視覺的,不攔任何東西。

**(2) 工具列必須把鍵盤區讓出來。** 畫布穿透只解決全螢幕那一層,真正會擋住你打字的是工具列:11 顆鍵約佔螢幕 40% 高度、貼著左緣往下延伸,**必然壓在鍵盤的 Q / A / Z 那一整排上面**。而且 `TYPE_ACCESSIBILITY_OVERLAY` 的層級**高於 `TYPE_INPUT_METHOD`**,是工具列蓋住鍵盤,不是反過來。你按 A,按到的是工具列的某顆鍵。

所以進入暫停時,工具列**自動**摺疊成一顆 56dp 小球並移到螢幕上半部(y < 40% 高),球面只顯示「繼續」。仍可拖曳,萬一它還是擋到。按繼續後自動回復原本的展開形態與位置。

暫停時你只需要「繼續」這一個功能,11 顆鍵全都沒意義,摺疊成球是最合理的形態,順便把鍵盤區清空。

例外:按 `■` 單純停止錄製時**維持展開** —— 你可能是要存檔、進編輯模式、加點,需要整排按鍵。要讓位自己按 `∧`。

**(3) 時間軸不能被汙染。** 你打驗證碼花了 30 秒,恢復錄製後第一個手勢若照實算 `delayBefore = 30000ms`,重播時就會莫名其妙卡 30 秒。所以每次恢復錄製時,把「上一手勢結束時間」重設為恢復的那一刻,恢復後第一個手勢的 `delayBefore` 一律取 `settings.defaultGapMs`。

**恢復錄製立即生效,不倒數。** 靠 `↩ 復原上一步` 救濟誤錄,比讓使用者每次都等 3 秒划算。按 `⊙` 那一下點在工具列上,工具列吃掉了,畫布收不到,不會被錄進去。

**M4 可選優化**:用 `AccessibilityService.getWindows()` 找到 `TYPE_INPUT_METHOD` 的實際 `boundsInScreen`,精準閃避而不是固定移到上半部。要多開 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`,收益不大,先不做。

### 10.4 錄製時的限制(UI 上要提示)

- 工具列、播放面板、步驟清單覆蓋的區域無法錄製 —— 拖走它們
- 錄製中轉螢幕方向 → 中止錄製並提示(座標系會亂掉)
- 快速連續滑動在補發模式下會失準 → 關掉「逐手勢補發」改純盲錄

---

## 十一、條件等待(M4)

需要 `canRetrieveWindowContent="true"`。

```
每 200ms:
  root = rootInActiveWindow ?: 下一輪
  BFS 走訪,比對 text / contentDescription / viewIdResourceName
  命中 → 繼續流程
  超時 → 進入 PAUSED,提示「等不到『X』,請手動處理後按繼續」
```

走訪完呼叫 `node.recycle()`(API 33 起是 no-op,但要相容舊版)。

**輔助功能:掃描畫面文字。** 編輯器裡按「掃描當前畫面」→ 3 秒倒數 → 你切到目標 app → 服務抓出所有可見文字回傳清單讓你點選,不用手打字串。

**不做文字輸入。** 需要打字的地方一律插暫停點,流程停下來你自己打。

---

## 十二、檔案結構

```
D:\touch\
├─ .github\workflows\android.yml        GitHub Actions:編譯 debug APK 為 artifact
├─ docs\SPEC.md                          本規格書
├─ README.md  CONTRIBUTING.md  CHANGELOG.md
├─ .editorconfig  .gitattributes  .gitignore
├─ settings.gradle.kts  build.gradle.kts  gradle.properties
├─ gradle\wrapper\gradle-wrapper.properties
└─ app\
   ├─ build.gradle.kts   proguard-rules.pro
   └─ src\main\
      ├─ AndroidManifest.xml
      ├─ res\
      │   ├─ values\strings.xml          英文,預設語系
      │   ├─ values-zh-rTW\strings.xml   正體中文
      │   ├─ values\themes.xml  colors.xml
      │   ├─ xml\accessibility_service_config.xml
      │   ├─ drawable\  mipmap\
      └─ java\com\tapflow\android\
         ├─ App.kt                       Application:Repo.init
         ├─ MainActivity.kt
         ├─ data\                        純 Kotlin,不得 import android.*,不得含使用者可見字串
         │   ├─ Model.kt                 Pt / Stroke / Step / Clip / Flow / Node
         │   ├─ Settings.kt              全域設定
         │   ├─ Repo.kt                  StateFlow 狀態 + JSON 持久化
         │   └─ JsonConfig.kt            共用 Json(classDiscriminator = "type")
         ├─ text\
         │   └─ StepText.kt              使用者可見字串的格式化,吃 Resources
         ├─ engine\
         │   ├─ TapFlowService.kt        服務進出點、按鍵攔截、三個 window 的擁有者
         │   ├─ Workspace.kt             工作區狀態 + 自動草稿
         │   ├─ Recorder.kt              錄製狀態、逐手勢補發
         │   ├─ Player.kt                跑工作區 / 單片段(M1)
         │   ├─ FlowRunner.kt            流程狀態機 + 游標 + 暫停(M3)
         │   ├─ GestureDispatcher.kt     Step → GestureDescription、縮放、抖動
         │   └─ NodeFinder.kt            條件等待(M4)
         ├─ overlay\
         │   ├─ OverlayHost.kt           三個 window 的建立 / 層級 / fallback / 位置持久化
         │   ├─ ToolbarView.kt           左側工具列(展開 / 摺疊球 / 邊緣把手)
         │   ├─ TransportView.kt         頂部播放面板(進度 / 迴圈 / 計時器)
         │   ├─ CanvasView.kt            標記繪製 + 錄製攔截 + 編輯拖曳 + 亞光
         │   ├─ Markers.kt               標記模型、hit-test、繪製
         │   └─ ParamCard.kt             點選標記後的參數卡
         └─ ui\
             ├─ Theme.kt
             ├─ HomeScreen.kt            權限引導、工具列開關、片段清單
             ├─ SettingsScreen.kt        全域設定(M2)
             ├─ FlowEditorScreen.kt      流程編排(M3)
             └─ ClipDetailScreen.kt      片段詳情 / 改名 / 刪除 / 匯出
```

---

## 十三、實作分期

### M1 — 能錄能播(最大風險期)

服務 + 三個 window(含 overlay fallback、吸邊、摺疊、邊緣把手)+ 工具列 + 播放面板 + 計時器 + 錄製與逐手勢補發(單指)+ **`⊙` 接續錄製** + **`⏸+` 插入暫停點** + **`↩` 復原上一步** + 標記顯示(唯讀)+ 步驟文字清單 + `👁` 三段密度 + 工作區與自動草稿 + `💾` 存成片段 + 播放工作區 / 片段 + 迴圈次數 + 暫停 / 停止 + 音量鍵備援 + 螢幕常亮 + GitHub Actions。

**暫停點提前到 M1**(原本排 M3)—— 沒有它就錄不了任何「中間需要人工」的多層流程,而那正是這個 app 跟市面上同類產品的差異點。它也是全 app 唯一的暫停機制,所以後面各期都不用再蓋暫停相關的東西。

**驗收重點,這三點決定後面設計是否要調整:**

1. 逐手勢補發在實機上的手感 —— 頓挫是否可接受、畫面是否確實前進
2. `FLAG_NOT_TOUCHABLE` 切換是否可靠地讓畫布放行觸控
3. 錄 50+ 步時標記是否還看得清楚、觸控是否還流暢

### M2 — 螢幕上直接編輯

`✎` 編輯模式 + 標記 hit-test 與拖曳 + `＋` 新增點擊點 + `−` 刪除 + 參數卡(時長 / 延遲 / 重指定座標)+ 全域設定畫面(預設值、隨機抖動、大小 / 不透明度、亞光)+ 警示斜線區。

### M3 — 流程與人工介入

Flow 資料層 + 主 app 流程編排畫面 + `FlowRunner` 狀態機 + 兩層迴圈 + 播放中標記高亮當前步驟。

**流程層不新增任何暫停機制** —— M1 的 `PauseStep` 已經涵蓋。要在片段之間停下來就在前一個片段尾端放一個暫停點。若實測發現「同一片段重用在不同流程、有的要暫停有的不要」真的會發生,再給 `ClipNode` 加一個 `pauseAfter: Boolean`,別現在先蓋。

### M4 — 條件等待與多指

開 `canRetrieveWindowContent` + `AwaitTextNode` + 畫面文字掃描輔助 + 多指錄製與重播 + 片段 / 流程匯出匯入 JSON。

可選(視實測需要再決定):播放中面板自動避讓、`willContinue` 分段 stroke 提升滑動擬真度。

---

## 十四、已知限制

1. **部分遊戲 / 金融 app 會擋注入手勢**(檢查 `FLAG_WINDOW_IS_OBSCURED`、`getToolType`,或直接偵測無障礙服務啟用),重播會無效。無 root 無解。
2. `dispatchGesture` 時序精度受系統排程影響,抖動約 ±10~30ms。要求毫秒級精準的場景不適用。
3. 單一 stroke 內的速度變化不會完整保留(`GestureDescription` 沿路徑均速插值)。若實測滑動手感差,M4 可改用 `willContinue` 分段 stroke。
4. 純座標重播,不做圖像識別,換機型只能線性縮放。
5. 條件等待只讀得到 app 願意暴露給無障礙的節點;Canvas / 遊戲引擎自繪 UI 讀不到。
6. **工具列與播放面板覆蓋的區域,錄製時錄不到、播放時該步驟會被吃掉。** 請拖到空白處(§1.3)。
7. 銀行、密碼輸入等帶 `FLAG_SECURE` 的畫面會隱藏所有 overlay,注入手勢通常也會被擋。
8. **播放中偵測不到使用者手動碰螢幕**,所以沒有「碰到就自動暫停」的保護。無障礙服務拿不到其他 app 的觸控事件。
9. 不支援文字輸入自動化(§11)。

---

## 十四之二、系統層故障:手勢注入器卡住

**不是這個 app 能修的問題,但必須認得出來,否則會浪費大量除錯時間。**

`dispatchGesture` 依賴 `MotionEventInjector`,而它隨無障礙輸入過濾鏈一起安裝。`AccessibilityManagerService` 取用它時會等最多 `WAIT_MOTION_INJECTOR_TIMEOUT_MILLIS`(**1 秒**),等不到就直接回報手勢失敗。

**判準:失敗時間固定在 ~1010ms,與手勢長度無關。**

```
dispatch strokes=1 [938,1170..938,1170 106ms]
  -> CANCELLED after 1010ms of  106ms      ← 點擊
  -> CANCELLED after 1009ms of 1147ms      ← 長滑動,同一個時間點
```

一個 106ms 的點擊不可能「執行到一半被打斷」1010ms。看到這個就不要往程式碼裡找。

**處理順序**:toggle 無障礙服務 → **重開機(實測有效)** → 重新安裝。

> 這個狀況實際上耗掉了四輪除錯。誤導的關鍵有兩點:一是原本的錯誤訊息把「該 app 拒收注入手勢、需要 root」當成結論寫死,而那只是眾多可能之一;二是「上一個好的 commit」與「第一個壞的 commit」之間看似有因果,實際上那個 diff 完全沒有碰到手勢派送路徑,真正的變數是重新安裝 APK 導致服務重新綁定。
>
> 教訓:**當 diff 裡找不到任何相關的東西時,先懷疑環境而不是繼續猜程式碼。** 而讓環境自己說話的工具就是 §14.3 的診斷紀錄。

## 十四之三、診斷紀錄

主畫面最下方的「診斷紀錄」記下引擎的近期時間軸:擷取了幾個 stroke、畫布何時釋放、派送的座標範圍與時長、結果與耗時。按錄製或播放會清空重新計時,可一鍵複製(含版本號)。

存在理由:**手機上拿不到 logcat,而 toast 只能講一句話。** 沒有它的時候,每個假設都要出一個 build 才能驗證;有了它,一次就定案。

刻意做成純文字、有上限、沒有任何功能依賴它 —— 它是除錯工具,不是產品功能。

---

## 十五、驗證方式

**編譯**:推上 GitHub → Actions 跑 `gradle assembleDebug` → 下載 `app-debug.apk` artifact。

**實機驗收(每期)**

- **M1** — 開無障礙 → 工具列出現 → 按 `⊙` 錄「設定 → Wi-Fi → 返回」→ **確認錄製時畫面確實前進** → 螢幕上出現 1/2/3 準心且文字清單同步 → `👁` 三段切換正常 → `↩` 復原上一步 → 按 `■` 停止,做點別的事,再按 `⊙` 確認**接續**而非重來 → 按 `💾` 存成片段 → 按 `▶` 確認動作重現 → 頂部面板計時器與進度正確 → 音量鍵暫停 / 停止 → 收起成邊緣把手再點回來 → 殺掉服務再開,確認工作區草稿還在 → 連錄 50+ 步確認不卡
- **M1(人工介入專項,最關鍵的一項)** — 找一個需要輸入文字的畫面,錄到該步按 `⏸+` → 確認錄製自動停止、工具列自動摺疊成球並移到上半部 → **叫出鍵盤,把 Q / A / Z 那一整排每個鍵都按一次,確認沒有任何一鍵被工具列吃掉** → 真的輸入完 → 按球上的 `⊙` 接續錄後面幾步,確認工具列展開回原位 → 確認接續後第一步的前置延遲**不是**你打字花掉的那幾十秒 → 存檔重播 → 確認跑到該步會停下、提示條出現在頂部且不擋觸控 → 按繼續確認接續執行 → 換成「不顯示提示」再測一次
- **M2** — 按 `✎` 進編輯模式 → 拖動某個準心 → 播放確認新座標生效 → `＋` 手動加點 → 參數卡改時長與延遲 → 開隨機抖動 150px,播放時觀察落點確實散開 → 開亞光,確認注入手勢仍然有效(亞光層不吃觸控)
- **M3** — 分三次錄三個片段,其中一段尾端放暫停點 → 流程頁串起來 → 播放,確認跑到暫停點會停、按繼續後接續下一個片段 → 測「結束」後再播是從頭 → 測兩層迴圈次數
- **M4** — 插入「等待出現『登入成功』」節點,測命中與超時轉暫停兩條路徑 → 錄雙指縮放並重播 → 匯出 JSON 再匯入,確認一致
