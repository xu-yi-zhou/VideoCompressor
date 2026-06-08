#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
局域网转写服务（电脑端常驻），供手机 App 把音频"递过来"转写。

手机 App 把抽好的音频 POST 到 http://<电脑局域网IP>:8000/transcribe ，
返回流式 NDJSON，末行结果：{ "srt": "...", "vtt": "...", "text": "...", "chapters": [...] }。
App 拿到后写成旁挂文件即可。章节按「时长 + 停顿」启发式切分（不依赖大模型）。

模型只在启动时加载一次，常驻显存，之后每次请求都很快。

依赖：pip install -r requirements.txt fastapi "uvicorn[standard]" python-multipart
启动：python server.py            （默认 large-v3 / cuda / float16，监听 0.0.0.0:8000）
      python server.py --model medium --port 9000
注意：确保电脑防火墙放行该端口，手机和电脑在同一局域网。
"""

import argparse
import io
import json
import queue
import re
import tempfile
import threading
import os
import uuid

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import FileResponse, StreamingResponse
from starlette.background import BackgroundTask
import uvicorn

import transcribe as T

app = FastAPI(title="Lecture Transcribe Server")
_model = None
_cfg = {}


def get_model():
    global _model
    if _model is None:
        T.ensure_cuda_dll_dirs()
        from faster_whisper import WhisperModel
        print(f"加载模型 {_cfg['model']} ({_cfg['device']}, {_cfg['compute_type']}) ...")
        _model = WhisperModel(_cfg["model"], device=_cfg["device"], compute_type=_cfg["compute_type"])
    return _model


# 字幕里要去掉的标点（中英文都覆盖；不含空白，英文单词间空格单独规范化）
_PUNCT_RE = re.compile(r"[，。、！？；：“”‘’（）《》【】…—～·,.!?;:()\[\]{}<>\"'\\/|`~]")


def clean_cue_text(text: str, fillers) -> str:
    """字幕条文本清洗（参考 E:\\video\\srt\\transcribe.py）：删口头禅 + 去所有标点 + 规范空白。"""
    for f in fillers or ():
        if f:
            text = text.replace(f, "")
    text = _PUNCT_RE.sub("", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _clean_cues(cues):
    """对字幕条逐条清洗，丢弃清洗后为空的条（整条是口头禅/标点的情况）。"""
    fillers = _cfg.get("fillers") or []
    if not _cfg.get("strip_punct", True) and not fillers:
        return cues
    out = []
    for c in cues:
        t = clean_cue_text(c["text"], fillers)
        if t:
            out.append({**c, "text": t})
    return out


def _srt_string(segments) -> str:
    buf = io.StringIO()
    for i, seg in enumerate(segments, 1):
        buf.write(f"{i}\n{T.format_ts(seg['start'])} --> {T.format_ts(seg['end'])}\n{seg['text'].strip()}\n\n")
    return buf.getvalue()


def _vtt_string(segments) -> str:
    buf = io.StringIO()
    buf.write("WEBVTT\n\n")
    for seg in segments:
        buf.write(f"{T.format_ts(seg['start'], '.')} --> {T.format_ts(seg['end'], '.')}\n{seg['text'].strip()}\n\n")
    return buf.getvalue()


def do_transcribe(path, language, on_progress=None):
    """统一转写，采用 E:\\video\\srt\\transcribe.py 的管道：
      VAD 切静音 + 不累积上文 + 要求带标点 → 词级时间戳 → 按「标点/字数/停顿」切字幕条。
    套用启动时加载的 hotwords / prompt / glossary（术语纠正）。可选降噪预处理。
    on_progress(stage, frac)：转写过程按 0~1 上报进度，供流式接口回传手机。"""
    def emit(stage, frac):
        if on_progress:
            on_progress(stage, max(0.0, min(1.0, frac)))

    src, denoised_tmp = path, None
    if _cfg.get("denoise", True):
        try:
            denoised_tmp = T.denoise_to_temp(path)
            src = denoised_tmp
        except Exception as e:
            print(f"降噪失败，改用原音频：{e}")
    # 提示词要求输出标点：build_cues_from_words 依赖句末标点按句切分（无 prompt 时给默认值）
    kwargs = dict(
        language=language, beam_size=5,
        vad_filter=_cfg.get("vad", True),
        vad_parameters={"min_silence_duration_ms": 500},
        word_timestamps=True,
        condition_on_previous_text=False,
        initial_prompt=_cfg.get("prompt") or "以下是普通话的句子，请输出简体中文并加标点。",
    )
    if _cfg.get("hotwords"):
        kwargs["hotwords"] = _cfg["hotwords"]
    try:
        try:
            seg_iter, info = get_model().transcribe(src, **kwargs)
        except TypeError:
            kwargs.pop("hotwords", None)  # 旧版 faster-whisper 不支持 hotwords
            seg_iter, info = get_model().transcribe(src, **kwargs)
        dur = info.duration or 0
        gloss = _cfg.get("gloss") or []
        # 句级 segments（带标点，供文字稿/章节）+ 词级 words（供按句切字幕）
        segments, words = [], []
        for seg in seg_iter:
            text = T.apply_glossary(seg.text, gloss) if gloss else seg.text
            segments.append({"start": seg.start, "end": seg.end, "text": text})
            for w in (getattr(seg, "words", None) or []):
                words.append({"start": w.start, "end": w.end, "text": w.word})
            if on_progress and dur:
                emit("电脑转写中…", (seg.end / dur) * 0.9)
        # E 管道核心：词级 → 按标点/字数/停顿切条（已去标点）；无词级时退回句级
        cues = T.build_cues_from_words(
            words,
            max_chars=_cfg.get("cue_max_chars", 16),
            max_gap=_cfg.get("cue_max_gap", 1.0),
            strip_punct=_cfg.get("strip_punct", True),
        ) if words else segments
        if gloss:  # 术语纠正在切条后的整句文本上做，避免被词级切碎漏匹配
            cues = [{**c, "text": T.apply_glossary(c["text"], gloss)} for c in cues]
        return segments, cues, info
    finally:
        if denoised_tmp:  # 迭代器已消费完，删掉降噪临时 wav
            try:
                os.remove(denoised_tmp)
            except OSError:
                pass


@app.get("/health")
def health():
    return {
        "ok": True,
        "model": _cfg.get("model"),
    }


# 已烧好待下载的成品：token -> mp4 路径（/burn 流式完成后，手机再 GET /download/{token} 取走）
_burn_outputs = {}


def _ndjson_stream(q, cleanup_paths=()):
    """把工作线程经队列 q 推来的事件逐条 yield 成 NDJSON 行；结束（收到 None）后删临时文件。"""
    try:
        while True:
            item = q.get()
            if item is None:
                break
            yield json.dumps(item, ensure_ascii=False) + "\n"
    finally:
        for p in cleanup_paths:
            try:
                os.remove(p)
            except OSError:
                pass


def _run_transcribe_job(tmp_path, language, min_chapter_sec, q):
    """工作线程：跑完整转写，进度与最终结果都经队列 q 回传给流式响应。"""
    try:
        segments, cues, info = do_transcribe(
            tmp_path, language,
            on_progress=lambda stage, frac: q.put({"stage": stage, "progress": round(frac, 4)}),
        )
        # 章节用句级（带标点）文本，按「时长 + 停顿」启发式切分
        q.put({"stage": "生成章节中…", "progress": 0.96})
        chapters = T.build_chapters_heuristic(segments, min_chapter_sec)
        # 字幕(srt/vtt)做无标点 + 删口头禅清洗；文字稿/章节仍用带标点文本，便于阅读
        clean_cues = _clean_cues(cues)
        q.put({
            "done": True,
            "language": info.language,
            "duration": info.duration,
            "srt": _srt_string(clean_cues),
            "vtt": _vtt_string(clean_cues),
            "text": "".join(s["text"].strip() for s in segments),
            "chapters": [{"time_sec": round(c["time"], 3), "title": c["title"]} for c in chapters],
        })
    except Exception as e:
        q.put({"error": str(e)})
    finally:
        q.put(None)


@app.post("/transcribe")
async def transcribe_ep(
    file: UploadFile = File(...),
    language: str = Form("zh"),
    min_chapter_sec: float = Form(300.0),
):
    """流式 NDJSON：每行 {"stage","progress"} 上报进度，最后一行 {"done":true, ...结果}。"""
    # 落临时文件（faster-whisper 用 PyAV/ffmpeg 直接解码，mp4/m4a/wav 都行）
    suffix = os.path.splitext(file.filename or "")[1] or ".bin"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name
    q = queue.Queue()
    threading.Thread(
        target=_run_transcribe_job,
        args=(tmp_path, language, min_chapter_sec, q),
        daemon=True,
    ).start()
    return StreamingResponse(_ndjson_stream(q, (tmp_path,)), media_type="application/x-ndjson")


def _run_burn_job(tmp_path, srt_path, out_path, language, codec, cq, font, fontsize, disclaimer, q):
    """工作线程：转写 → 写 SRT → 硬烧字幕+重编码 → 把成品登记进 _burn_outputs 等手机下载。"""
    try:
        # 转写进度压缩到 0~0.8，留出 0.8~1.0 给烧录+压缩阶段
        _, cues, _ = do_transcribe(
            tmp_path, language,
            on_progress=lambda stage, frac: q.put({"stage": stage, "progress": round(frac * 0.8, 4)}),
        )
        T.write_srt(_clean_cues(cues), srt_path)
        q.put({"stage": "在电脑烧字幕+压缩中…（视频越长越久）", "progress": 0.85})
        T.burn_subtitles(tmp_path, srt_path, out_path, codec=codec, cq=cq, font=font,
                         fontsize=fontsize, disclaimer=disclaimer)
        token = uuid.uuid4().hex
        _burn_outputs[token] = out_path
        q.put({"done": True, "video_url": f"/download/{token}"})
    except Exception as e:
        q.put({"error": str(e)})
        try:
            os.remove(out_path)
        except OSError:
            pass
    finally:
        q.put(None)


@app.post("/burn")
async def burn_ep(
    file: UploadFile = File(...),
    language: str = Form("zh"),
    codec: str = Form("hevc_nvenc"),
    cq: int = Form(28),
    font: str = Form("Microsoft YaHei"),
    fontsize: int = Form(20),
    disclaimer: str = Form("字幕由AI生成，可能有错"),
):
    """上传视频 → 转写 → 硬烧字幕+重编码。流式回传进度，最后一行给出成品下载地址。"""
    suffix = os.path.splitext(file.filename or "")[1] or ".mp4"
    tmp_in = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    tmp_in.write(await file.read())
    tmp_in.close()
    base = tmp_in.name
    srt_path = base + ".srt"
    out_path = base + ".subtitled.mp4"
    q = queue.Queue()
    threading.Thread(
        target=_run_burn_job,
        args=(base, srt_path, out_path, language, codec, cq, font, fontsize, disclaimer, q),
        daemon=True,
    ).start()
    # 输入与 SRT 流式结束后即可删；成品 out_path 留给 /download 取走后再删
    return StreamingResponse(_ndjson_stream(q, (base, srt_path)), media_type="application/x-ndjson")


def _run_burn_srt_job(tmp_path, srt_path, out_path, codec, cq, font, fontsize, disclaimer, q):
    """工作线程：用手机端传来的（已人工逐句校对的）SRT 直接硬烧+重编码，跳过转写。"""
    try:
        q.put({"stage": "在电脑烧字幕+压缩中…（视频越长越久）", "progress": 0.1})
        T.burn_subtitles(tmp_path, srt_path, out_path, codec=codec, cq=cq, font=font,
                         fontsize=fontsize, disclaimer=disclaimer)
        token = uuid.uuid4().hex
        _burn_outputs[token] = out_path
        q.put({"done": True, "video_url": f"/download/{token}"})
    except Exception as e:
        q.put({"error": str(e)})
        try:
            os.remove(out_path)
        except OSError:
            pass
    finally:
        q.put(None)


@app.post("/burn_srt")
async def burn_srt_ep(
    file: UploadFile = File(...),
    srt: UploadFile = File(...),
    codec: str = Form("hevc_nvenc"),
    cq: int = Form(28),
    font: str = Form("Microsoft YaHei"),
    fontsize: int = Form(20),
    disclaimer: str = Form("字幕由AI生成，可能有错"),
):
    """上传视频 + 已校对 SRT → 直接硬烧字幕+重编码（不再转写）。流式回传进度，最后给下载地址。"""
    suffix = os.path.splitext(file.filename or "")[1] or ".mp4"
    tmp_in = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    tmp_in.write(await file.read())
    tmp_in.close()
    base = tmp_in.name
    srt_path = base + ".srt"
    with open(srt_path, "wb") as f:
        f.write(await srt.read())
    out_path = base + ".subtitled.mp4"
    q = queue.Queue()
    threading.Thread(
        target=_run_burn_srt_job,
        args=(base, srt_path, out_path, codec, cq, font, fontsize, disclaimer, q),
        daemon=True,
    ).start()
    return StreamingResponse(_ndjson_stream(q, (base, srt_path)), media_type="application/x-ndjson")


@app.get("/download/{token}")
def download_ep(token: str):
    """取走 /burn 烧好的成品 mp4，下载完成后删除该临时文件。"""
    path = _burn_outputs.pop(token, None)
    if not path or not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="成品不存在或已被取走")

    def cleanup():
        try:
            os.remove(path)
        except OSError:
            pass

    return FileResponse(path, media_type="video/mp4", filename="subtitled.mp4",
                        background=BackgroundTask(cleanup))


if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="large-v3")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--compute-type", default="float16")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    # 词表/提示词：默认读脚本目录下的 hotwords.txt / glossary.txt（手机端走服务也会自动套用）
    ap.add_argument("--hotwords", default=os.path.join(script_dir, "hotwords.txt"))
    ap.add_argument("--glossary", default=os.path.join(script_dir, "glossary.txt"))
    ap.add_argument("--prompt", default="", help="提示词；留空则读脚本目录下的 prompt.txt")
    ap.add_argument("--prompt-file", default=os.path.join(script_dir, "prompt.txt"),
                    help="提示词文件，默认 prompt.txt（写明板书会出现 A1/B1/C1 等带下标编号，偏置识别）")
    # 口语表：一行一个口头禅（如"对吧""那么"），转写后从字幕里删除；默认读脚本目录 fillers.txt
    ap.add_argument("--fillers", default=os.path.join(script_dir, "fillers.txt"),
                    help="口语表 txt（一行一个要删除的口头禅）")
    ap.add_argument("--keep-punct", action="store_true",
                    help="保留字幕标点（默认去除所有标点，输出纯文字字幕）")
    ap.add_argument("--no-vad", action="store_true",
                    help="关闭静音过滤（默认开；min_silence 500ms）")
    ap.add_argument("--no-denoise", action="store_true",
                    help="关闭降噪预处理（默认开；压制板书/粉笔哒哒声，救开头孤立的 A B C）")
    ap.add_argument("--cue-max-chars", type=int, default=16,
                    help="单条字幕最多字数，超过就断成新条（默认 16，E 管道值）")
    ap.add_argument("--cue-max-gap", type=float, default=1.0,
                    help="逐词字幕：停顿超过该秒数就断成新条（默认 1.0，E 管道值）")
    args = ap.parse_args()

    hw = T.cap_hotwords(T.load_wordlist(args.hotwords)) if os.path.isfile(args.hotwords) else []
    gloss = T.load_glossary(args.glossary) if os.path.isfile(args.glossary) else []
    fillers = T.load_wordlist(args.fillers) if os.path.isfile(args.fillers) else []
    fillers.sort(key=len, reverse=True)  # 先删长的，避免短词残留（参考 transcribe.py 口语表）
    prompt = args.prompt or T.load_prompt(args.prompt_file)  # 命令行 --prompt 优先，否则读 prompt.txt

    _cfg.update(
        model=args.model, device=args.device, compute_type=args.compute_type,
        hotwords=" ".join(hw), gloss=gloss, prompt=prompt, vad=not args.no_vad,
        fillers=fillers, strip_punct=not args.keep_punct,
        denoise=not args.no_denoise,
        cue_max_chars=args.cue_max_chars, cue_max_gap=args.cue_max_gap,
    )
    print(f"已加载：热词 {len(hw)} 个（{os.path.basename(args.hotwords)}）、"
          f"术语纠正 {len(gloss)} 条（{os.path.basename(args.glossary)}）、"
          f"口语词 {len(fillers)} 条（{os.path.basename(args.fillers)}）"
          + (f"、prompt：{prompt[:20]}…" if prompt else "、无 prompt")
          + f"、字幕标点：{'保留' if args.keep_punct else '去除'}"
          + f"、VAD：{'关' if args.no_vad else '开'}"
          + f"、降噪：{'关' if args.no_denoise else '开'}"
          + f"、切句：{args.cue_max_chars}字/{args.cue_max_gap}s（E 管道）")
    get_model()  # 启动即预热
    uvicorn.run(app, host=args.host, port=args.port)
