# SpkPrac — 全双工实时语音对话 & 口语练习

Android 端实时语音对话应用：接入千问开放平台 **qwen3.8-omni-flash-realtime** 全双工实时模型（可自定义换成任何 OpenAI Realtime 兼容服务），支持普通语音聊天和口语练习两种模式，AI 语音带字幕、可开翻译，你说的话实时转写，练习结束自动生成点评报告。

> release APK **约 1.7 MB**（R8 + 资源收缩）。所有动效均为 Compose Canvas 手写，未引入任何动画/图表库。

仓库：<https://github.com/Shikaraw/SpkPrac-APP> · 下载：<https://github.com/Shikaraw/SpkPrac-APP/releases>

## Windows 桌面版

仓库里的 [`PC/`](PC/README.md) 是同功能的 Windows 移植版：

- 技术栈 **Tauri v2 + Rust + 原生 Web 前端 + Web Audio API**
- 单文件 **SpkPrac.exe，约 4 MB**，无需安装程序或运行库
- 侧边栏 + 双栏会话布局、深浅色主题、Aurora 流光背景、Canvas 语音球
- 麦克风与扬声器可分别选择；Chromium 的回声消除实测生效
- 发布使用独立标签 `pc-v*`，不会与 Android 的 APK 更新混淆

构建：

    cd PC
    cargo build --release

产物：`PC/target/release/SpkPrac.exe`

## 功能

### 聊天模式
- 免提全双工语音对话（服务端 VAD 自动断句），AI 回复即语音 + 实时滚动字幕
- **打断**：你一开口 AI 立刻停（可在设置中关闭）
- 麦克风静音、扬声器/听筒切换、随时改用文字输入
- **回复语言**可选：跟随你说的话，或固定为 15 种语言之一
- 断线自动重连（可关），重连后自动恢复上文（只重放最近 40 条，避免上下文膨胀）

### 口语练习模式
- 选语言（15 种）、水平（A1~C2）、话题（自由填写或快速选择）
- AI 只用目标语言交流，按你选择的频率（从不/偶尔/积极）口头即时纠错
- 挂断后自动生成中文练习报告（总体表现 / 语法与用词问题 / 更地道的表达 / 下次建议）
- 报告就地渲染 Markdown，可复制、可重新生成，并随会话一起落盘

### 界面与动效（本次重做）
- **Aurora 流光背景**：缓慢漂移的柔光色场，绘制层动画，不触发重组
- **VoiceOrb 语音球**：纯 Canvas 手绘，按状态变化——聆听时随麦克风电平脉动、思考时虚线旋转、说话时环形声波、失败时红色静止
- **实时电平波形**：控制区上方 26 根竖条跟随你的音量起伏
- 消息气泡进场动画、思考中三点跳动、状态胶囊、渐变模式卡、按压缩放反馈
- 页面切换淡入 + 轻微滑动
- 深色 / 浅色 / 跟随系统三档主题；字幕字号大中小三档
- 可在设置里**关闭动效**（省电 / 无障碍）与**触感反馈**

### 音色选择与声音复刻（v1.2.0 新增）

- 实时模型为 `qwen3.8-omni-flash-realtime` 时，音色提供官方 33 个系统音色下拉，同时保留自由输入
- **声音复刻**：上传一段 10–20 秒录音即可克隆专属音色，自动加入音色列表
- 音色管理页（状态/刷新/删除）+ 每音色详情页（可复制 voice_id）
- 删除音色后下拉列表同步消失；删除当前音色自动回落 `Tina`

### 音频输出设备选择（v1.1.0 新增）

之前版本把输出写死成「内置扬声器」，插耳机也不生效。现在会真正枚举系统的通讯设备并让你选：

- 会话页点喇叭图标 → 弹出设备列表，列出**外放 / 听筒 / 有线耳机 / 蓝牙 / USB** 全部可用输出，当前设备打勾
- 列表首项「自动（跟随系统）」= 不钉设备，把路由交还系统策略 —— **插拔耳机、连蓝牙会自动切过去**
- 设置页可设「默认音频输出」，会话开始时按此应用；会话内手动切换会记住为下次的默认
- 注册了 `AudioDeviceCallback`，会话进行中插拔耳机，设备列表和路由都会实时更新
- Android 12 以下没有可枚举的通讯设备 API，退化为「外放 / 听筒」两项，行为与旧版一致

### 版本管理与更新检查（v1.1.0 新增）

- 设置页「关于与更新」显示当前版本，可手动「检查更新」
- 「自动检查更新」：启动时后台检查一次，**每 24 小时最多一次**（时间戳持久化，不会每次开 App 都查）
- 检查走 GitHub Release API（`Shikaraw/SpkPrac-APP`），直连失败自动回退到 **gh-proxy 镜像**
- 有新版本时弹窗展示版本号、包大小、Release 说明（Markdown 渲染），可「下载 APK」（交给系统 DownloadManager，完成后点通知安装）或「打开 Release 页面」
- 「使用镜像加速」开关：GitHub 慢或不可达时，检查与下载都走 gh-proxy

### 其它
- **首页统计**：会话数、累计时长、连续练习天数；历史会话**搜索**
- **会话历史**：本地保存全部会话与报告，可回看、分享（纯文本）、**导出 Markdown 文件**、**复制全文**、删除（二次确认）
- 可配置服务商：实时接口地址 / 模型名 / 音色（预设 + 自定义）/ 文本接口 / 文本模型；协议风格支持「千问平台」与「OpenAI 兼容」；自带「测试连接」
- VAD 灵敏度与静音时长可调、ASR 语言可指定或自动、聊天人设指令可编辑
- **麦克风增益**（1.0~3.0x，声音小可放大）、屏幕常亮、中英双语界面
- 数据管理：一键导出全部会话、清空全部会话、清除 API Key

