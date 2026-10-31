package com.chatjob.platform.zhaopin;

import com.chatjob.platform.Models.JobDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表数据解析的单测。夹具字段名与取值来自 2026-10-31 的真实页面（{@code positionList}）。
 *
 * <p>这些用例守的是最容易静默出错的三件事：切 JSON 时被字符串里的花括号骗、翻页数据在
 * 另一层路径下、以及"已投递"标记读错导致重复骚扰 HR。
 */
class ZhaopinRecordTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String RECORD = """
            {
              "positionNumber": "CC378471810J40886738807",
              "name": "北京java工程师",
              "salary60": "1-1.5万",
              "education": "本科",
              "workingExp": "5-10年",
              "companyName": "电信科学技术第五研究所有限公司",
              "companySize": "500-999人",
              "industryName": "通信/网络设备",
              "jobDescription": "负责公司ERP系统开发，功能迭代与性能优化。",
              "positionUrl": "https://www.zhaopin.com/jobdetail/CC378471810J40886738807.htm",
              "hasAppliedPosition": false,
              "workCity": "北京",
              "cityDistrict": "闽侯",
              "streetName": "上街",
              "jobDetailData": {
                "staff": {"staffName": "陈女士", "hrJob": "hr", "activityLevel": ["今日回复50+次"]}
              }
            }
            """;

    @Test
    void 切内嵌JSON时不被字符串里的花括号带偏() {
        String script = "__INITIAL_STATE__={\"jd\":\"熟悉 {Spring} 与 }{ 的区别\"};console.log(1);";
        assertThat(ZhaopinRecord.sliceInitialState(script))
                .isEqualTo("{\"jd\":\"熟悉 {Spring} 与 }{ 的区别\"}");
    }

    @Test
    void 没有标记时返回null而不是抛异常() {
        assertThat(ZhaopinRecord.sliceInitialState("<html></html>")).isNull();
        assertThat(ZhaopinRecord.sliceInitialState(null)).isNull();
    }

    @Test
    void 首屏数据在顶层取到岗位() {
        String script = "__INITIAL_STATE__=" + wrap("positionList", RECORD);
        List<JsonNode> list = ZhaopinRecord.parseInitialState(script, JSON);
        assertThat(list).hasSize(1);
        assertThat(ZhaopinRecord.positionNumber(list.get(0))).isEqualTo("CC378471810J40886738807");
    }

    @Test
    void 翻页接口的数据在data层也能取到() {
        String body = "{\"code\":200,\"data\":" + wrap("positionList", RECORD) + "}";
        try {
            List<JsonNode> list = ZhaopinRecord.findPositionList(JSON.readTree(body));
            assertThat(list).hasSize(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void 记录转成中性模型时字段各就各位() throws Exception {
        JobDetail detail = ZhaopinRecord.toJobDetail(ZhaopinRecord.PanelView.empty(), JSON.readTree(RECORD));
        assertThat(detail.platformJobId()).isEqualTo("CC378471810J40886738807");
        assertThat(detail.jobName()).isEqualTo("北京java工程师");
        assertThat(detail.companyName()).isEqualTo("电信科学技术第五研究所有限公司");
        assertThat(detail.salary()).isEqualTo("1-1.5万");
        assertThat(detail.area()).isEqualTo("北京 闽侯 上街");
        assertThat(detail.experience()).isEqualTo("5-10年");
        assertThat(detail.degree()).isEqualTo("本科");
        assertThat(detail.hrName()).isEqualTo("陈女士");
        assertThat(detail.hrTitle()).isEqualTo("hr");
        assertThat(detail.detailUrl()).contains("/jobdetail/CC378471810J40886738807.htm");
    }

    /**
     * 列表内嵌的 JD 是截断版（实测 81 字 vs 面板 516 字），所以面板文本更长时必须用面板的，
     * 否则喂给 AI 的是半截岗位描述，判定质量会明显下降。
     */
    @Test
    void 面板JD更长时以面板为准() throws Exception {
        String panelJd = "负责公司ERP系统开发，功能迭代与性能优化。".repeat(10);
        JobDetail detail = ZhaopinRecord.toJobDetail(ZhaopinRecord.PanelView.ofJd(panelJd), JSON.readTree(RECORD));
        assertThat(detail.description()).isEqualTo(panelJd.trim());
    }

    @Test
    void 面板JD更短时保留数据里的版本() throws Exception {
        JobDetail detail = ZhaopinRecord.toJobDetail(ZhaopinRecord.PanelView.ofJd("太短"), JSON.readTree(RECORD));
        assertThat(detail.description()).contains("ERP系统开发");
    }

    /**
     * 真实 JD 里混着站点噪声（实测日志里就有「版权所有 zhaopin.com」「看看大家怎么写 · 1/3」），
     * 不清掉会白占 prompt 的 4000 字预算，把真正的任职要求挤出去。
     */
    @Test
    void 职位描述里的站点噪声与HTML标签被清掉() {
        String raw = "1.Java开发法律协议资质公示<div> <div> 版权所有 zhaopin.com  </div></div>"
                + "看看大家怎么写 · 1/3\n\n2.熟悉Docker以及基于Docker的容器生态体系；";
        String cleaned = ZhaopinRecord.cleanDescription(raw);
        assertThat(cleaned).doesNotContain("版权所有");
        assertThat(cleaned).doesNotContain("看看大家怎么写");
        assertThat(cleaned).doesNotContain("<div>");
        assertThat(cleaned).contains("2.熟悉Docker以及基于Docker的容器生态体系；");
        assertThat(cleaned).startsWith("1.Java开发法律协议资质公示");
    }

    @Test
    void 空描述清洗后仍是空串() {
        assertThat(ZhaopinRecord.cleanDescription(null)).isEmpty();
        assertThat(ZhaopinRecord.cleanDescription("   ")).isEmpty();
    }

    /**
     * 适配器只带出平台原文，不自己归一 —— 归一只有一套（evaluate 层的 ActiveLevel），
     * 这里再搞一套会让同一句话在不同表里出现两种说法。
     */
    @Test
    void 活跃度只带原文归一留给evaluate层() throws Exception {
        JobDetail detail = ZhaopinRecord.toJobDetail(
                ZhaopinRecord.PanelView.empty(), JSON.readTree(RECORD));
        assertThat(detail.activeLevel()).isEmpty();
        assertThat(detail.activeRaw()).isEqualTo("今日回复50+次");
    }

    @Test
    void 活跃度原文保留平台说法() throws Exception {
        assertThat(ZhaopinRecord.activityRaw(JSON.readTree(RECORD))).isEqualTo("今日回复50+次");
    }

    @Test
    void 已投递标记来自平台字段() throws Exception {
        assertThat(ZhaopinRecord.alreadyApplied(JSON.readTree(RECORD))).isFalse();
        JsonNode applied = JSON.readTree(RECORD.replace("\"hasAppliedPosition\": false",
                "\"hasAppliedPosition\": true"));
        assertThat(ZhaopinRecord.alreadyApplied(applied)).isTrue();
    }

    @Test
    void 编号缺失时从详情链接兜底解析() throws Exception {
        JsonNode record = JSON.readTree(RECORD.replace(
                "\"positionNumber\": \"CC378471810J40886738807\",", ""));
        assertThat(ZhaopinRecord.positionNumber(record)).isEqualTo("CC378471810J40886738807");
    }

    @Test
    void 面板详情链接里抠出岗位编号() {
        assertThat(ZhaopinRecord.numberFromDetailUrl(
                "https://www.zhaopin.com/jobdetail/CCL1260832240J40831666816.htm?srccode=401901&data_identity=b43282fe"))
                .isEqualTo("CCL1260832240J40831666816");
        assertThat(ZhaopinRecord.numberFromDetailUrl(
                "https://www.zhaopin.com/jobdetail/CC378471810J40886738807.htm"))
                .isEqualTo("CC378471810J40886738807");
        assertThat(ZhaopinRecord.numberFromDetailUrl("")).isEmpty();
        assertThat(ZhaopinRecord.numberFromDetailUrl(null)).isEmpty();
    }

    /**
     * 滚动加载出来的岗位（196 张卡片里的后 176 张）不在首屏 {@code positionList} 里，
     * {@code record} 传 null 也必须能凭面板独立组出模型 —— 否则这些岗位全被跳过。
     */
    @Test
    void 没有列表记录时只靠面板也能组出完整模型() {
        ZhaopinRecord.PanelView panel = new ZhaopinRecord.PanelView(
                "CCL1260832240J40831666816",
                "https://www.zhaopin.com/jobdetail/CCL1260832240J40831666816.htm?srccode=401901",
                "java开发工程师（兼职）",
                "8000-15000元",
                List.of("北京·台江区", "3-5年", "学历不限", "兼职/临时", "招30人"),
                "福建省卓正能源科技有限公司",
                "/B轮 · 20-99人 · 产业互联网平台",
                "黄紫金",
                "今日回复19次",
                "人事管理 · 福建省卓正能源科技有限公司",
                "立即投递",
                "负责后端模块开发，熟悉 Spring Boot 与 MyBatis。",
                "{}");
        JobDetail detail = ZhaopinRecord.toJobDetail(panel, null);
        assertThat(detail.platformJobId()).isEqualTo("CCL1260832240J40831666816");
        assertThat(detail.jobName()).isEqualTo("java开发工程师（兼职）");
        assertThat(detail.companyName()).isEqualTo("福建省卓正能源科技有限公司");
        assertThat(detail.salary()).isEqualTo("8000-15000元");
        assertThat(detail.area()).isEqualTo("北京·台江区");
        assertThat(detail.experience()).isEqualTo("3-5年");
        assertThat(detail.degree()).isEqualTo("学历不限");
        assertThat(detail.hrName()).isEqualTo("黄紫金");
        assertThat(detail.hrTitle()).isEqualTo("人事管理");
        assertThat(detail.activeRaw()).isEqualTo("今日回复19次");
        assertThat(detail.description()).contains("Spring Boot");
    }

    /** 面板字段与列表字段都有时以面板为准：面板是当前选中岗位，列表可能是首屏另一条 */
    @Test
    void 面板与列表冲突时面板优先() throws Exception {
        ZhaopinRecord.PanelView panel = new ZhaopinRecord.PanelView(
                "CCL1260832240J40831666816", "https://www.zhaopin.com/jobdetail/CCL1260832240J40831666816.htm",
                "面板上的岗位名", "9000-10000元", List.of("北京·鼓楼区", "1-3年", "本科"),
                "面板上的公司", "", "李四", "今日活跃", "招聘专员", "继续沟通", "面板JD", "{}");
        JobDetail detail = ZhaopinRecord.toJobDetail(panel, JSON.readTree(RECORD));
        assertThat(detail.platformJobId()).isEqualTo("CCL1260832240J40831666816");
        assertThat(detail.jobName()).isEqualTo("面板上的岗位名");
        assertThat(detail.companyName()).isEqualTo("面板上的公司");
        assertThat(detail.area()).isEqualTo("北京·鼓楼区");
        assertThat(detail.activeRaw()).isEqualTo("今日活跃");
    }

    /** 摘要区标签顺序不固定，按内容判别而不是按位置取 */
    @Test
    void 摘要区标签按内容判别() {
        List<String> tags = List.of("学历不限", "招30人", "1-3年", "北京·台江区", "全职");
        assertThat(ZhaopinRecord.pickExperience(tags)).isEqualTo("1-3年");
        assertThat(ZhaopinRecord.pickDegree(tags)).isEqualTo("学历不限");
        assertThat(ZhaopinRecord.areaOfTags(tags)).isEqualTo("北京·台江区");
        assertThat(ZhaopinRecord.pickExperience(List.of("招3人", "本科"))).isEmpty();
        assertThat(ZhaopinRecord.pickDegree(List.of("招3人"))).isEmpty();
        assertThat(ZhaopinRecord.areaOfTags(List.of("本科", "3-5年")).isEmpty()).isTrue();
    }

    private static String wrap(String field, String record) {
        return "{\"" + field + "\":[" + record.replace("\n", "") + "]}";
    }
}
