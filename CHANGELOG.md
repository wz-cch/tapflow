# Changelog

格式參考 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/),版本號遵循 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

## [Unreleased]

### Added — 基礎
- 規格書 [docs/SPEC.md](docs/SPEC.md) 定稿(v2.0)
- 三層資料模型:`Step` / `Clip` / `Flow`,手勢統一為 stroke 模型以支援多指
- 全域設定 `Settings`:預設時長、隨機抖動、外觀、螢幕常亮
- 語系資源:英文(預設)與正體中文
- GitHub Actions 建置流程,產出 debug APK artifact
- Git Flow 與 commit 慣例 [CONTRIBUTING.md](CONTRIBUTING.md)

### Added — M1 能錄能播
- `TapFlowService`:無障礙服務,三個懸浮視窗的擁有者
- 三個懸浮視窗:左側工具列(展開 / 圓球 / 邊緣把手)、頂部播放面板(進度與計時器)、全螢幕標記畫布
- Overlay 掛載走 `TYPE_ACCESSIBILITY_OVERLAY`,失敗才 fallback 到 `SYSTEM_ALERT_WINDOW`
- 錄製與**逐手勢補發** —— 錄完立刻送回下層 app,畫面才會前進
- `⊙` 接續錄製、`↩` 復原上一步、`💾` 存成片段(長按另存新片段)
- **暫停點**:插入即停止錄製並讓出觸控;暫停時工具列自動摺疊到螢幕上半部,把鍵盤區讓出來
- 工作區與自動草稿,服務重啟後還原
- 播放:迴圈次數、倒數、暫停 / 繼續 / 停止、音量下鍵備援(長按停止)
- 螢幕常亮、亞光黑幕、隨機位置與時間抖動、解析度線性縮放
- 標記顯示:序號準心、長按進度環、滑動軌跡與箭頭、`👁` 三段密度、步驟文字清單
- 主畫面:無障礙引導、工具列開關、片段清單(載入 / 改名 / 刪除)

### 尚待實機驗收
M1 的三個風險項目還沒在實機上驗過,見 [docs/SPEC.md](docs/SPEC.md) 第十五節:
逐手勢補發的手感、`FLAG_NOT_TOUCHABLE` 切換是否可靠、暫停時鍵盤是否真的按得到。
