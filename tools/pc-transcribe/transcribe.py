#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网课视频转写工具（电脑端，GPU 加速）

用 faster-whisper 把课程视频/音频转成：
  - <名字>.srt   字幕（标准格式，几乎所有播放器/网课平台都认）
  - <名字>.vtt   字幕（WebVTT，网页播放器用）
  - <名字>.txt   纯文字稿
  - <名字>.chapters.txt   章节节点（YouTube 风格 "0:00 标题"，可粘贴到平台章节栏）
  - <名字>.chapters.json  章节节点（结构化，给 App / 自定义播放器用）
  - <名字>.summary.txt    课堂总结（全课概要 + 几分几秒讲了啥的时间线，需本地/云端大模型）
  - <名字>.summary.json   课堂总结（结构化，给 App 用）

大模型（自动探测本地 Ollama 11434 / LM Studio 1234，探测到即默认启用，无需参数）：
  - LLM 字幕纠错：纠正听错的术语/同音字、补标点，逐条对齐不动时间轴；
  - LLM 章节切分：按内容分章、起更准的标题（否则退回「按时长 + 停顿」启发式）。
  用 --no-llm / --no-llm-correct / --no-llm-chapters 关闭；也可用 --llm-base-url/--llm-model 指定云端服务。

依赖：pip install -r requirements.txt
示例：
  python transcribe.py "D:/课程/第3讲.mp4"                         # 有本地大模型则自动纠错+切章节
  python transcribe.py "第3讲.mp4" --model medium --min-chapter-sec 240
  python transcribe.py "第3讲.mp4" --no-llm                        # 纯 whisper，不用大模型
  python transcribe.py "第3讲.mp4" --llm-base-url http://localhost:11434/v1 --llm-model qwen2.5:7b
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile


def ensure_cuda_dll_dirs():
    """Windows: pip 的 nvidia-*-cu12 包把 DLL 放在 site-packages\\nvidia\\*\\bin，
    默认不在 DLL 搜索路径里，导致 CTranslate2 报 cublas64_12.dll/cudnn 找不到。
    这里在导入 faster-whisper 之前把这些 bin 目录注册进搜索路径。"""
    if os.name != "nt":
        return
    try:
        import glob
        import nvidia
        added = []
        for root in list(nvidia.__path__):
            for binp in glob.glob(os.path.join(root, "*", "bin")):
                if os.path.isdir(binp):
                    try:
                        os.add_dll_directory(binp)
                    except Exception:
                        pass
                    os.environ["PATH"] = binp + os.pathsep + os.environ.get("PATH", "")
                    added.append(binp)
        if added:
            print(f"已注册 CUDA DLL 目录 {len(added)} 个")
    except Exception as e:
        print(f"注册 CUDA DLL 目录失败（可忽略，若报 dll 找不到再处理）：{e}", file=sys.stderr)


def format_ts(seconds: float, sep: str = ",") -> str:
    """秒 → SRT/VTT 时间戳 HH:MM:SS,mmm。"""
    if seconds < 0:
        seconds = 0
    ms = int(round(seconds * 1000))
    h, ms = divmod(ms, 3600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}{sep}{ms:03d}"


def format_clock(seconds: float) -> str:
    """秒 → 章节用的 m:ss 或 h:mm:ss。"""
    seconds = int(seconds)
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


# 章节标题开头常见的语气词/连接词，起标题时跳过它们，免得标题全是"好""那么"
_FILLER = ("好的", "好", "那么", "那", "然后", "接下来", "这个", "这", "就是", "其实",
           "我们", "现在", "对不对", "对", "嗯", "啊", "呃", "呐", "诶", "来", "所以")


def load_wordlist(path):
    """读取词表文件：一行一个词，也可一行多个（空格/逗号分隔），# 开头为注释。"""
    words = []
    if not path:
        return words
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            for w in line.replace("，", " ").replace(",", " ").split():
                words.append(w)
    return words


def load_prompt(path):
    """读取 initial_prompt 文本：忽略 # 注释行，其余行拼成一段当作上文喂给模型。
    用来把"板书会出现带数字下标的编号，如 A1、B1、C1"这类格式偏置给 Whisper。"""
    if not path or not os.path.isfile(path):
        return ""
    lines = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s and not s.startswith("#"):
                lines.append(s)
    return " ".join(lines)


