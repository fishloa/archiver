ALTER TABLE app_user ADD COLUMN lang VARCHAR(2) DEFAULT 'en' CHECK (lang IN ('en', 'de'));
