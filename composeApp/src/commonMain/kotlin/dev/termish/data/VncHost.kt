package dev.termish.data

import kotlinx.serialization.Serializable

/**
 * VNC 远程桌面主机。与 SSH [Host] 独立：VNC 有自己的端口（默认 5900）与
 * 密码体系（VNC-Auth，非 SSH 凭据），密码存平台安全存储（账号
 * `secretAccountFor(id, "vncPassword")`）。
 */
@Serializable
data class VncHost(
    val id: String,
    val name: String,
    val hostname: String,
    /** 显示序号：N 号显示 = 5900 + N（端口 0 = 5900）。 */
    val display: Int = 0,
    /** 只读观看模式：不发送任何输入事件。 */
    val viewOnly: Boolean = false,
    val tags: List<String> = emptyList(),
    val colorIndex: Int = 0,
    val createdAt: Long = 0L,
    val lastConnectedAt: Long = 0L,
) {
    /** 实际 TCP 端口。 */
    val port: Int get() = 5900 + display
}
