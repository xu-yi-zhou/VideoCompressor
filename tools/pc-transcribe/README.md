# 网课转写工具（电脑端，faster-whisper + GPU）

把课程视频转成**字幕（SRT/VTT）+ 章节节点**，在你的 PC（RTX 3070）上用 GPU 跑，免费、私密、快。

两种用法：
- **`transcribe.py`** —— 命令行，直接处理本地视频文件（现在就能用，**不需要手机**）。
- **`server.py`** —— 常驻局域网服务，给手机 App 用：转写字幕、按校对后的 SRT 把字幕硬烧进视频。

---

## 一、安装（一次）

```powershell
cd tools\pc-transcribe
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

> **GPU 说明（3070）**：faster-whisper 后端是 CTranslate2，CUDA 模式需要 cuBLAS 和 cuDNN。
> 如果运行报缺 `cudnn_ops64_*.dll` 之类，最简单的办法是装上对应库：
> ```powershell
> pip install nvidia-cublas-cu12 nvidia-cudnn-cu12
> ```
> 显存吃紧（3070 是 8GB）时把 `--compute-type` 换成 `int8_float16`（large-v3 约占 3GB）。
> 实在没配好 CUDA 也可以 `--device cpu`（慢很多）。

---

## 二、命令行转写（推荐先用这个验证效果）

```powershell
python transcribe.py "D:\课程\第3讲.mp4"
```

会在视频同目录生成：

| 文件 | 用途 |
|---|---|
| `第3讲.srt` | 字幕，几乎所有播放器/网课平台都认 |
| `第3讲.vtt` | 字幕，网页播放器用 |
| `第3讲.txt` | 纯文字稿 |
| `第3讲.chapters.txt` | 章节节点，YouTube 风格 `0:00 标题`，可直接粘到平台章节栏 |
| `第3讲.chapters.json` | 章节节点（结构化，给自定义播放器/App 用） |
| `第3讲.summary.txt` | 课堂总结：全课概要 + `几分几秒-几分几秒 这段讲了啥` 时间线（需大模型） |
| `第3讲.summary.json` | 课堂总结（结构化，给 App 用） |

常用参数：
```powershell
# 想更快（精度略降）：用 medium 模型
python transcribe.py "第3讲.mp4" --model medium

# 显存不够：int8 量化
python transcribe.py "第3讲.mp4" --compute-type int8_float16

# 章节切得更细（每 4 分钟一个）
python transcribe.py "第3讲.mp4" --min-chapter-sec 240
```

### 减少人工校对（术语多的课强烈推荐）
数学/专业课的术语、公式 ASR 难免出错。两招把人工量压到最小：

```powershell
# 1) 提示词 + 热词表：偏置识别，开头就少错
python transcribe.py "第3讲.mp4" `
  --prompt "这是一节高等数学课，涉及矩阵、行列式、偏导、线性无关、特征值等术语。" `
  --hotwords hotwords.txt

# 2) 术语纠正表：把高频错词写进文件，转写后自动全文替换，一次维护、所有课程复用
python transcribe.py "第3讲.mp4" --glossary glossary.txt

# 三者可同时用
python transcribe.py "第3讲.mp4" --hotwords hotwords.txt --glossary glossary.txt
```
- `hotwords.txt` 格式见 `hotwords.example.txt`，一行一个词（影响识别阶段）。
  ⚠ **热词有数量上限**（Whisper 提示长度约 224 token，约几十个词）：只放**最常出现、最容易听错**的少量术语。
  超过会被自动截断并提示。**庞大的术语库不要往这放**，改用下面的 `--glossary`（无数量限制）。
- `glossary.txt` 格式见 `glossary.example.txt`，每行 `错误词=正确词`（转写后纠正）。
- 两个文件都是**一次维护、所有课程复用**，每次校对发现新词就追加一行，越用越省人工。

> 关于 VAD：若发现有人声被吞，加 `--no-vad`（数学课老师默默板书的静音段被滤掉是正常的）。

### 高效人工校对工具
真要逐句核对，用免费的 **Subtitle Edit**（Windows）：左侧波形+视频、右侧字幕逐条对照，听一句改一句，比记事本快得多。打开视频再加载生成的 `.srt` 即可。

### 本地大模型：自动纠错 + 更准章节（强烈推荐）
脚本会**自动探测本地大模型服务**（Ollama `11434` / LM Studio `1234`）。一旦探测到，默认开启三件事，**无需任何参数**：

1. **LLM 字幕纠错润色** —— 用上下文纠正 ASR 听错的术语/同音字、补规范标点（超出静态 `--glossary` 的能力，能纠没见过的错），逐条对齐**不动时间轴**，纠错后的文本同时写进 `.srt/.vtt/.txt` 和章节。
2. **LLM 章节切分** —— 读全文按内容分章、起更准的中文标题（替代"取开头几个字"的启发式）。
3. **LLM 课堂总结** —— 生成 `.summary.txt`：一段全课概要 + `几分几秒-几分几秒 这段讲了啥` 的时间线（另有 `.summary.json` 给 App）。

```powershell
# 先装好本地模型，例如：ollama pull qwen2.5:7b —— 之后什么都不用加，自动启用
python transcribe.py "第3讲.mp4"
```

也支持任意 OpenAI 兼容服务（云端）或指定模型：
```powershell
python transcribe.py "第3讲.mp4" --llm-base-url http://localhost:11434/v1 --llm-model qwen2.5:7b
```

开关（默认全开）：
- `--no-llm` 完全不用大模型（章节走启发式、不纠错、不总结）
- `--no-llm-correct` 不做字幕纠错（仍用 LLM 切章节/总结）
- `--no-llm-chapters` 不用 LLM 切章节（仍做纠错/总结，章节退回启发式）
- `--no-llm-summary` 不生成课堂总结
- `--summary-sections 12` 课堂总结分段数量上限
- `--llm-correct-batch 50` 纠错每批字幕条数（越大越省调用）

> 纠错按批调用本地模型，2 小时课会多花几分钟（7B 模型在 3070 上）。失败/数量对不上的批会**自动保留原文**，不会破坏字幕。
> `--glossary` 仍建议保留：它免费、确定性强、术语会作为"正确写法参考"一并喂给纠错模型。

---

## 三、局域网服务（给手机 App 用）

```powershell
python server.py            # 默认 large-v3 / cuda，监听 0.0.0.0:8000
```
- 查看本机局域网 IP：`ipconfig`（如 192.168.1.20）。
- 确认手机与电脑同一 WiFi，且防火墙放行该端口。
- 手机 App「字幕 / 节点」页的「电脑服务地址」填 `192.168.1.20:8000` 即可。
- 章节按「时长 + 停顿」启发式切分；服务端**不使用大模型**（不做 LLM 纠错/章节/总结）。逐句校对在 App 内手动完成。

健康检查：浏览器打开 `http://192.168.1.20:8000/health` 应返回 `{"ok": true, "model": "large-v3"}`。