def load_glossary(path):
    """读取术语纠正表：每行 `错误词=正确词`（或用 Tab 分隔），# 开头为注释。"""
    pairs = []
    if not path:
        return pairs
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            sep = "=" if "=" in line else ("\t" if "\t" in line else None)
            if not sep:
                continue
            wrong, right = line.split(sep, 1)
            wrong = wrong.strip()
            if wrong:
                pairs.append((wrong, right.strip()))
    return pairs


def apply_glossary(text, pairs):
    for wrong, right in pairs:
        text = text.replace(wrong, right)
    return text


def cap_hotwords(words, max_chars=100):
    """热词受 Whisper 提示长度限制，按字符数保守截断。返回保留的词列表。"""
    kept, total = [], 0
    for w in words:
        total += len(w) + 1
        if total > max_chars:
            break
        kept.append(w)
    return kept


def words_to_cues(words, max_gap=0.6, max_chars=16, max_dur=5.0):
    """把逐词时间戳按"停顿/长度/时长"切成字幕条：
    老师说 A …(停顿)… B …(停顿)… C 时会切成三条、各自出现在对应时间，而不是一次性全打出来。"""
    cues, cur = [], []

    def flush():
        text = "".join(w["text"] for w in cur).strip()
        return {"start": cur[0]["start"], "end": cur[-1]["end"], "text": text}

    for w in words:
        if cur:
            gap = w["start"] - cur[-1]["end"]
            cur_chars = sum(len(x["text"].strip()) for x in cur)
            dur = cur[-1]["end"] - cur[0]["start"]
            if gap > max_gap or cur_chars >= max_chars or dur >= max_dur:
                cues.append(flush())
                cur = []
        cur.append(w)
    if cur:
        cues.append(flush())
    return [c for c in cues if c["text"]]


def collect_segments_and_cues(seg_iter, gloss, max_gap=0.6, max_chars=16, max_dur=5.0, on_progress=None):
    """遍历转写结果：句级 segments（给章节/文字稿）+ 逐词切出的 cues（给字幕，按语速同步）。
    同时套用术语纠正表。无逐词时间戳时，cues 退回句级。"""
    segments, words = [], []
    for seg in seg_iter:
        text = apply_glossary(seg.text, gloss) if gloss else seg.text
        segments.append({"start": seg.start, "end": seg.end, "text": text})
        seg_words = getattr(seg, "words", None)
        if seg_words:
            for w in seg_words:
                wt = apply_glossary(w.word, gloss) if gloss else w.word
                words.append({"start": w.start, "end": w.end, "text": wt})
        if on_progress:
            on_progress(seg)
    cues = words_to_cues(words, max_gap, max_chars, max_dur) if words else segments
    return segments, cues


def title_from(text: str, limit: int = 20) -> str:
    t = text.strip().strip("，。、,.!?！？ ")
    changed = True
    while changed:  # 反复剥掉开头的语气词
        changed = False
        for f in _FILLER:
            if t.startswith(f) and len(t) > len(f):
                t = t[len(f):].lstrip("，。、,.!?！？ ")
                changed = True
                break
    return (t[:limit] + "…") if len(t) > limit else (t or "（无）")


def write_srt(segments, path):
    with open(path, "w", encoding="utf-8") as f:
        for i, seg in enumerate(segments, 1):
            f.write(f"{i}\n")
            f.write(f"{format_ts(seg['start'])} --> {format_ts(seg['end'])}\n")
            f.write(seg["text"].strip() + "\n\n")


def write_vtt(segments, path):
    with open(path, "w", encoding="utf-8") as f:
        f.write("WEBVTT\n\n")
        for seg in segments:
            f.write(f"{format_ts(seg['start'], '.')} --> {format_ts(seg['end'], '.')}\n")
            f.write(seg["text"].strip() + "\n\n")


def write_txt(segments, path):
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(seg["text"].strip() for seg in segments))


def build_chapters_heuristic(segments, min_sec: float):
    """按时长切章节：每累计 min_sec 秒，在下一句开头切一章。
    标题从该句起拼接几句、跳过语气词，凑成一个能看懂的短句。"""
    if not segments:
        return []

    def title_at(i: int) -> str:
        text = ""
        for j in range(i, min(i + 5, len(segments))):
            text += segments[j]["text"].strip()
            if len(text) >= 14:
                break
        return title_from(text)

    chapters = [{"time": segments[0]["start"], "title": title_at(0)}]
    last = segments[0]["start"]
    for i, seg in enumerate(segments):
        if seg["start"] - last >= min_sec:
            chapters.append({"time": seg["start"], "title": title_at(i)})
            last = seg["start"]
    return chapters


