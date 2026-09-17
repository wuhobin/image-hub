package com.aurora.imagehub;

import com.aurora.imagehub.constants.AiGenerationConstants.TaskStatus;
import com.aurora.imagehub.model.entity.AiGeneration;
import com.aurora.imagehub.model.vo.GenerationVO;
import java.util.Date;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证实测耗时的单位转换，避免把审计时间或缺失数据当成生成耗时。 */
class GenerationVOTest {

    /** 保留毫秒精度；修改审计时间或删除图片不改变实测值，旧记录不推算。 */
    @Test
    void durationUsesMeasuredMillisInsteadOfAuditTimes() {
        AiGeneration task = new AiGeneration();
        task.setStatus(TaskStatus.SUCCEEDED.name());
        task.setCreateTime(new Date(1_000_000));
        task.setUpdateTime(new Date(1_066_000));
        assertThat(GenerationVO.from(task, null).getDurationSeconds()).isNull();

        task.setDurationMillis(12_345L);
        assertThat(GenerationVO.from(task, null).getDurationSeconds()).isEqualByComparingTo("12.345");
        task.setCreateTime(null);
        task.setUpdateTime(new Date(9_000_000));
        assertThat(GenerationVO.from(task, null).getDurationSeconds()).isEqualByComparingTo("12.345");
        task.setStatus(TaskStatus.FAILED.name());
        assertThat(GenerationVO.from(task, null).getDurationSeconds()).isEqualByComparingTo("12.345");
        task.setDurationMillis(0L);
        assertThat(GenerationVO.from(task, null).getDurationSeconds()).isZero();
        task.setDurationMillis(null);
        assertThat(GenerationVO.from(task, null).getDurationSeconds()).isNull();
    }
}