### 接口（App 自动调用，手动调试时参考）
所有转写/烧录接口都返回 **NDJSON 流**：一行一个 JSON，进度行 `{"stage","progress"}`，最后一行 `{"done":true,...}` 或 `{"error":"..."}`。

| 端点 | 用途 | 入参（multipart） |
|---|---|---|
| `POST /transcribe` | 上传音频转写 → 最后一行是完整结果 `{srt,vtt,text,chapters}` | `file`(音频) |
| `POST /burn` | 上传视频 → 服务端**转写并硬烧字幕** → 末行给 `video_url` | `file`(视频) |
| `POST /burn_srt` | 上传视频 + **已在 App 里逐句校对的 SRT** → 直接硬烧（**不再转写**）→ 末行给 `video_url` | `file`(视频)、`srt`(字幕) |
| `GET /download/{token}` | 取走 `/burn`、`/burn_srt` 烧好的成品 mp4（取走即删） | — |

> ⚠️ `/burn` 与 `/burn_srt` 依赖 **ffmpeg 命令行**（不是 Python 包）。`ffmpeg -version` 能跑才行，否则报「未找到 ffmpeg」。
> App 流程是「生成字幕 → 逐句校对 → 导出」：导出走 `/burn_srt`，烧进画面的就是你校对后的字幕。

### 字幕风格选项（服务端转写）
服务端转写采用 `transcribe.py` 的管道：VAD 切静音 + 不累积上文 + 提示带标点 → 词级时间戳 →
按「句末标点 / 字数(默认 16) / 停顿(默认 1.0s)」**按句切分**字幕条（`--cue-max-chars` / `--cue-max-gap` 可调）。
默认输出**无标点的纯文字字幕**，并支持删除口头禅：

| 选项 | 默认 | 说明 |
|---|---|---|
| `--keep-punct` | 关（即默认去标点） | 加上则**保留**字幕里的中英文标点 |
| `--fillers fillers.txt` | 读脚本目录 `fillers.txt` | 口语表，一行一个口头禅（如 `那么`/`这个`/`对吧`），转写后从字幕删除；整条被删空则丢弃 |
| `--no-vad` | 关（即默认开 VAD） | 默认开启静音过滤（`min_silence` 500ms）；孤立短句/停顿多的课可加此项关掉 |
| `--hallucinations hallucinations.txt` | 读脚本目录（与内置表合并） | **幻听黑名单**：静音段 Whisper 常脑补"请不吝点赞/点点栏目/谢谢观看/字幕by"等，命中即整条丢弃。内置已覆盖常见的，遇到没覆盖的往该文件加一行即可 |

- 只清洗**字幕（srt/vtt）**；`.txt` 文字稿与章节仍保留标点，便于阅读/喂大模型。
- `fillers.txt` 自带示例（默认全部注释，不删任何词）：取消注释或自行追加后重启即可生效。
- 转写已固定加 `condition_on_previous_text=False`，抑制长视频的上文累积幻听 / 溢出。

```powershell
python server.py                 # 默认：去标点 + VAD 开 + 防幻觉
python server.py --keep-punct    # 保留标点
python server.py --no-vad        # 关 VAD
```

---

## 性能参考
3070 + `large-v3` + `float16`，中文课程约 **5~10× 实时**：2 小时课大约 15~30 分钟转完。
想更快用 `medium`（约再快 1.5~2×，日常网课精度通常够用）。