# 本地 OpenAI 兼容大模型常见端点：Ollama / LM Studio
_LOCAL_LLM_ENDPOINTS = ("http://localhost:11434/v1", "http://localhost:1234/v1")


def detect_local_llm(timeout=2.0):
    """探测本地是否有 OpenAI 兼容的大模型服务（Ollama 11434 / LM Studio 1234）。
    可用则返回 (base_url, model_id)，否则 (None, None)。只列模型、不触发推理，很快。"""
    try:
        import requests
    except Exception:
        return None, None
    for base in _LOCAL_LLM_ENDPOINTS:
        try:
            r = requests.get(base.rstrip("/") + "/models", timeout=timeout)
            r.raise_for_status()
            data = r.json().get("data") or []
            if data:
                return base, data[0]["id"]
        except Exception:
            continue
    return None, None


def llm_chat(base_url, model, api_key, prompt, temperature=0.2, timeout=600):
    """调用 OpenAI 兼容 /chat/completions，返回助手文本。供切章节 / 纠错共用。"""
    import requests
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    resp = requests.post(
        base_url.rstrip("/") + "/chat/completions",
        headers=headers,
        json={
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": temperature,
        },
        timeout=timeout,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"].strip()


def _strip_code_fence(content):
    """剥掉 LLM 输出里可能的 ```json ... ``` 包裹，只留中间内容。"""
    if content.startswith("```"):
        content = content.split("```")[1]
        if content.startswith("json"):
            content = content[4:]
    return content.strip()


def correct_cues_llm(cues, base_url, model, api_key, terms=None, batch_size=50,
                     timeout=600, on_progress=None):
    """用大模型校对字幕条：纠正 ASR 听错的术语/同音字、补规范标点，保持每条一一对应
    （不合并/拆分/增删，只替换 text，时间轴不动）。分批送入（同批互为上下文），
    某批数量对不上或解析失败则该批保留原文。返回 (新 cues 列表, 改动条数)。"""
    term_hint = ""
    if terms:
        uniq = list(dict.fromkeys(t for t in terms if t))[:60]  # 去重、限量，避免提示过长
        if uniq:
            term_hint = "正确术语参考（文中出现时请按此写法）：" + "、".join(uniq) + "\n"
    out = [dict(c) for c in cues]
    changed = 0
    for i in range(0, len(out), batch_size):
        batch = out[i:i + batch_size]
        numbered = "\n".join(f"{j + 1}. {c['text'].strip()}" for j, c in enumerate(batch))
        prompt = (
            "你是中文网课字幕校对员。下面是自动语音识别（ASR）得到的逐条字幕，"
            "可能把专业术语、同音字听错，标点也不规范。请逐条校对：\n"
            "- 只改明显的识别错误（错别字、同音误识、术语）并补全/规范标点；\n"
            "- 不要改写语义、不要润色文风、不要合并或拆分、不要增删条目，保持口语原貌；\n"
            f"- 严格输出一个 JSON 字符串数组，长度必须正好为 {len(batch)}，"
            "第 i 个元素是第 i 条校对后的文本。只输出 JSON，不要任何解释。\n"
            + term_hint + "\n字幕：\n" + numbered
        )
        try:
            content = _strip_code_fence(llm_chat(base_url, model, api_key, prompt, timeout=timeout))
            arr = json.loads(content)
            if not isinstance(arr, list) or len(arr) != len(batch):
                got = len(arr) if isinstance(arr, list) else "非数组"
                raise ValueError(f"返回条数 {got} ≠ {len(batch)}")
            for c, new_text in zip(batch, arr):
                nt = str(new_text).strip()
                if nt and nt != c["text"].strip():
                    c["text"] = nt
                    changed += 1
        except Exception as e:
            print(f"  第 {i // batch_size + 1} 批校对失败，保留原文：{e}", file=sys.stderr)
        if on_progress:
            on_progress(min(i + batch_size, len(out)), len(out))
    return out, changed


def build_chapters_llm(segments, base_url, model, api_key, max_chapters):
    """用 OpenAI 兼容大模型按内容切章节、起标题（含本地 Ollama）。失败则抛异常由上层回退。"""
    # 压缩成"每行一段时间戳+文字"，控制上下文长度
    lines = [f"[{format_clock(s['start'])}] {s['text'].strip()}" for s in segments]
    transcript = "\n".join(lines)
    prompt = (
        "下面是一节网课的带时间戳逐句字幕。请按讲授内容把它划分成章节，"
        f"输出不超过 {max_chapters} 个章节。每个章节给出起始时间（用字幕里出现过的时间戳）"
        "和一个简洁中文标题（不超过 16 字）。只输出 JSON 数组，元素形如 "
        '{"time":"mm:ss","title":"..."}，不要任何额外文字。\n\n字幕：\n' + transcript
    )
    content = _strip_code_fence(llm_chat(base_url, model, api_key, prompt))
    raw = json.loads(content)
    return [{"time": _clock_to_sec(c["time"]), "title": str(c["title"]).strip()} for c in raw]


def _clock_to_sec(t):
    """把 LLM 给的 "mm:ss" / "h:mm:ss" 时间戳解析成秒。"""
    parts = [int(x) for x in str(t).split(":")]
    while len(parts) < 3:
        parts.insert(0, 0)
    return parts[0] * 3600 + parts[1] * 60 + parts[2]


def build_summary_llm(units, base_url, model, api_key, max_sections=12, timeout=600):
    """用大模型生成课堂总结：一段总体概要 + 按时间顺序的分段（几分几秒大致讲了啥）。
    返回 {"overview": str, "sections": [{"start": 秒, "end": 秒, "summary": str}, ...]}。
    失败抛异常由上层处理。"""
    lines = [f"[{format_clock(u['start'])}] {u['text'].strip()}" for u in units]
    transcript = "\n".join(lines)
    prompt = (
        "下面是一节网课的带时间戳逐句字幕。请做一份课堂总结，包含两部分：\n"
        "1) overview：用 2~4 句话概括整节课讲了什么；\n"
        f"2) sections：按时间顺序分成不超过 {max_sections} 段，每段给出起始时间 start、"
        "结束时间 end（都用 mm:ss，取自字幕里出现过的时间戳）、以及 summary"
        "（1~2 句中文，说明这段大致讲了啥）。\n"
        '只输出 JSON 对象，形如 {"overview":"...","sections":[{"start":"mm:ss","end":"mm:ss","summary":"..."}]}，'
        "不要任何额外文字。\n\n字幕：\n" + transcript
    )
    content = _strip_code_fence(llm_chat(base_url, model, api_key, prompt, timeout=timeout))
    data = json.loads(content)

    def safe_sec(v):
        try:
            return max(0, _clock_to_sec(v))
        except (ValueError, TypeError):
            return 0

    sections = []
    for s in data.get("sections", []):
        start = safe_sec(s.get("start", 0))
        end = safe_sec(s.get("end")) if s.get("end") else 0
        sections.append({
            "start": start,
            "end": end if end > start else 0,  # 非法/越界的 end 置 0，呈现时省略
            "summary": str(s.get("summary", "")).strip(),
        })
    return {"overview": str(data.get("overview", "")).strip(), "sections": sections}


def denoise_to_temp(input_path, highpass=80, lowpass=8000, nf=-25):
    """轻量降噪预处理：用 ffmpeg 高/低通去掉板书粉笔的"哒哒"宽频瞬态噪声 + afftdn 频域降噪，
    再压成 16k 单声道 wav 临时文件喂给 whisper。这样夹在噪声里、间隔又长的零星短音
    （典型：开头老师慢慢写、间或念 A …… B …… C）更不容易被当成噪声整段丢掉。
    返回临时文件路径，调用方用完需自行删除。"""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("未找到 ffmpeg 命令行，降噪需要 ffmpeg。")
    fd, out = tempfile.mkstemp(suffix=".denoised.wav")
    os.close(fd)
    af = f"highpass=f={highpass},lowpass=f={lowpass},afftdn=nf={nf}"
    cmd = ["ffmpeg", "-y", "-i", os.path.abspath(input_path),
           "-af", af, "-ar", "16000", "-ac", "1", os.path.abspath(out)]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def robust_decode_kwargs(no_speech_threshold=0.35):
    """让 whisper 不要轻易把"夹在噪声/长停顿里的零星短音"判成静音丢弃。
    默认值比官方更宽松：no_speech 阈值调低（0.6→0.35）、logprob 阈值放宽，
    专治讲课开头孤立的 A/B/C 不出字幕。代价是纯静音段偶尔会多冒一两句幻听。"""
    return dict(
        no_speech_threshold=no_speech_threshold,
        log_prob_threshold=-1.5,
        compression_ratio_threshold=2.4,
    )


def _find_cjk_fontfile():
    """找一个含中文字形的字体文件，给 drawtext 用（drawtext 不像 subtitles 能靠 fontconfig 按名解析）。"""
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyh.ttf",
              r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\simhei.ttf",
              r"C:\Windows\Fonts\simsun.ttc"):
        if os.path.isfile(p):
            return p
    return None


