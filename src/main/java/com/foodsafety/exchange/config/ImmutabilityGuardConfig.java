package com.foodsafety.exchange.config;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 不可变约束初始化：入站库与事件账禁止 UPDATE/DELETE。
 * PL/pgSQL 函数体含分号，不能经 schema.sql 分号拆分执行，故以单语句方式创建。
 */
@Configuration
public class ImmutabilityGuardConfig {

  @Bean
  ApplicationRunner immutabilityGuard(DataSource dataSource) {
    return args -> {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      jdbc.execute("""
          CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger
          LANGUAGE plpgsql AS $$
          BEGIN
            RAISE EXCEPTION 'immutable table % does not allow %', TG_TABLE_NAME, TG_OP;
          END;
          $$""");
      jdbc.execute("""
          CREATE OR REPLACE TRIGGER trg_inbound_envelope_immutable
            BEFORE UPDATE OR DELETE ON inbound_envelope
            FOR EACH ROW EXECUTE FUNCTION forbid_mutation()""");
      jdbc.execute("""
          CREATE OR REPLACE TRIGGER trg_business_event_immutable
            BEFORE UPDATE OR DELETE ON business_event
            FOR EACH ROW EXECUTE FUNCTION forbid_mutation()""");
      // 追溯版本冻结后不可修改/删除：风险沟通所依据的生产图与实验结果必须可复现
      jdbc.execute("""
          CREATE OR REPLACE TRIGGER trg_trace_version_immutable
            BEFORE UPDATE OR DELETE ON trace_version
            FOR EACH ROW EXECUTE FUNCTION forbid_mutation()""");
    };
  }
}
