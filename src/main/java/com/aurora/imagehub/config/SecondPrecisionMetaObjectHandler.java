package com.aurora.imagehub.config;

import com.aurora.starter.mybatisplus.model.BaseEntity;
import com.aurora.starter.mybatisplus.mybatis.MetaObjectHandlerAdapter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/** 复用平台时间填充，并统一截断到秒，保证接口返回值与 TIMESTAMP(0) 存储一致。 */
@Component
public class SecondPrecisionMetaObjectHandler extends MetaObjectHandlerAdapter {
    @Override
    public void insertFill(MetaObject metaObject) {
        super.insertFill(metaObject);
        if (metaObject.getOriginalObject() instanceof BaseEntity entity) {
            entity.setCreateTime(toSeconds(entity.getCreateTime()));
            entity.setUpdateTime(toSeconds(entity.getUpdateTime()));
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        super.updateFill(metaObject);
        if (metaObject.getOriginalObject() instanceof BaseEntity entity) {
            entity.setUpdateTime(toSeconds(entity.getUpdateTime()));
        }
    }

    private Date toSeconds(Date value) {
        return value == null ? null : Date.from(value.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }
}
