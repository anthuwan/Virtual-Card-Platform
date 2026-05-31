-- ShedLock distributed scheduler lock table.
-- Ensures only one application instance runs scheduled jobs (e.g. card expiration)
-- simultaneously in a multi-instance deployment.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
