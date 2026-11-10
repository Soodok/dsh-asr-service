package app.dsh.asr;

interface IStreamCallback {
    /** 中间结果：已识别出的文字（每变一次回调一次）。 */
    void onPartial(int seq, String text);
    /** 最终结果。ok=false 时 text 为空、err 说明原因。 */
    void onResult(int seq, boolean ok, String text, String err, long ms);
}
