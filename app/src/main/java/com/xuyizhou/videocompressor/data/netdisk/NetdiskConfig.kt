/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.netdisk

/**
 * 百度网盘开放平台应用配置。
 *
 * 使用前需要到百度网盘开放平台（https://pan.baidu.com/union）实名注册开发者、
 * 创建应用，将下发的 AppKey / SecretKey 填入下方常量。
 * [APP_NAME] 即开放平台应用名，上传目录固定为 `/apps/<应用名>/<REMOTE_DIR>/`。
 *
 * 注意：真实凭据不要提交进 git（本文件在仓库中保持占位符）。
 */
object NetdiskConfig {

    /** TODO: 开放平台创建应用后填写 */
    const val APP_KEY = ""

    /** TODO: 开放平台创建应用后填写 */
    const val APP_SECRET = ""

    /** TODO: 与应用名一致（上传目录使用） */
    const val APP_NAME = ""

    /** 上传目录：应用专属目录下的子目录 */
    const val REMOTE_DIR = "视频压缩"

    /** 授权 scope：基础信息 + 网盘读写 */
    const val SCOPE = "basic,netdisk"

    val configured: Boolean
        get() = APP_KEY.isNotBlank() && APP_SECRET.isNotBlank() && APP_NAME.isNotBlank()
}
