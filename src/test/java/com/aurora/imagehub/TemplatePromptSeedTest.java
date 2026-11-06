package com.aurora.imagehub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用独立 H2 数据库验证系统模板初始化、保守升级与输入长度边界，不启动应用或访问真实数据库。
 */
class TemplatePromptSeedTest {

    private static final String UPGRADE = "deploy/db/migrations/20261023_template_prompt_upgrade.sql";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z][a-z0-9_]{0,31})}}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * H2 默认 BINARY 会截断到一个字节，专用连接配置必须保留完整内容并区分大小写和尾部空白。
     */
    @Test
    void binaryGuardsCompareCompleteValuesExactly() {
        JdbcTemplate jdbcTemplate = database();
        String value = "共同前缀".repeat(40) + "abc";
        assertThat(jdbcTemplate.queryForObject("SELECT CAST(? AS BINARY)", byte[].class, value))
                .isEqualTo(value.getBytes(StandardCharsets.UTF_8));
        for (String changed : List.of(value + "d", value + " ", value.replace("abc", "ABC"))) {
            assertThat(jdbcTemplate.queryForObject("SELECT CAST(? AS BINARY) = CAST(? AS BINARY)",
                    Boolean.class, value, changed)).isFalse();
        }
    }

    /**
     * 原始九个模板升级后与新库一致，其他字段保持不变；重复执行不修改任何记录。
     */
    @Test
    void upgradesAllOriginalSeedsToFreshSchemaAndCanBeRepeated() {
        JdbcTemplate jdbcTemplate = legacyDatabase();
        List<Map<String, Object>> before = templates(jdbcTemplate);
        List<Map<String, Object>> expected = templates(database("deploy/db/schema.sql"));

        execute(jdbcTemplate, UPGRADE);

        List<Map<String, Object>> upgraded = templates(jdbcTemplate);
        assertThat(upgraded).hasSize(9);
        assertThat(content(upgraded)).isEqualTo(content(expected));
        assertThat(metadata(upgraded)).isEqualTo(metadata(before));
        for (int index = 0; index < before.size(); index++) {
            assertThat(upgraded.get(index).get("prompt_pattern")).isNotEqualTo(before.get(index).get("prompt_pattern"));
        }

        execute(jdbcTemplate, UPGRADE);

        assertThat(templates(jdbcTemplate)).isEqualTo(upgraded);
    }

    /**
     * 管理员改过正文或字段、软删除及自行创建的模板均跳过；单独改封面等元数据不阻止默认正文升级。
     */
    @Test
    void preservesCustomizedDeletedAndUnseededTemplatesIncludingCaseDifferences() {
        JdbcTemplate jdbcTemplate = legacyDatabase();
        jdbcTemplate.update("UPDATE hub_creation_template SET prompt_pattern=CONCAT(prompt_pattern, '管理员补充') WHERE seed_key='avatar'");
        jdbcTemplate.update("UPDATE hub_creation_template SET fields_json=REPLACE(fields_json, 'scene', 'Scene') WHERE seed_key='wallpaper'");
        jdbcTemplate.update("UPDATE hub_creation_template SET deleted=1 WHERE seed_key='greeting'");
        jdbcTemplate.update("UPDATE hub_creation_template SET prompt_pattern=REPLACE(prompt_pattern, '{{topic}}', '{{Topic}}') WHERE seed_key='xiaohongshu'");
        jdbcTemplate.update("UPDATE hub_creation_template SET fields_json=REPLACE(fields_json, '城市里的慢生活', '管理员的文章主题') WHERE seed_key='article'");
        jdbcTemplate.update("UPDATE hub_creation_template SET title='管理员标题', description='管理员描述', category_id=(SELECT id FROM hub_template_term WHERE kind='CATEGORY' AND name='电商创作'), enabled=0, sort_order=321, cover_url='https://example.test/cover.jpg', source_share_id='source-share', source_author='管理员' WHERE seed_key='video'");
        jdbcTemplate.update("UPDATE hub_creation_template SET prompt_pattern=CONCAT(prompt_pattern, ' ') WHERE seed_key='product'");
        jdbcTemplate.update("UPDATE hub_creation_template SET fields_json=CONCAT(fields_json, ' ') WHERE seed_key='promotion'");
        jdbcTemplate.update("INSERT INTO hub_creation_template(title, description, category_id, prompt_pattern, fields_json, enabled, sort_order) SELECT '自建模板', description, category_id, prompt_pattern, fields_json, enabled, sort_order FROM hub_creation_template WHERE seed_key='product-scene'");
        List<Map<String, Object>> before = templates(jdbcTemplate);

        execute(jdbcTemplate, UPGRADE);

        List<Map<String, Object>> upgraded = templates(jdbcTemplate);
        assertThat(upgraded).hasSameSizeAs(before);
        assertThat(metadata(upgraded)).isEqualTo(metadata(before));
        Map<String, String> expectedPrompts = new HashMap<>();
        for (Map<String, Object> row : templates(database("deploy/db/schema.sql"))) {
            expectedPrompts.put((String) row.get("seed_key"), (String) row.get("prompt_pattern"));
        }
        for (int index = 0; index < before.size(); index++) {
            Map<String, Object> previous = before.get(index);
            Map<String, Object> current = upgraded.get(index);
            Object seed = previous.get("seed_key");
            if ("video".equals(seed) || "product-scene".equals(seed)) {
                assertThat(current.get("prompt_pattern")).isEqualTo(expectedPrompts.get(seed));
                assertThat(current.get("prompt_pattern")).isNotEqualTo(previous.get("prompt_pattern"));
            } else {
                assertThat(current).isEqualTo(previous);
            }
        }

        execute(jdbcTemplate, UPGRADE);

        assertThat(templates(jdbcTemplate)).isEqualTo(upgraded);
    }

    /**
     * 占位符与原有填写项逐一对应，示例可完整替换；每项填满前端允许的 500 字符仍不超过生图上限。
     */
    @Test
    void seededPromptsKeepFieldsCompatibleAndFitGenerationLimit() throws Exception {
        List<Map<String, Object>> seeds = templates(database("deploy/db/schema.sql"));
        List<Map<String, Object>> original = templates(legacyDatabase());
        assertThat(seeds).hasSize(9);
        for (int index = 0; index < seeds.size(); index++) {
            Map<String, Object> seed = seeds.get(index);
            String prompt = (String) seed.get("prompt_pattern");
            JsonNode fields = objectMapper.readTree((String) seed.get("fields_json"));
            assertThat(fields).isEqualTo(objectMapper.readTree((String) original.get(index).get("fields_json")));
            assertThat(prompt.length()).isBetween(1, 4000);
            Map<String, String> examples = new LinkedHashMap<>();
            for (JsonNode field : fields) {
                assertThat(field.path("example").asText().length()).isLessThanOrEqualTo(300);
                assertThat(examples.put(field.path("key").asText(), field.path("example").asText())).isNull();
            }
            Set<String> used = new HashSet<>();
            PLACEHOLDER.matcher(prompt).results().forEach(match -> used.add(match.group(1)));
            assertThat(used).isEqualTo(examples.keySet());
            assertThat(PLACEHOLDER.matcher(prompt).replaceAll("")).doesNotContain("{{", "}}");
            String rendered = PLACEHOLDER.matcher(prompt).replaceAll(match -> Matcher.quoteReplacement(examples.get(match.group(1))));
            assertThat(rendered).doesNotContain("{{", "}}");
            assertThat(rendered.length()).isBetween(1, 4000);
            assertThat(PLACEHOLDER.matcher(prompt).replaceAll("字".repeat(500)).length()).isLessThanOrEqualTo(4000);
        }
    }

    /**
     * 创建每个测试独享的内存库，二进制转换按 MySQL 的不截断语义验证。
     */
    private JdbcTemplate database(String... scripts) {
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:template_prompt_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE;VARIABLE_BINARY=TRUE", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(driverManagerDataSource);
        execute(jdbcTemplate, scripts);
        return jdbcTemplate;
    }

    /**
     * 使用未改动的历史 SQL 建立旧默认内容，同时补齐已独立发布的封面字段。
     */
    private JdbcTemplate legacyDatabase() {
        return database("deploy/db/migrations/20260924_creation_templates.sql", "deploy/db/migrations/20261023_template_cover_url.sql");
    }

    /**
     * 直接执行仓库 SQL，不改写迁移内容。
     */
    private void execute(JdbcTemplate jdbcTemplate, String... scripts) {
        ResourceDatabasePopulator resourceDatabasePopulator = new ResourceDatabasePopulator();
        resourceDatabasePopulator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        for (String script : scripts) resourceDatabasePopulator.addScript(new FileSystemResource(script));
        resourceDatabasePopulator.execute(jdbcTemplate.getDataSource());
    }

    private List<Map<String, Object>> templates(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("SELECT * FROM hub_creation_template ORDER BY id");
    }

    private List<Map<String, Object>> content(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> Map.of("seed_key", row.get("seed_key"), "prompt_pattern", row.get("prompt_pattern"), "fields_json", row.get("fields_json"))).toList();
    }

    /**
     * 正文升级允许数据库更新修改时间，其余元数据必须原样保留。
     */
    private List<Map<String, Object>> metadata(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("prompt_pattern");
            copy.remove("update_time");
            result.add(copy);
        }
        return result;
    }
}
