-- Optional per-reminder custom label ("Aller chercher le traitement" vs the
-- default "Prise du traitement"). Free text written by the user; the native
-- side decides whether it may appear in a notification (it is only shown when
-- the notification content mode is not GENERIC, same privacy rule as the
-- med-name/alias modes).
ALTER TABLE dose_schedules ADD COLUMN label TEXT;
