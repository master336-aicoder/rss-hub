-- PostgreSQL (production) changelog schema. Must stay column-for-column in sync with
-- ../h2/V5__changelog_schema.sql; the deviations are the ones V1-V4 already establish (TEXT, TIMESTAMPTZ,
-- gen_random_uuid()).

CREATE TABLE content_change (
  -- No foreign key on post_id on purpose: after a post is deleted the changelog still has to say that it existed,
  -- what it was titled and when it went away. Dropping the history would drop the most important entries in
  -- /feed/changelog — the retractions. Same reasoning as repack_source_audit in V4.
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id   UUID     NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  post_id     BIGINT   NOT NULL,
  post_slug   TEXT     NOT NULL,
  post_title  TEXT     NOT NULL,
  -- A snapshot taken at write time, not rebuilt on read: the link has to keep pointing where the post used to be.
  post_url    TEXT     NOT NULL,
  action      TEXT     NOT NULL
              CHECK (action IN ('created', 'updated', 'status', 'deleted')),
  from_status TEXT,
  to_status   TEXT,
  changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX content_change_recent_idx ON content_change (id DESC);
CREATE INDEX content_change_post_idx ON content_change (post_id, id DESC);
