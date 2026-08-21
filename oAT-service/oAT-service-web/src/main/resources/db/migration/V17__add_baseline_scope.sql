-- C3: 基线归属范围（系统级闭环）
-- 新建基线必须绑定系统（scope=SYSTEM）；存量中 source_app_id 为空的基线标记为 LEGACY_PROJECT（遗留项目级，只读）。
ALTER TABLE oat_verification_baseline ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'SYSTEM';

UPDATE oat_verification_baseline
SET scope = 'LEGACY_PROJECT'
WHERE source_app_id IS NULL
  AND scope = 'SYSTEM';
