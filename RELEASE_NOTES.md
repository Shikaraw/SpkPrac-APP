## SpkPrac v0.1.1

全双工实时语音对话 + AI 口语练习。本次为 **新功能版本**，Android 与 Windows 双端同步更新，
版本号统一为 **0.1.1**（此前 Android 为 1.1.0、Windows 为 1.0.0）。

### 下载

| 平台 | 文件 |
|---|---|
| Windows | `SpkPrac-0.1.1.exe`（单文件，约 4 MB，双击即用） |
| Android | `SpkPrac-0.1.1.apk` |

> Windows 版未做代码签名，首次运行 SmartScreen 可能提示「未知发布者」，
> 点「更多信息 → 仍要运行」即可。

---

## 新增：音色选择与声音复刻

### 系统音色预设

当实时模型为 `qwen3.8-omni-flash-realtime` 时，设置页的音色一栏会提供一个可展开的下拉列表，
内含 **Qwen3.8-Omni 系列全部 33 个官方音色**：

```
Tina · Cindy · Liora Mira · Raymond · Zane · Katerina · Ryan · Mia · Cici
Theo Calm · Serena · Maia · Evan · Qiao · Momo · Wil · Angel · Li Cassian
Joyner · Gold · Jennifer · Aiden · Mione · Sunny · Dylan · Eric · Peter
Joseph Chen · Marcus · Li · Rocky · Kiki · Sohee
```

下拉与自由输入框并存：可以选，也可以照旧手打任意音色值。

> **一处更正**：最初需求里列的 `Tina / Ethan / Cherry / Serena / Dylan / Jada`，经查官方音色表，
> 只有 `Tina`、`Serena`、`Dylan` 属于 Qwen3.8-Omni 系列；`Ethan` 不存在，
> `Cherry` 与 `Jada` 属于旧的 Qwen3-Omni 非实时体系。因此预设改用官方有效全集，
> 上面三个仍在其中。

### 声音复刻

上传一段自己的录音，就能克隆出专属音色，用于实时对话：

1. 选择音频文件（WAV / MP3 / M4A / AAC）
2. 自动校验：大小 < 10 MB、采样率 ≥ 24 kHz、时长 5–60 秒（推荐 10–20 秒）
3. 命名后创建，服务端审核（通常数十秒）
4. 审核通过后自动出现在音色下拉列表的「我的音色」分组里

**音色管理页**可以查看全部复刻音色及其状态（可用 / 审核中 / 审核未通过），
支持刷新、删除。**每个音色有独立详情页**，可查看并复制 `voice_id`、绑定模型、创建时间、
原始文件名，也能重命名或删除。

删除后，该音色会立即从下拉列表中消失；如果删除的正是当前选中的音色，会自动回落到 `Tina`。

### 复刻接口地址

设置页新增「复刻接口地址」，默认指向北京地域的百炼声音复刻端点，
使用业务空间专属域名的用户可以自行修改。

---

## 其他

- 设置页音色区块补上「音频需包含至少 3 秒连续清晰朗读，无背景音乐与噪声」的提示
- 实时语音协议保持 `session.voice` 字段（官方确认对 Qwen3.8 仍向后兼容）
- 未新增任何第三方依赖；Windows 端仍为零前端依赖

## 已知限制

- 声音复刻需要有效 API Key，创建音色本身免费，按目标模型调用计费
- Windows 版未做代码签名
- 需要允许应用访问麦克风
- 版本号从 1.x 回落到 0.1.1：**已安装旧版本的用户不会收到自动更新提示**，
  需要手动下载安装（新装用户不受影响）
