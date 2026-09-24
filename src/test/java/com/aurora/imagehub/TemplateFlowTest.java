package com.aurora.imagehub;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.hutool.crypto.digest.BCrypt;
import com.aurora.imagehub.model.entity.ImageFile;
import com.aurora.imagehub.ratelimit.AttemptLimiter;
import com.aurora.imagehub.service.ImageFileService;
import com.aurora.starter.webmvc.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际 HTTP 与 H2 验证模板公开边界、管理员隔离、筛选、图片副本及失败清理，不调用模型或云服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:infrastructure-test.properties", properties = {
        "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
        "spring.datasource.url=jdbc:h2:mem:image_hub_templates;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORECASE=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=file:deploy/db/schema.sql", "platform.security.is-log=false"
})
@Import({TemplateFlowTest.TestBeans.class, SettingsCacheTestConfiguration.class})
class TemplateFlowTest {

    @TestConfiguration
    static class TestBeans {

        @Bean
        SaTokenDao saTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SaTokenDao saTokenDao;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private LettuceConnectionFactory lettuceConnectionFactory;

    @MockitoBean
    private AttemptLimiter attemptLimiter;

    @MockitoBean
    private ImageFileService imageFileService;

    /**
     * 覆盖完整维护和使用路径，包括原作品撤销后的独立副本。
     */
    @Test
    void templateLifecycleRespectsVisibilityValidationAndIndependentCopies() {
        SaManager.setSaTokenDao(saTokenDao);
        String hash = BCrypt.hashpw("test-password-123", BCrypt.gensalt(4));
        jdbcTemplate.update("INSERT INTO hub_admin(id,username,password_hash) VALUES(1,'operator',?)", hash);
        jdbcTemplate.update("INSERT INTO hub_user(id,username,email,password_hash) VALUES(1,'creator','creator@example.test',?)", hash);
        String admin = login("/api/admin/auth/login", "operator");
        String user = login("/api/app/auth/login", "creator");
        String publicPath = "/api/app/public/templates";
        String adminPath = "/api/admin/templates";
        assertThat(get(publicPath, null).path("data").path("total").asInt()).isEqualTo(9);
        assertThat(get(publicPath + "?pageSize=2", null).path("data").path("records").size()).isEqualTo(2);
        assertThat(get(publicPath + "?page=0", null).path("code").asInt()).isEqualTo(400);
        assertThat(get(publicPath + "?pageSize=51", null).path("code").asInt()).isEqualTo(400);
        assertThat(get(adminPath, null).path("code").asInt()).isEqualTo(401);
        assertThat(get(adminPath, user).path("code").asInt()).isEqualTo(401);
        long category = jdbcTemplate.queryForObject("SELECT id FROM hub_template_term WHERE kind='CATEGORY' AND name='个人创作'", Long.class);
        assertThat(get(publicPath + "?categoryId=" + category, null).path("data").path("total").asInt()).isEqualTo(3);
        assertThat(get(publicPath + "?search=插画", null).path("data").path("total").asInt()).isEqualTo(2);

        var termBody = Map.of("kind", "TAG", "name", "测试标签", "sortOrder", 7);
        long tag = post(adminPath + "/terms", termBody, admin).path("data").path("id").asLong();
        assertThat(tag).isPositive();
        assertThat(post(adminPath + "/terms", termBody, admin).path("code").asInt()).isEqualTo(409);
        assertThat(post(adminPath + "/terms", Map.of("kind", "TAG", "name", "   ", "sortOrder", 0), admin).path("code").asInt()).isEqualTo(400);
        Map<String, Object> body = new HashMap<>(Map.of("title", "测试场景", "description", "可填写的示例",
                "categoryId", category, "tagIds", List.of(tag), "promptPattern", "创作{{subject}}",
                "fields", List.of(Map.of("key", "subject", "label", "主体", "example", "山间书店", "required", true)),
                "enabled", false, "sortOrder", 5));
        assertThat(post(adminPath, body, user).path("code").asInt()).isEqualTo(401);
        JsonNode created = post(adminPath, body, admin);
        assertThat(created.path("code").asInt()).isEqualTo(200);
        long id = created.path("data").path("id").asLong();
        assertThat(get(publicPath + "/" + id, null).path("code").asInt()).isEqualTo(404);
        assertThat(created.path("data").has("fieldsJson")).isFalse();
        assertThat(created.path("data").has("storageInfo")).isFalse();
        assertThat(created.path("data").path("fields").get(0).path("key").asText()).isEqualTo("subject");

        body.put("promptPattern", "创作{{unknown}}");
        assertThat(request(adminPath + "/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(400);
        body.put("promptPattern", "创作{{subject}}");
        body.put("enabled", true);
        assertThat(request(adminPath + "/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(200);
        assertThat(get(publicPath + "?categoryId=" + category + "&tagId=" + tag + "&search=测试", null).path("data").path("total").asInt()).isEqualTo(1);
        assertThat(request(adminPath + "/terms/" + tag, HttpMethod.DELETE, null, admin).path("code").asInt()).isEqualTo(409);
        assertThat(request(adminPath + "/terms/" + category, HttpMethod.DELETE, null, admin).path("code").asInt()).isEqualTo(409);

        ImageFile first = image("example-one"), second = image("example-two");
        when(imageFileService.storeTemplateExample(any(MultipartFile.class))).thenReturn(first, second);
        var upload = new LinkedMultiValueMap<String, Object>();
        upload.add("file", new ByteArrayResource(new byte[]{1, 2, 3}) {
            @Override
            public String getFilename() {
                return "example.png";
            }
        });
        assertThat(post(adminPath + "/" + id + "/image", upload, admin).path("data").path("exampleUrl").asText()).isEqualTo(first.getUrl());
        doThrow(new BizException(502, "mock cleanup failure")).doNothing().when(imageFileService).discardGenerated(first.getStorageInfo());
        assertThat(post(adminPath + "/" + id + "/image", upload, admin).path("data").path("exampleUrl").asText()).isEqualTo(second.getUrl());
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_template_example WHERE id='example-one'", Integer.class)).isZero();
        assertThat(request(adminPath + "/" + id + "/image", HttpMethod.DELETE, null, admin).path("code").asInt()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM hub_template_example WHERE id='example-one'", Integer.class)).isEqualTo(1);
        assertThat(get(publicPath + "/" + id, null).path("data").path("exampleUrl").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_image", Integer.class)).isZero();

        String sourceId = UUID.randomUUID().toString(), shareId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO hub_image(id,user_id,name,url,type,source_type,size,width,height,storage_info)
                VALUES(?,1,'original.png','https://cdn.example.test/original.png','PNG','AI',100,10,10,'original-location')
                """, sourceId);
        jdbcTemplate.update("""
                INSERT INTO hub_ai_generation(id,user_id,request_id,model_id,model_name,model_code,base_url,images_path,
                  prompt,image_size,quality,status,share_id,share_status,prompt_public,published_time)
                VALUES(?,1,?,1,'测试模型','test','https://example.test','/images','公开的山林','1024x1024','medium',
                  'SUCCEEDED',?,'PUBLIC',0,CURRENT_TIMESTAMP)
                """, sourceId, UUID.randomUUID().toString(), shareId);
        var importBody = Map.of("shareId", shareId, "title", "导入的场景", "categoryId", category);
        assertThat(post(adminPath + "/import", importBody, admin).path("code").asInt()).isEqualTo(400);
        verify(imageFileService, never()).copyTemplateExample(any());
        jdbcTemplate.update("UPDATE hub_ai_generation SET prompt_public=1 WHERE id=?", sourceId);
        ImageFile copy = image("import-copy");
        when(imageFileService.copyTemplateExample(any())).thenReturn(copy);
        JsonNode imported = post(adminPath + "/import", importBody, admin);
        assertThat(imported.path("code").asInt()).isEqualTo(200);
        long importedId = imported.path("data").path("id").asLong();
        assertThat(imported.path("data").path("exampleUrl").asText()).isEqualTo(copy.getUrl());
        assertThat(imported.path("data").path("enabled").asBoolean()).isFalse();
        jdbcTemplate.update("UPDATE hub_ai_generation SET share_status='PRIVATE',share_id=NULL WHERE id=?", sourceId);
        jdbcTemplate.update("UPDATE hub_image SET deleted=1 WHERE id=?", sourceId);
        jdbcTemplate.update("UPDATE hub_creation_template SET enabled=1 WHERE id=?", importedId);
        JsonNode independent = get(publicPath + "/" + importedId, null);
        assertThat(independent.path("code").asInt()).isEqualTo(200);
        assertThat(independent.path("data").path("promptPattern").asText()).isEqualTo("公开的山林");
        assertThat(independent.path("data").path("exampleUrl").asText()).isEqualTo(copy.getUrl());
        assertThat(post(adminPath + "/import", importBody, admin).path("code").asInt()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hub_quota_usage", Integer.class)).isZero();

        body.put("tagIds", List.of());
        body.put("enabled", false);
        body.put("fields", List.of());
        body.put("promptPattern", "原样展示 {{literal}} 与 {{中文}}");
        assertThat(request(adminPath + "/" + id, HttpMethod.PUT, body, admin).path("code").asInt()).isEqualTo(200);
        assertThat(get(adminPath + "/" + id, admin).path("data").path("promptPattern").asText()).isEqualTo("原样展示 {{literal}} 与 {{中文}}");
        assertThat(get(publicPath + "/" + id, null).path("code").asInt()).isEqualTo(404);
        assertThat(request(adminPath + "/terms/" + tag, HttpMethod.DELETE, null, admin).path("code").asInt()).isEqualTo(200);
        assertThat(post(adminPath + "/terms", termBody, admin).path("code").asInt()).isEqualTo(409);
        jdbcTemplate.update("UPDATE hub_admin SET deleted=1 WHERE id=1");
        assertThat(get(adminPath, admin).path("code").asInt()).isEqualTo(401);
    }

    /**
     * 虚构的独立存储定位，仅验证数据库关联和清理语义。
     */
    private ImageFile image(String id) {
        ImageFile image = new ImageFile();
        image.setId(id);
        image.setUrl("https://cdn.example.test/" + id + ".png");
        image.setStorageInfo("location-" + id);
        return image;
    }

    /**
     * 通过真实登录接口取得不同账号类型的 Token。
     */
    private String login(String path, String username) {
        JsonNode result = post(path, Map.of("username", username, "password", "test-password-123"), null);
        assertThat(result.path("code").asInt()).isEqualTo(200);
        return result.path("data").path("token").asText();
    }

    private JsonNode get(String path, String token) {
        return request(path, HttpMethod.GET, null, token);
    }

    private JsonNode post(String path, Object body, String token) {
        return request(path, HttpMethod.POST, body, token);
    }

    private JsonNode request(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return testRestTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }
}
