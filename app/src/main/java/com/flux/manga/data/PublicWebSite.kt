package com.flux.manga.data

// Category IDs adapted from Keiyoushi d9420cd1c66ecd3385946b616aacfcf8f6314f70 (Apache-2.0).
// Modified 2026-11-11 for FluxManga. See public-web-NOTICE.txt.
internal enum class PublicWebSite(val sourceId: String, val label: String, val origin: String, val tags: Map<String, String>) {
    GUAZI("guazi-public-v1", "瓜子漫画", "https://www.guazimanhua.com/", linkedMapOf("耽美" to "41", "恋爱" to "9", "校园" to "29", "霸总" to "5", "都市" to "42", "穿越" to "8", "古风" to "23", "玄幻" to "25", "奇幻" to "31", "科幻" to "22", "灵异" to "21", "动作" to "54", "悬疑" to "11", "冒险" to "30", "搞笑" to "15", "热血" to "13", "恐怖" to "14", "系统" to "148", "逆袭" to "97", "脑洞" to "55", "复仇" to "61", "真人" to "17", "其它" to "27")),
    KUAIKAN("kuaikan-public-v1", "快看漫画", "https://www.kuaikanmanhua.com/", linkedMapOf("恋爱" to "20", "古风" to "46", "穿越" to "80", "大女主" to "77", "青春" to "47", "非人类" to "92", "奇幻" to "22", "都市" to "48", "总裁" to "52", "强剧情" to "82", "玄幻" to "63", "系统" to "86", "悬疑" to "65", "末世" to "91", "热血" to "67", "萌系" to "62", "搞笑" to "71", "重生" to "89", "异能" to "68", "冒险" to "93", "武侠" to "85", "竞技" to "72", "正能量" to "54")),
    MANHUAGUI("manhuagui-public-v1", "漫画柜", "https://www.manhuagui.com/", linkedMapOf("热血" to "rexue", "冒险" to "maoxian", "魔幻" to "mohuan", "神鬼" to "shengui", "搞笑" to "gaoxiao", "萌系" to "mengxi", "爱情" to "aiqing", "科幻" to "kehuan", "魔法" to "mofa", "格斗" to "gedou", "武侠" to "wuxia", "机战" to "jizhan", "战争" to "zhanzheng", "竞技" to "jingji", "体育" to "tiyu", "校园" to "xiaoyuan", "生活" to "shenghuo", "励志" to "lizhi", "历史" to "lishi", "伪娘" to "weiniang", "宅男" to "zhainan", "腐女" to "funv", "耽美" to "danmei", "百合" to "baihe", "后宫" to "hougong", "治愈" to "zhiyu", "美食" to "meishi", "推理" to "tuili", "悬疑" to "xuanyi", "恐怖" to "kongbu", "四格" to "sige", "职场" to "zhichang", "侦探" to "zhentan", "社会" to "shehui", "音乐" to "yinyue", "舞蹈" to "wudao", "杂志" to "zazhi", "黑道" to "heidao"));
    companion object {
        val sources = entries.map { it.sourceId }
        fun of(source: String) = entries.firstOrNull { it.sourceId == source }
    }
}
internal fun isPublicWebSource(source: String) = PublicWebSite.of(source) != null
