# 音色预设 + 声音复刻 — 双端实现规格 v0.1.1

本文件是两个平台（Android / PC）共同遵守的唯一契约。实现时必须**逐条对齐**，不要各自发挥。

---

## 1. 目标

1. `rtModel == "qwen3.8-omni-flash-realtime"` 时，音色除自由输入外，还提供**系统音色预设下拉**。
   > **重要更正**：原始需求里给的 `Tina / Ethan / Cherry / Serena / Dylan / Jada` 经查官方音色表，
   > 只有 **Tina / Serena / Dylan** 在 Qwen3.8-Omni 系列有效；
   > `Ethan` 不存在，`Cherry` / `Jada` 属于旧的 Qwen3-Omni（非实时）体系。
   > 因此预设改用官方 Qwen3.8-Omni 有效全集（见 §5.1），用户的 3 个有效项都在其中。
2. 支持**声音复刻**：用户选一段本地音频 → 命名 → 创建音色 → 自动出现在下拉列表。
3. 支持**音色管理**：列表、详情、删除。删除后下拉列表同步消失。
4. 每个复刻音色有**详情页**，可查看 `voice_id` 等元数据并复制。
5. 双端版本号统一为 **0.1.1**。

---

## 2. 接口契约（阿里云百炼 · 声音复刻 HTTP API）

### 2.1 地址

```
POST {cloneEndpoint}
```

`cloneEndpoint` 默认值（两端一致）：

```
https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/audio/tts/customization
```

必须在设置中可配置（用户可能换 workspace/地域，Android 与 PC 都要暴露）。
PC 端加在`设置 → 端点与模型`，Android 加在`设置 → 接口`。

请求头：

```
Authorization: Bearer <apiKey>
Content-Type: application/json
```

### 2.2 通用请求体

```json
{ "model": "<ENROLL_MODEL>", "input": { "action": "<action>", ... } }
```

`<ENROLL_MODEL>` **取决于目标模型家族，两者不同，不能混用**：

| 目标实时模型 | 复刻模型名 |
|---|---|
| `qwen3.8-omni-flash-realtime`（及 Qwen-Omni / Omni-Realtime 系列） | **`qwen-voice-enrollment`** |
| `qwen-audio-3.x-realtime-*`（Qwen-Audio 系列） | `voice-enrollment` |

实现规则（两端一致）：

```
enrollModelFor(rtModel) =
  rtModel 含 "omni"  ? "qwen-voice-enrollment" : "voice-enrollment"
```

本项目默认模型 `qwen3.8-omni-flash-realtime` 落在第一行。

### 2.3 五个 action

| action | input 字段 | 返回 |
|---|---|---|
| `create_voice` | `target_model`*, `prefix`*, `audio`*, `language_hints?`, `enable_preprocess?` | `output.voice_id` |
| `list_voice` | `prefix?`, `page_index?`, `page_size?` | `output.voice_list[]` |
| `query_voice` | `voice_id`* | `output.{status, target_model, gmt_create, gmt_modified, resource_link}` |
| `update_voice` | `voice_id`*, `audio`* | `output {}` |
| `delete_voice` | `voice_id`* | `output {}` |

`voice_list` 单项：`{ voice_id, gmt_create, gmt_modified, status }`（**不含 target_model**）。

`audio` 支持公网 URL 或 **Base64 Data URI**：

```
data:audio/wav;base64,<...>
data:audio/mpeg;base64,<...>
data:audio/mp4;base64,<...>
```

本地文件一律走 Data URI。

`prefix` 规则：**仅数字和英文字母，≤10 字符**。生成名格式 `{target_model}-{prefix}-{唯一标识}`。

### 2.4 音色状态

| status | 含义 | 可选用 |
|---|---|---|
| `DEPLOYING` | 审核中/处理中 | 否 |
| `OK` | 审核通过 | **是** |
| `UNDEPLOYED` | 审核未通过 | 否 |

创建后需**轮询 `query_voice`**：间隔 1s，最多 30 次，直到 `status == "OK"`；
若变 `UNDEPLOYED` 立即停止并报错；超时按"仍在审核中"处理（不报失败）。

### 2.5 错误

非 2xx 或 `code != null` 时，把服务端 `message` 原样展示给用户（不要把 `code` 丢掉）。
网络异常展示为可读文本，不崩溃。

---

## 3. 音频文件校验（上传前）

以 **Qwen-Omni 系列的严格标准**为准（它比 Qwen-Audio 更严）：