def _escape_filter_path(path):
    """把 Windows 路径转成 ffmpeg 滤镜图能接受的形式：正斜杠 + 盘符冒号双反斜杠转义。
    （冒号要躲过滤镜图外层和参数内层两道解析，所以是两个反斜杠，实测 C\\\\:/... 才生效。）"""
    return path.replace("\\", "/").replace(":", "\\\\:")


def burn_subtitles(input_path, srt_path, output_path,
                   codec="hevc_nvenc", cq=28, font="Microsoft YaHei",
                   fontsize=20, audio_bitrate="96k",
                   disclaimer="字幕由AI生成，可能有错"):
    """用 ffmpeg 把 SRT 硬烧进画面并重编码（默认 NVENC GPU 压缩）。需要系统已装 ffmpeg 命令行。
    disclaimer 非空时，在画面右上角压一行半透明免责声明（drawtext）；置空字符串则不加。"""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("未找到 ffmpeg 命令行，请先安装并加入 PATH（烧字幕依赖 ffmpeg）。")
    # 在 SRT 所在目录里以相对文件名调用，绕开 Windows 路径里盘符冒号导致的 subtitles 滤镜转义问题
    work = os.path.dirname(os.path.abspath(srt_path)) or "."
    srt_name = os.path.basename(srt_path)
    style = f"FontName={font},Fontsize={fontsize},Outline=1,Shadow=0,MarginV=20"
    vf = f"subtitles={srt_name}:force_style='{style}'"
    disc_file = None
    if disclaimer:
        # 中文经命令行传给 Windows 版 ffmpeg 会变乱码，改写成 UTF-8 文本文件用 textfile= 读；
        # 放在 work 目录里用相对文件名引用，避开路径冒号转义。
        disc_file = os.path.join(work, "_disclaimer.txt")
        with open(disc_file, "w", encoding="utf-8") as f:
            f.write(disclaimer)
        disc_size = max(14, int(round(fontsize * 0.8)))
        opts = ["textfile=_disclaimer.txt", "fontcolor=white@0.8", f"fontsize={disc_size}",
                "x=w-tw-24", "y=24", "box=1", "boxcolor=black@0.4", "boxborderw=8"]
        ff = _find_cjk_fontfile()
        if ff:  # 用具体字体文件最稳；找不到就退回按字体名（靠 fontconfig，可能渲染成方块）
            opts.insert(0, f"fontfile={_escape_filter_path(ff)}")
        else:
            opts.insert(0, f"font={font}")
        vf += ",drawtext=" + ":".join(opts)
    cmd = ["ffmpeg", "-y", "-i", os.path.abspath(input_path), "-vf", vf, "-c:v", codec]
    if "nvenc" in codec:
        cmd += ["-rc", "vbr", "-cq", str(cq), "-preset", "p5"]
    else:
        cmd += ["-crf", str(cq), "-preset", "medium"]
    cmd += ["-c:a", "aac", "-b:a", audio_bitrate, os.path.abspath(output_path)]
    print("烧录硬字幕：", " ".join(cmd))
    try:
        subprocess.run(cmd, cwd=work, check=True)
    finally:
        if disc_file:
            try:
                os.remove(disc_file)
            except OSError:
                pass
    return output_path