## 使用

1. 安装 APK，首次进入点右上角 **设置**
2. 填入千问平台 API Key（[qianwenai.com](https://www.qianwenai.com) 控制台获取），点 **测试连接** 确认可用
3. 回首页选 **聊天模式** 或 **口语练习**，授予麦克风权限，戴上耳机即可开始

默认配置（无需改动即可用）：

| 项 | 默认值 |
|---|---|
| 实时接口 | `wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/realtime` |
| 实时模型 | `qwen3.8-omni-flash-realtime` |
| 文本接口 | `https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions` |
| 文本模型（翻译/报告） | `qwen3.8-flash` |
| 音色 | `Tina` |

音频：上行 16kHz / 下行 24kHz，PCM16 单声道，使用 `VOICE_COMMUNICATION` 通道启用系统回声消除（AEC + 降噪）。

## 构建

命令行（本机已验证的组合）：

```bash
# JDK 17 + Gradle 8.11.1 + AGP 8.7.3
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot"
export GRADLE_USER_HOME="E:/gradle-home"   # 依赖缓存放 E 盘，防止 C 盘爆满
./gradlew assembleDebug     # app/build/outputs/apk/debug/   (~17MB)
./gradlew assembleRelease   # app/build/outputs/apk/release/ (~1.7MB, R8+资源收缩+签名)
```

release 使用仓库根目录 `keystore.properties` + `spkprac.jks` 签名（本地自签，密码见文件）。

在 Android Studio 打开时，请把 **Settings → Build Tools → Gradle → Gradle JVM** 设为 JDK 17（自带 JBR 是 25，跑不动 Gradle 8.x）。

## 项目结构

```
app/src/main/java/com/spkprac/app/
├── MainActivity.kt          # 导航（Screen sealed class + AnimatedContent 转场）
├── audio/AudioEngine.kt     # 采集/播放/AEC/通话路由/电平计算
├── core/Prompts.kt          # 聊天/练习/翻译/报告提示词
├── core/SessionViewModel.kt # 会话状态机：连接/重连/打断/PTT/翻译/报告
├── data/                    # DataStore 设置 + 会话历史(JSON) + 统计/导出
├── net/TextApi.kt           # OpenAI 兼容文本接口
├── rt/RealtimeClient.kt     # Realtime WebSocket 协议
└── ui/
    ├── components/          # AuroraBackground / VoiceOrb / Markdown / 通用组件
    ├── screens/             # 首页/会话/练习设置/设置/详情
    └── theme/               # 色板、形状、字号、Brand 渐变与 UiPrefs
```

字符串资源按模块拆分：`strings.xml`（公共）+ `strings_core/session/home/detail/settings.xml`，每个都有 `values-en/` 英文版本。

## 更新日志

### v1.2.0

- 新增音色预设下拉（Qwen3.8-Omni 官方 33 个音色）
- 新增声音复刻：创建 / 管理 / 详情 / 删除自定义音色
- 新增「复刻接口地址」设置项
- 版本号统一为 1.2.0（Android 与 Windows）

### v1.1.0

**新增**
- 音频输出设备选择（自动/外放/听筒/有线/蓝牙/USB），插拔自动跟随，会话内可切换
- 版本管理 + 手动/自动检查更新，GitHub Release + 镜像回退，内置 APK 下载
- Release 流程：推 `v*` tag 触发 GitHub Actions 自动发布 `dist/` 里的签名 APK

**修复**
- 输出设备被硬编码为内置扬声器，导致耳机/蓝牙完全无效（`setCommunicationDevice` 无条件钉死）

**升级注意**
- 旧的 `speaker_on` 布尔设置已被 `audio_route` 取代，升级后默认「自动」，插耳机即可生效

### v1.0.0

首个完成重做的版本：全双工实时语音、口语练习、报告、会话历史、深色主题、全套手写动效。

## 本次修复的主要问题

| 问题 | 影响 |
|---|---|
| PTT 模式下未按键仍持续上传麦克风 | 按住说话时会把大量静音/杂音一并提交，识别质量差 |
| 重连时旧 `RealtimeClient` 未关闭 | 泄漏 OkHttp 连接与线程，多次重连后卡顿 |
| `playChannel` 跨会话复用 | 新会话开头会播出上一个会话残留的音频 |
| 报告保存竞态 + `onCleared` 用已取消的 scope 落盘 | 会话尾巴与练习报告丢失 |
| 打断后旧 `response` 的音频继续播放 | AI 被「打断」后又说了半句 |
| 麦克风初始化失败时静默 | 显示已连接但没有声音 |
| `FLAG_KEEP_SCREEN_ON` 退出后不清除 | 离开通话界面屏幕一直亮着 |
| 设置页 Slider 每帧写 DataStore | 拖动卡顿、写放大 |
| 设置页输入框被数据流回写重置 | 中文输入法掉字、光标跳动 |
| 字幕每来一个字就强制滚到底 | 用户上滑回看时被拽回底部 |
| 离线直接连、傻等 15s 超时 | 现在先做网络预检并给明确提示 |
| 首页删除会话无二次确认 | 误删 |
| 硬编码中英文串 | 已全部改为 `values/` + `values-en/` 双语资源 |

## 已知限制

- 未做前台服务：App 退到后台超过数秒后系统会掐麦克风（通话类应用建议保持亮屏使用）
- API Key 明文存于应用私有目录（`/data/data/com.spkprac.app/`），不随备份导出也不上传
- 翻译与练习报告按句子/次数调用文本模型，产生少量额外 token 费用（flash 模型很便宜）