| 项 | 要求 | 处理 |
|---|---|---|
| 扩展名/容器 | `.wav` `.mp3` `.m4a` `.aac`（大小写不敏感） | 不通过则拒绝 |
| 文件大小 | < 10 MB | 不通过则拒绝 |
| 时长 | 5s ≤ d ≤ 60s；**推荐 10–20s** | 越界拒绝；10–20 外仅警告 |
| 采样率 | **≥ 24 kHz** | 不通过则拒绝 |
| 声道 | **单声道** | 双声道仅警告（首声道会被使用） |

另：官方要求音频至少含 **3 秒连续清晰朗读**，无背景音乐/噪声/他人声——这条无法自动检测，写进界面提示文案即可。

校验失败必须**明确提示哪一项不合格**，不要只说"文件无效"。

---

## 4. 数据模型

两端都持久化到本地设置存储（Android: Prefs/DataStore；PC: settings.json）。

```
ClonedVoice {
  voiceId: String          // 服务端返回，主键
  name: String             // 用户自定义显示名（本地）
  targetModel: String      // 创建时绑定的模型
  status: String           // DEPLOYING / OK / UNDEPLOYED
  createdAt: Long          // epoch ms
  updatedAt: Long          // epoch ms（本地最后同步时间）
  audioName: String?       // 原文件名，仅展示
}
```

设置新增两项：

```
cloneEndpoint: String = "https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/audio/tts/customization"
clonedVoices: List<ClonedVoice> = []
```

**契约要点**：`list_voice` 不返回 `target_model`。合并策略：

1. 调 `list_voice`（`page_size: 100`, `page_index: 0`）。
2. 按 `voice_id` 与本地记录合并，更新 `status` / `updatedAt`。
3. 远程有、本地无的条目加入本地，`name` 默认取 `voice_id` 末段，`targetModel` 留空后
   **逐条 `query_voice` 补齐**（最多 30 条，失败则留空并标记为"未知模型"）。
4. 本地有、远程无的条目 → **直接删除**（服务端已删除）。
5. 未知模型（`targetModel == ""`）的音色**不进下拉列表**，但在管理页可见。

---

## 5. UI 规格

### 5.1 设置页 · 音色行

替换现有"音色"行，改为三件套同一行/同一区块：

```
音色
[ 预设下拉 ▾ ]           ← 仅当 rtModel == "qwen3.8-omni-flash-realtime"
[ 自由输入框        ]     ← 始终存在
[ 管理音色 ]              ← 打开音色管理页
```

- **预设下拉**仅在该模型下渲染，选项 = 系统音色 + 当前模型下 `status == OK` 的复刻音色。
  分组标题："系统音色" / "我的音色"。

  系统音色清单（Qwen3.8-Omni 系列官方有效值，顺序即下表，`Tina` 为默认）：

  ```
  Tina, Cindy, Liora Mira, Raymond, Zane, Katerina, Ryan, Mia, Cici,
  Theo Calm, Serena, Maia, Evan, Qiao, Momo, Wil, Angel, Li Cassian, Joyner,
  Gold, Jennifer, Aiden, Mione, Sunny, Dylan, Eric, Peter, Joseph Chen, Marcus,
  Li, Rocky, Kiki, Sohee
  ```

  共 33 个，必须定义成**单一常量数组**（两端同一个顺序），不要散落硬编码。
  下拉展示用 voice 值本身即可（这些值就是自解释的英文名）。
- 下拉与输入框**双向同步**：选下拉 → 写入输入框；输入框改值 → 若与某选项一致则下拉选中，
  否则下拉显示为"自定义"（不得清空输入框）。
- 空选择时回落到默认 `Tina`。
- 管理按钮始终可见。

### 5.2 音色管理页（新页面）

- 顶部：标题"我的音色" + 右上"添加音色"按钮。
- 列表项：`name`（主）+ 状态徽标（审核中/可用/未通过）+ 副标题 `targetModel`。
- 点击列表项 → 详情页。
- 列表项右侧滑动/长按删除，或详情页内删除。
- 空态：说明文案 + "添加音色"引导。
- 顶部提供"刷新"（重新 `list_voice`）。

### 5.3 添加音色流程

1. 输入显示名（必填，≤20 字符，去首尾空格）。
2. 选择音频文件（系统文件选择器，过滤 wav/mp3/m4a）。
3. 选完立即做 §3 校验并显示结果（时长、大小、格式）。
4. 点"创建"→ 上传 → 进度态 → 轮询状态 → 成功提示。
5. 成功后：写入本地列表、`status=DEPLOYING`（或已 OK）、`name` 用用户输入。
6. 若当前音色为空或用户确认，把新音色设为当前音色。

