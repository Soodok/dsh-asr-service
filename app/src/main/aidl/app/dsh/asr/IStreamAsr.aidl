// ★ v0.7.0（2026-11-03）：流式识别跨进程接口。
// 为什么需要 AIDL 而不是普通 Binder 方法：调用方是另一个 App（vela.Databank），
// 拿不到 DecodeService 的类定义，只有稳定 AIDL 接口才能在编译期互认。
//
// 为什么不走 startService（原先的实现）：OPPO hans 按 uid 冻结进程后，
// startService 的 intent 停在 AM 队列不投递也不报错；binder 事务能穿透冻结。
package app.dsh.asr;

import app.dsh.asr.IStreamCallback;

interface IStreamAsr {
    /** 开始一段流式会话。 */
    /**
     * 开始一段流式会话。
     * @param sampleRate 输入 PCM 的真实采样率。手表实测只给到 8000Hz，
     *        必须按真实值建特征，否则音频被当 16k 处理会加速 2 倍、识别全错。
     */
    void beginStream(int seq, String lang, int sampleRate, IStreamCallback cb);
    /** 喂一片裸 PCM16LE 单声道。 */
    void feedPcm(int seq, in byte[] pcm);
    /** 收尾，最终结果通过回调的 onResult 回传。 */
    void endStream(int seq);
}
