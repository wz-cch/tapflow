# Contributing to TapFlow

專案語言慣例:**程式碼註解、commit message、branch 名稱一律英文;規格書與 README 用正體中文。**

---

## 1. Git Flow

分支模型遵循 Git Flow。

| 分支 | 用途 | 從哪來 | 合併回 |
|---|---|---|---|
| `main` | 只有實機驗過的版本。發佈時打 tag | — | — |
| `develop` | 整合分支,所有開發成果先進這裡 | `main` | — |
| `feat/*` `fix/*` `refactor/*` `docs/*` `chore/*` | 一個分支做一件事 | `develop` | `develop` |
| `release/*` | 發佈前的凍結與收尾 | `develop` | `main` + `develop` |
| `hotfix/*` | **已發佈版本**的緊急修補 | `main` | `main` + `develop` |

### 分支命名

**前綴跟 commit 的 type 同一套**(`feat` `fix` `refactor` `docs` `chore` …),而不是一律 `feature/`。一個分支做一件事,名字就說那件事:

```
feat/wait-countdown
fix/pin-the-collapse-button
refactor/expand-the-order-not-the-steps
docs/catch-up-with-what-we-actually-do
release/0.1.0
hotfix/0.1.1-toolbar-crash
```

一律小寫、用連字號、英文。名字寫**做了什麼**,不是寫改了哪個檔案。

> **`fix/` 不是 `hotfix/`。** `fix/` 從 `develop` 開、合回 `develop`,是還沒發佈的東西的修正 —— 目前所有的修正都是這一種。`hotfix/` 只用在「已經發佈出去的版本壞了」,它從 `main` 開、要合兩邊。挑錯會讓一個普通修正跑到 `main` 上,而 `main` 的意思是「驗過的」。

### tag 打在哪

**只有發佈才打 tag,不是每個 `main` 的 commit 都有。** `main` 上會有實機驗完之後從 `develop` 合過來的 commit;版本號真的往前走的時候才 `git tag -a v0.1.0`。

### 典型流程

開一個功能分支:

```bash
git switch develop
git pull
git switch -c feat/wait-countdown
```

做完後合回 `develop`(用 `--no-ff` 保留分支的形狀):

```bash
git switch develop
git merge --no-ff feat/wait-countdown
git branch -d feat/wait-countdown
```

**`push` 不在這串裡面,那是刻意的。** 這個專案的節奏是「做一批、出一個 APK、實機驗、然後才推」,所以 `develop` 常常會累積好幾個 commit 沒推 —— 那是正常狀態,不是忘了。

發佈:

```bash
git switch -c release/0.1.0 develop
# 更新 CHANGELOG.md、app/build.gradle.kts 的 versionName / versionCode
git commit -m "chore(release): prepare 0.1.0"

git switch main
git merge --no-ff release/0.1.0
git tag -a v0.1.0 -m "TapFlow 0.1.0"

git switch develop
git merge --no-ff release/0.1.0
git branch -d release/0.1.0
git push --all && git push --tags
```

`main` 永遠可以直接編出可安裝的 APK。

### 什麼時候可以合進 `main`

**實機驗過之後,不是寫完之後。** CI 綠燈只保證編得起來(§5)。所以 `develop` 落後 `main` 幾十個 commit 是預期中的:那些是還沒上機驗的東西。

---

## 2. 本機出 APK

CI 會出 artifact,但等它一輪比在本機建慢得多,而測試迴圈的瓶頸就是這個。

```bash
gradle assembleDebug -PbuildId=$(git rev-parse --short HEAD)
# app/build/outputs/apk/debug/app-debug.apk
```

**這個 repo 沒有 gradle wrapper**(CI 直接裝 Gradle),所以是 `gradle` 不是 `./gradlew`。

`-PbuildId` 就是主畫面顯示的版本號 —— `0.1.0+<sha>`,跟 CI 的 artifact 名字同一個規則。不帶的話會是 `0.1.0-local`,那就分不出手上裝的是哪一版了,而「你測的是哪一版」每問一次就是一趟來回。

> **每一次建置的 debug 簽章都不一樣,所以裝的時候可能要先反安裝。** 專案沒有 commit 任何 keystore,CI 也沒有 cache `~/.android` —— 所以這不是「本機 vs CI」的問題,**CI 自己前後兩次的簽章也不一樣**。裝不上去說套件衝突就是這件事。
>
> 而反安裝會清掉 SAF 的授權,所以裝完第一次要重新選一次資料夾。

---

## 3. Commit message

