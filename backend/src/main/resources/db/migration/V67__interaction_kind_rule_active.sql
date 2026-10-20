-- Правило вида можно временно отключить, не удаляя (аудит видов, 20.10.2026).
ALTER TABLE interaction_kind_rules ADD COLUMN active BOOLEAN DEFAULT TRUE NOT NULL;
