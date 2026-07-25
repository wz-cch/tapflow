# Changelog

格式參考 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/),版本號遵循 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

## [Unreleased]

### Added
- 規格書 [docs/SPEC.md](docs/SPEC.md) 定稿(v2.0)
- 三層資料模型:`Step` / `Clip` / `Flow`,手勢統一為 stroke 模型以支援多指
- `PauseStep` 暫停點 —— 全 app 唯一的暫停機制
- 全域設定 `Settings`:預設時長、隨機抖動、外觀、螢幕常亮
- 語系資源:英文(預設)與正體中文
- GitHub Actions 建置流程,產出 debug APK artifact
- Git Flow 與 commit 慣例 [CONTRIBUTING.md](CONTRIBUTING.md)

### 尚未實作
`engine/`、`overlay/`、`ui/` 三層都還沒開始,專案目前**編不過** —— Manifest 引用的 `App` 與 `MainActivity` 尚未建立。詳見 [README](README.md#開發狀態)。
