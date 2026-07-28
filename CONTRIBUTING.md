# Contributing to TapFlow

專案語言慣例:**程式碼註解、commit message、branch 名稱一律英文;規格書與 README 用正體中文。**

---

## 1. Git Flow

分支模型遵循 Git Flow。

| 分支 | 用途 | 從哪來 | 合併回 |
|---|---|---|---|
| `main` | 只有正式發佈版。每個 commit 都對應一個 tag | — | — |
| `develop` | 整合分支,所有開發成果先進這裡 | `main` | — |
| `feature/*` | 單一功能或里程碑 | `develop` | `develop` |
| `release/*` | 發佈前的凍結與收尾 | `develop` | `main` + `develop` |
| `hotfix/*` | 正式版的緊急修補 | `main` | `main` + `develop` |

### 分支命名

```
feature/m1-record-replay
feature/m2-canvas-editing
feature/overlay-fallback
release/0.1.0
hotfix/0.1.1-toolbar-crash
```

一律小寫、用連字號、英文。`feature/` 底下若對應規格的里程碑,請帶上 `m1` ~ `m4` 前綴,方便對照 [docs/SPEC.md](docs/SPEC.md) 第十三節。

### 典型流程

開一個功能分支:

```bash
git switch develop
git pull
git switch -c feature/m1-record-replay
```

做完後合回 `develop`(用 `--no-ff` 保留功能分支的形狀):

```bash
git switch develop
git pull
git merge --no-ff feature/m1-record-replay
git push
git branch -d feature/m1-record-replay
```

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

---

## 2. Commit message

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

## 3. 程式碼慣例

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

---

## 4. Pull request

自用專案不強制走 PR,但如果開了:

- 標題用 commit 的格式:`feat(engine): ...`
- 說明裡回答三件事:**為什麼要改**、**怎麼驗證的**(實機測了什麼)、**有什麼還沒處理**
- 一個 PR 只做一件事。M1 那種大範圍的可以拆成多個 PR 進同一個 feature branch

---

## 5. 驗收

每個里程碑的實機驗收清單在 [docs/SPEC.md](docs/SPEC.md) 第十五節。**合進 `develop` 之前要跑過對應那一段**,並在 commit 或 PR 裡寫明結果 —— 包含沒過的項目。

CI 只保證編得起來,保證不了「錄製時畫面真的有前進」這種事。
