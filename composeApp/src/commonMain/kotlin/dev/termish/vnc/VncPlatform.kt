package dev.termish.vnc

/**
 * 阻塞式 TCP 套接字抽象（在 IO 线程/协程里使用）。
 * RFB/VNC 是单连接字节流协议，read/write 阻塞语义由平台实现。
 */
expect class VncTcpSocket(host: String, port: Int) {
    /** 发送全部字节；失败抛异常（连接层捕获后置断线状态）。 */
    fun write(data: ByteArray)
    /** 阻塞读最多 len 字节到 buf[off]；返回实际读取数，流结束返回 -1。 */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    fun close()
}

