package tv.prism

enum class PlaybackMode(val id: String, val label: String) {
    SEQUENTIAL("sequential", "顺序播放"),
    REPEAT_ALL("repeat_all", "列表循环"),
    REPEAT_ONE("repeat_one", "单曲循环"),
    SHUFFLE("shuffle", "随机播放");

    companion object {
        fun fromId(id: String?): PlaybackMode? = entries.firstOrNull { it.id == id }
    }
}