遵循 [Conventional Commits](https://www.conventionalcommits.org/)。**英文,現在式命令語氣,句首小寫,句尾不加句號。**

```
<type>(<scope>): <subject>

<body,選填,說明為什麼這樣改而不是改了什麼>

<footer,選填>
```

### type

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 修 bug |
| `refactor` | 不改變外部行為的內部調整 |
| `perf` | 效能改善 |
| `docs` | 只動文件(含 SPEC.md) |
| `style` | 排版、空白、命名,不影響行為 |
| `test` | 測試 |
| `build` | Gradle、依賴、版本號 |
| `ci` | GitHub Actions |
| `chore` | 其他雜項 |

### scope

用專案的套件名或關注區域:

```
data      資料模型與持久化
engine    無障礙服務、錄製、重播、手勢派送
overlay   懸浮視窗、標記畫布
ui        Compose 主畫面與編輯器
text      使用者可見字串的格式化
i18n      語系資源
docs      文件
ci        建置流程
```

### 範例

```
feat(engine): replay each gesture right after recording it

The accessibility service cannot read raw touch coordinates from other apps, so
recording has to intercept them with a full-screen overlay. That overlay would
otherwise swallow the touch, leaving the target app frozen on the same screen.
Dispatch the captured gesture back down with the canvas temporarily set to
FLAG_NOT_TOUCHABLE, so the screen advances and multi-step flows are recordable.
```

```
feat(overlay): collapse the toolbar when paused so the keyboard is reachable
fix(data): stop using kind as the JSON class discriminator
docs(spec): drop the flow-level pause node, PauseStep covers it
build: bump versionName to 0.1.0
```

**body 寫「為什麼」,不要寫「改了什麼」** —— diff 已經說明改了什麼。上面第一個範例就是標準:解釋為什麼非得這樣做,而不是列出動了哪幾行。

---

## 4. 程式碼慣例

### 註解與文件

- **所有註解與 KDoc 一律英文。** 不管檔案在哪一層。
- 註解寫「為什麼」,不是「做什麼」。程式碼本身應該說明做什麼。
- 特別值得註解的是**反直覺的技術決策**:例如為什麼用 `FLAG_NOT_TOUCHABLE` 而不是 `removeView`、為什麼 class discriminator 不能叫 `kind`。這種知識不寫下來,下一個人(或三個月後的你)一定會踩回去。

### 使用者可見字串

- **零硬編碼。** 所有使用者讀得到的文字都必須在 `res/values/strings.xml`,包含畫在 overlay 上的字。
- 新增字串時,`values/strings.xml`(英文)與 `values-zh-rTW/strings.xml`(正體中文)**必須同時更新**,並保持 key 的順序一致以便對照。
- 帶參數的字串一律用位置參數(`%1$d`、`%2$s`),不要用 `%d`、`%s` —— 不同語言的詞序不一樣。

### 分層規則

```
data/   純 Kotlin。不得 import android.*,不得含使用者可見字串。
        目的:可以在純 JVM 上做單元測試。
text/   使用者可見字串的格式化,吃 Resources。Compose 與 overlay 共用。
engine/ 無障礙服務、錄製、重播。唯一能呼叫 dispatchGesture 的地方。
overlay/ 原生 View。不用 Compose(服務沒有 Activity 生命週期,
        在 WindowManager 上跑 Compose 要自己補 lifecycle owner,容易卡死)。
ui/     Compose。只有主 app 畫面。
```

`data/` 不得依賴 Android 是硬規則。想在 model 裡放 `label(): String` 就是違規 —— 那是 `text/` 的工作。

### Kotlin 風格

- 官方 Kotlin coding conventions(`kotlin.code.style=official` 已設好)
- 縮排 4 空格,行寬 120,見 `.editorconfig`
- 顯式標示可見性只在需要時(`private`、`internal`),public 不用寫
- 優先用 `data class` 與不可變集合;需要可變狀態時用 `MutableStateFlow` 而不是 `var`

### `@Serializable` 的類別不可以宣告 private companion

**這一條是硬規則,而且它咬過人。** serialization 的 compiler plugin 會把 `serializer()` 掛在那個類別的 companion 上 —— 如果類別自己宣告了一個,就是**同一個** companion。所以:

```kotlin
@Serializable
data class Clip(...) {
    private companion object { const val COST_MS = 300L }   // ← 讓 Clip.Companion 變成 private
}
```

之後每一次 `encodeToString(clip)` 都會編譯成「從別的類別讀一個 private 靜態欄位」。**編譯得過**(plugin 把它產生在 inline 函式的 body 裡,沒有 synthetic accessor),然後在**真的會檢查的 runtime 上** `IllegalAccessError`。

它會表現成裝置專屬的 crash 而不是 bug:Android 11 放行,Android 10 不放行。而 stack frame 會指向一個**超過檔案長度的行號** —— 那是 inliner 標記「這段程式碼來自別的地方」的方式,也是唯一的線索。

要常數就放 **top-level `private const val`**。放在 companion 裡沒有任何好處。

---

## 5. Pull request

自用專案不強制走 PR,但如果開了:

- 標題用 commit 的格式:`feat(engine): ...`
- 說明裡回答三件事:**為什麼要改**、**怎麼驗證的**(實機測了什麼)、**有什麼還沒處理**
- 一個 PR 只做一件事。M1 那種大範圍的可以拆成多個 PR 進同一個 feature branch

---

## 6. 驗收

每個里程碑的實機驗收清單在 [docs/SPEC.md](docs/SPEC.md) 第十五節。CI 只保證編得起來,保證不了「錄製時畫面真的有前進」這種事。

**驗收發生在合進 `develop` 之後、合進 `main` 之前,不是合進 `develop` 之前。** 這跟原本寫的順序相反,而換掉是因為實際上做不到:驗一批東西要出一個 APK,出 APK 要有一個能建的 branch,而那個 branch 就是 `develop`。所以:

1. 做完合進 `develop`
2. 從 `develop` 出 APK,把要驗的項目寫進 `UNVERIFIED.local.md`(這個檔**不 commit**,靠 `.git/info/exclude` 排除)
3. 實機驗,結果回報 —— **沒過的比過的有價值**
4. 過了才合進 `main`

沒驗過的東西留在 `develop` 是正常的,那正是這兩條分支的分工。