def write_chapters(chapters, base_path):
    with open(base_path + ".chapters.txt", "w", encoding="utf-8") as f:
        for c in chapters:
            f.write(f"{format_clock(c['time'])} {c['title']}\n")
    with open(base_path + ".chapters.json", "w", encoding="utf-8") as f:
        json.dump(
            [{"time_sec": round(c["time"], 3), "title": c["title"]} for c in chapters],
            f, ensure_ascii=False, indent=2,
        )


def write_summary(summary, base_path):
    """课堂总结写成可读的 .summary.txt（概要 + 时间线）和结构化 .summary.json（给 App）。"""
    sections = summary.get("sections", [])
    with open(base_path + ".summary.txt", "w", encoding="utf-8") as f:
        if summary.get("overview"):
            f.write("【课堂概要】\n" + summary["overview"].strip() + "\n\n")
        f.write("【时间线】\n")
        for s in sections:
            span = format_clock(s["start"])
            if s.get("end"):
                span += "-" + format_clock(s["end"])
            f.write(f"{span}  {s['summary']}\n")
    with open(base_path + ".summary.json", "w", encoding="utf-8") as f:
        json.dump({
            "overview": summary.get("overview", ""),
            "sections": [
                {"start_sec": round(s["start"], 3), "end_sec": round(s["end"], 3), "summary": s["summary"]}
                for s in sections
            ],
        }, f, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser(description="网课视频 → 字幕 + 章节（faster-whisper，GPU）")
    ap.add_argument("input", help="视频或音频文件路径（直接吃 mp4，无需先抽音频）")
    ap.add_argument("--model", default="large-v3", help="whisper 模型：tiny/base/small/medium/large-v3（越大越准越慢）")
    ap.add_argument("--language", default="zh", help="语言，默认 zh（中文）")
    ap.add_argument("--device", default="cuda", help="cuda 或 cpu")
    ap.add_argument("--compute-type", default="float16", help="float16 / int8_float16(省显存) / int8")
    ap.add_argument("--output-dir", default=None, help="输出目录，默认与输入文件同目录")
    ap.add_argument("--min-chapter-sec", type=float, default=300, help="启发式章节的最短间隔秒数")
    ap.add_argument("--prompt", default="以下是一节中文课程的录音，请输出规范标点。",
                    help="initial_prompt，提示词偏置；数学课可写明涉及的术语/符号以提升识别")
    ap.add_argument("--no-vad", action="store_true", help="关闭静音过滤（若怀疑有人声被吞可加上对比）")
    ap.add_argument("--no-denoise", action="store_true",
                    help="关闭降噪预处理（默认开；专门压制板书/粉笔哒哒声，救开头孤立的 A B C）")
    ap.add_argument("--no-speech-threshold", type=float, default=0.35,
                    help="低于此 no_speech 概率才判为静音丢弃，调低更不易漏字（默认 0.35，官方 0.6）")
    ap.add_argument("--no-word-srt", action="store_true", help="关闭逐词字幕，退回句级字幕（整句一次性显示）")
    ap.add_argument("--cue-max-gap", type=float, default=0.6, help="逐词字幕：停顿超过该秒数就断成新字幕条")
    ap.add_argument("--cue-max-chars", type=int, default=13, help="逐词字幕：单条最多字符数（默认 13，避免长句折成两行）")
    ap.add_argument("--hotwords", default=None, help="热词表文件：一行一个词（或一行多个），全程偏置识别（faster-whisper 支持时生效）")
    ap.add_argument("--burn", action="store_true", help="把字幕硬烧进画面并重编码，输出 <名字>.subtitled.mp4（需 ffmpeg）")
    ap.add_argument("--codec", default="hevc_nvenc", help="烧录重编码器：hevc_nvenc/h264_nvenc(GPU) 或 libx264(CPU)")
    ap.add_argument("--burn-cq", type=int, default=28, help="烧录画质，越小越清晰越大（NVENC 的 cq / x264 的 crf）")
    ap.add_argument("--burn-font", default="Microsoft YaHei", help="字幕字体（需系统已装，中文要用含中文字形的字体）")
    ap.add_argument("--burn-fontsize", type=int, default=20, help="字幕字号")
    ap.add_argument("--disclaimer", default="字幕由AI生成，可能有错", help="烧录时画面右上角的免责声明文字")
    ap.add_argument("--no-disclaimer", action="store_true", help="烧录时不加免责声明")
    ap.add_argument("--glossary", default=None, help="术语纠正表文件：每行 错误词=正确词，转写后自动全文替换")
    ap.add_argument("--max-chapters", type=int, default=20, help="LLM 章节数量上限")
    ap.add_argument("--llm-base-url", default=None, help="OpenAI 兼容地址，如 http://localhost:11434/v1（留空则自动探测本地 Ollama/LM Studio）")
    ap.add_argument("--llm-model", default=None, help="LLM 模型名，如 qwen2.5:7b / gpt-4o-mini（留空且探测到本地服务时自动选用）")
    ap.add_argument("--llm-key", default=os.environ.get("LLM_API_KEY"), help="LLM 的 API Key（本地 Ollama 可不填）")
    ap.add_argument("--no-llm", action="store_true", help="完全不使用大模型（即便本地探测到也不用），章节走启发式、不做纠错")
    ap.add_argument("--no-llm-chapters", action="store_true", help="不用 LLM 切章节（仍可做 LLM 纠错），章节退回启发式")
    ap.add_argument("--no-llm-correct", action="store_true", help="不做 LLM 字幕纠错润色（仍可用 LLM 切章节）")
    ap.add_argument("--no-llm-summary", action="store_true", help="不生成 LLM 课堂总结（概要 + 几分几秒讲了啥的时间线）")
    ap.add_argument("--summary-sections", type=int, default=12, help="课堂总结分段数量上限")
    ap.add_argument("--llm-correct-batch", type=int, default=50, help="LLM 纠错每批字幕条数（越大越省调用、上下文越长）")
    args = ap.parse_args()

    # 解析 LLM 配置：显式 --llm-base-url/--llm-model 优先；否则自动探测本地 Ollama/LM Studio
    llm_base, llm_model, llm_key = args.llm_base_url, args.llm_model, args.llm_key
    if not args.no_llm and not (llm_base and llm_model):
        d_base, d_model = detect_local_llm()
        if d_base:
            llm_base, llm_model = llm_base or d_base, llm_model or d_model
            print(f"检测到本地大模型：{llm_model} @ {llm_base}（自动用于章节/纠错，--no-llm 可关）")
    llm_on = bool(llm_base and llm_model) and not args.no_llm

    if not os.path.isfile(args.input):
        print(f"找不到文件：{args.input}", file=sys.stderr)
        sys.exit(1)

    ensure_cuda_dll_dirs()
    from faster_whisper import WhisperModel

    print(f"加载模型 {args.model} ({args.device}, {args.compute_type}) ...")
    model = WhisperModel(args.model, device=args.device, compute_type=args.compute_type)

    # 降噪预处理（默认开）：压制板书噪声，救开头孤立短音
    audio_path, denoised_tmp = args.input, None
    if not args.no_denoise:
        try:
            print("降噪预处理（压制板书/粉笔哒哒声）...")
            denoised_tmp = denoise_to_temp(args.input)
            audio_path = denoised_tmp
        except Exception as e:
            print(f"降噪失败，改用原音频：{e}", file=sys.stderr)

    print(f"开始转写（VAD 静音过滤：{'关' if args.no_vad else '开'}，降噪：{'关' if args.no_denoise else '开'}）...")
    tr_kwargs = dict(
        language=args.language,
        initial_prompt=args.prompt or None,
        vad_filter=not args.no_vad,
        vad_parameters={"min_silence_duration_ms": 500},
        beam_size=5,
        **robust_decode_kwargs(args.no_speech_threshold),
    )
    hotwords = load_wordlist(args.hotwords)
    if hotwords:
        # Whisper 提示区 token 预算有限（约 224），热词过多会撑爆解码长度直接报错。
        kept = cap_hotwords(hotwords)
        if len(kept) < len(hotwords):
            print(f"⚠ 热词共 {len(hotwords)} 个，超过 Whisper 提示长度上限，仅取前 {len(kept)} 个。"
                  f"庞大的术语库请改用 --glossary 纠错表（无数量限制）。", file=sys.stderr)
        tr_kwargs["hotwords"] = " ".join(kept)
        print(f"已加载热词 {len(kept)} 个")
    if not args.no_word_srt:
        tr_kwargs["word_timestamps"] = True  # 逐词时间戳，字幕按语速分条显示
    try:
        seg_iter, info = model.transcribe(audio_path, **tr_kwargs)
    except TypeError:
        tr_kwargs.pop("hotwords", None)  # 旧版 faster-whisper 不支持 hotwords
        print("当前 faster-whisper 版本不支持 --hotwords，已忽略（可升级 faster-whisper）", file=sys.stderr)
        seg_iter, info = model.transcribe(audio_path, **tr_kwargs)
    print(f"识别语言: {info.language} (置信 {info.language_probability:.2f})，音频时长 {format_clock(info.duration)}")

    gloss = load_glossary(args.glossary)
    if gloss:
        print(f"已套用术语纠正表 {len(gloss)} 条")

    counter = [0]

    def _progress(seg):
        counter[0] += 1
        if counter[0] % 25 == 0:
            pct = (seg.end / info.duration * 100) if info.duration else 0
            print(f"  {pct:5.1f}%  [{format_clock(seg.end)}] {seg.text.strip()[:30]}")

    segments, cues = collect_segments_and_cues(
        seg_iter, gloss,
        max_gap=args.cue_max_gap, max_chars=args.cue_max_chars, on_progress=_progress
    )

    if denoised_tmp:  # 转写已消费完迭代器，删掉降噪临时 wav
        try:
            os.remove(denoised_tmp)
        except OSError:
            pass

    if not segments:
        print("没有识别到任何语音。", file=sys.stderr)
        sys.exit(2)

    # LLM 字幕纠错：纠正听错的术语/同音字、补标点，逐条对齐不动时间轴
    corrected = False
    if llm_on and not args.no_llm_correct:
        print("用大模型校对字幕（纠正听错术语 / 规范标点）...")
        terms = load_wordlist(args.hotwords) + [right for _, right in gloss]
        try:
            cues, n_changed = correct_cues_llm(
                cues, llm_base, llm_model, llm_key, terms=terms,
                batch_size=args.llm_correct_batch,
                on_progress=lambda d, t: print(f"  校对 {d}/{t} 条") if d % 200 == 0 or d == t else None,
            )
            corrected = True
            print(f"已校对字幕，修改 {n_changed} 条")
        except Exception as e:
            print(f"LLM 校对失败，保留原字幕：{e}", file=sys.stderr)

    # 校对后以字幕条为统一文本源（已纠错）；未校对时沿用句级 segments（更适合阅读/起标题）
    text_units = cues if corrected else segments
    chapter_units = cues if corrected else segments

    out_dir = args.output_dir or os.path.dirname(os.path.abspath(args.input))
    os.makedirs(out_dir, exist_ok=True)
    stem = os.path.splitext(os.path.basename(args.input))[0]
    base = os.path.join(out_dir, stem)

    write_srt(cues, base + ".srt")
    write_vtt(cues, base + ".vtt")
    write_txt(text_units, base + ".txt")
    print(f"已写出字幕: {base}.srt / .vtt / .txt（{len(cues)} 条字幕 / {len(segments)} 句）")

    # 章节：优先 LLM，失败/未配置则启发式
    chapters = None
    if llm_on and not args.no_llm_chapters:
        try:
            print("用大模型切分章节 ...")
            chapters = build_chapters_llm(chapter_units, llm_base, llm_model, llm_key, args.max_chapters)
        except Exception as e:
            print(f"LLM 章节失败，改用启发式：{e}", file=sys.stderr)
    if not chapters:
        chapters = build_chapters_heuristic(chapter_units, args.min_chapter_sec)

    write_chapters(chapters, base)
    print(f"已写出章节: {base}.chapters.txt / .chapters.json（共 {len(chapters)} 个节点）")
    for c in chapters:
        print(f"  {format_clock(c['time'])}  {c['title']}")

    # 课堂总结（仅 LLM）：全课概要 + 几分几秒大致讲了啥的时间线
    if llm_on and not args.no_llm_summary:
        try:
            print("用大模型生成课堂总结 ...")
            summary = build_summary_llm(chapter_units, llm_base, llm_model, llm_key, args.summary_sections)
            write_summary(summary, base)
            print(f"已写出课堂总结: {base}.summary.txt / .summary.json（{len(summary['sections'])} 段）")
            if summary.get("overview"):
                print(f"  概要：{summary['overview']}")
        except Exception as e:
            print(f"LLM 总结失败，跳过：{e}", file=sys.stderr)

    if args.burn:
        out_mp4 = base + ".subtitled.mp4"
        burn_subtitles(args.input, base + ".srt", out_mp4,
                       codec=args.codec, cq=args.burn_cq,
                       font=args.burn_font, fontsize=args.burn_fontsize,
                       disclaimer="" if args.no_disclaimer else args.disclaimer)
        print(f"已输出硬字幕视频: {out_mp4}")


if __name__ == "__main__":
    main()
