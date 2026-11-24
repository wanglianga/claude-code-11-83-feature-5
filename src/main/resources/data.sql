-- 批次种子：B2026-01 固定 CSV1 规范；B2026-11 起生效 XML2 规范
INSERT INTO batch_registry (batch_id, spec_version, effective_from) VALUES
  ('B2026-01', 'CSV1', '2026-01-01T00:00:00Z'),
  ('B2026-11', 'XML2', '2026-11-01T00:00:00Z')
ON CONFLICT (batch_id) DO NOTHING;

INSERT INTO fault_switch (id, timeout_next) VALUES (1, FALSE)
ON CONFLICT (id) DO NOTHING;
