package com.chatjob.evaluate;

import com.chatjob.store.entity.BlacklistEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlacklistMatcherTest {

    private static BlacklistEntity company(String keyword) {
        BlacklistEntity entity = new BlacklistEntity();
        entity.setKind("company");
        entity.setKeyword(keyword);
        entity.setNormalized(TextNormalizer.normalize(keyword));
        return entity;
    }

    private static BlacklistEntity job(String keyword) {
        BlacklistEntity entity = new BlacklistEntity();
        entity.setKind("job");
        entity.setKeyword(keyword);
        entity.setNormalized(TextNormalizer.normalize(keyword));
        return entity;
    }

    @Test
    void 命中公司名即丢弃_并且不区分全角与大小写() {
        BlacklistMatcher matcher = new BlacklistMatcher(List.of(company("外包")));
        assertThat(matcher.match("XX信息技术外包有限公司", "Java 工程师")).isPresent();
    }

    @Test
    void 全角字符与大小写差异不影响匹配() {
        BlacklistMatcher matcher = new BlacklistMatcher(List.of(company("Bytedance")));
        assertThat(matcher.match("ＢＹＴＥＤＡＮＣＥ", "后端")).isPresent();
    }

    @Test
    void 岗位名命中同样丢弃() {
        BlacklistMatcher matcher = new BlacklistMatcher(List.of(job("销售")));
        assertThat(matcher.match("某公司", "销售经理")).isPresent();
        assertThat(matcher.match("某公司", "Java 工程师")).isEmpty();
    }

    @Test
    void 一个字符的关键词一律忽略_避免误杀() {
        BlacklistMatcher matcher = new BlacklistMatcher(List.of(company("科")));
        assertThat(matcher.size()).isZero();
        assertThat(matcher.match("任何科技公司", "岗位")).isEmpty();
    }

    @Test
    void 空白与围空格不影响匹配() {
        BlacklistMatcher matcher = new BlacklistMatcher(List.of(company(" 阿里 ")));
        assertThat(matcher.match("阿里巴巴 ", "Java")).isPresent();
    }

    @Test
    void 关键词校验拒绝过短的输入() {
        assertThat(BlacklistMatcher.isValidKeyword("科")).isFalse();
        assertThat(BlacklistMatcher.isValidKeyword(" 外包 ")).isTrue();
    }
}