`prefix` 生成：取 `name` 中 `[A-Za-z0-9]` 字符，转小写，取前 10 位；
若结果为空则用 `voice`。若与已有 prefix 冲突不做特殊处理（服务端会加唯一标识）。

### 5.4 音色详情页（新页面）

字段（可复制 `voice_id`）：

- 显示名（可重命名，仅本地）
- 状态（带解释：审核中请稍候 / 可用 / 审核未通过）
- `target_model`（即绑定的实时模型）
- `voice_id`（等宽字体 + 复制按钮）
- 创建时间、最后同步时间、原始文件名
- 操作：刷新状态、删除（二次确认）

### 5.5 删除

- 二次确认对话框，文案说明"从服务端删除后不可恢复"。
- 调 `delete_voice` → 成功后从本地列表移除 → **下拉列表中同步消失**。
- 若删除的是当前选中音色，当前音色回落到 `Tina`。

---

## 6. 文案（i18n）

两端都要中英双语。键与中文文案：

| key | zh |
|---|---|
| `voice.manage` | 管理音色 |
| `voice.custom` | 自定义 |
| `voice.group_system` | 系统音色 |
| `voice.group_mine` | 我的音色 |
| `voice.title` | 我的音色 |
| `voice.add` | 添加音色 |
| `voice.refresh` | 刷新 |
| `voice.empty` | 还没有复刻音色 |
| `voice.empty_desc` | 上传一段 10–20 秒的清晰录音，就能克隆出一个专属音色。 |
| `voice.name` | 音色名称 |
| `voice.name_hint` | 给这个音色起个名字 |
| `voice.pick_audio` | 选择音频文件 |
| `voice.audio_tip` | 支持 WAV / MP3 / M4A，5–60 秒，不超过 10 MB |
| `voice.create` | 创建音色 |
| `voice.creating` | 正在创建… |
| `voice.deploying` | 审核中 |
| `voice.ok` | 可用 |
| `voice.undeployed` | 审核未通过 |
| `voice.unknown_model` | 未知模型 |
| `voice.detail` | 音色详情 |
| `voice.voice_id` | 音色 ID |
| `voice.target_model` | 绑定模型 |
| `voice.created_at` | 创建时间 |
| `voice.synced_at` | 最后同步 |
| `voice.source_file` | 原始文件 |
| `voice.copy_id` | 复制 ID |
| `voice.copied` | 已复制 |
| `voice.rename` | 重命名 |
| `voice.delete` | 删除音色 |
| `voice.delete_confirm` | 确定删除「{name}」吗？删除后不可恢复，且该音色会从列表中消失。 |
| `voice.delete_done` | 音色已删除 |
| `voice.created` | 音色创建成功 |
| `voice.status_refreshed` | 状态已刷新 |
| `voice.err_name_empty` | 请填写音色名称 |
| `voice.err_format` | 不支持的音频格式，请使用 WAV / MP3 / M4A |
| `voice.err_size` | 音频文件超过 10 MB |
| `voice.err_too_short` | 音频太短，至少需要 5 秒 |
| `voice.err_too_long` | 音频太长，最长 60 秒 |
| `voice.warn_duration` | 建议使用 10–20 秒的音频，效果更好 |
| `voice.err_no_key` | 请先在设置中填入 API Key |

---

## 7. 硬性约束

- **不得弱化现有功能**：自由输入音色、其余设置项、会话流程一律保持原样。
- **不得新增重量级依赖**：Android 只用已有依赖 + 标准库；PC 保持零前端依赖。
  Rust 侧如需 multipart 也不需要（用 JSON + Base64 即可）。
- **不改整体架构**：Android 仍是 Compose + 现有 Repository/ViewModel 分层；
  PC 仍是 `SP.*` 全局命名空间 + Rust command。
- 文件读写、网络调用必须有错误处理，不允许抛未捕获异常。
- Base64 编码大文件时注意内存：10 MB 文件 → 约 13.3 MB 字符串，允许，但**不要**同时保留多份拷贝。

---

## 8. 版本号

- Android：`app/build.gradle.kts` → `versionName = "0.1.1"`，`versionCode` 递增到 `3`。
- PC：`PC/Cargo.toml` + `PC/tauri.conf.json` → `0.1.1`。

（版本号由 Lead 统一改，两个实现者不要动这两个文件。）
