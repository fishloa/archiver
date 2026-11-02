-- page_person_match and app_user named people by the in-memory tree's ids with nothing to hold
-- them. Rows naming a folded section root (or any person V22 merged) move to the person it was
-- merged into; rows naming nobody at all are dropped (a match is a cache) or cleared (a user's
-- "this is me"); then the references become foreign keys.
DELETE FROM page_person_match m
USING gen_person p
WHERE m.person_id = p.id AND p.merged_into IS NOT NULL
  AND EXISTS (SELECT 1 FROM page_person_match k
              WHERE k.page_id = m.page_id AND k.person_id = p.merged_into);

UPDATE page_person_match m SET person_id = p.merged_into
FROM gen_person p
WHERE m.person_id = p.id AND p.merged_into IS NOT NULL;

DELETE FROM page_person_match m
WHERE NOT EXISTS (SELECT 1 FROM gen_person p WHERE p.id = m.person_id);

UPDATE app_user u SET family_tree_person_id = p.merged_into
FROM gen_person p
WHERE u.family_tree_person_id = p.id AND p.merged_into IS NOT NULL;

UPDATE app_user SET family_tree_person_id = NULL
WHERE family_tree_person_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM gen_person p WHERE p.id = app_user.family_tree_person_id);

ALTER TABLE page_person_match
    ADD CONSTRAINT page_person_match_person_id_fkey
    FOREIGN KEY (person_id) REFERENCES gen_person (id);

ALTER TABLE app_user
    ADD CONSTRAINT app_user_family_tree_person_id_fkey
    FOREIGN KEY (family_tree_person_id) REFERENCES gen_person (id) ON DELETE SET NULL;
