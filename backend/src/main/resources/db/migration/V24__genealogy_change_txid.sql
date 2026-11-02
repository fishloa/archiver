ALTER TABLE gen_change ADD COLUMN txid bigint;
ALTER TABLE gen_change ALTER COLUMN txid SET DEFAULT txid_current();
