# Official Letterboxd API rollout

This is the implementation plan to follow after Letterboxd approves ReviewOnce. It deliberately avoids guessing undocumented endpoint behavior before credentials and final documentation are supplied.

## Phase 1 — approval and compliance

1. Publish the privacy policy and support contact.
2. Submit `letterboxd-api-application.md`.
3. Confirm permitted scopes, endpoints, redirect URLs and background behavior in writing.
4. Store the issued client secret in the hosting secret manager.

## Phase 2 — OAuth

1. Add Authorization Code flow with state and PKCE when supported.
2. Store no Letterboxd password.
3. Encrypt refresh/access tokens at rest.
4. Add connect, disconnect, revoke and delete-local-data controls.
5. Never expose the client secret in the PWA or mobile bundle.

## Phase 3 — full comparison

1. Read the authenticated member’s complete films, diary, ratings, reviews and watchlist.
2. Normalize everything to the existing `Film` domain model.
3. Match by TMDB ID first.
4. Keep partial reads marked as partial; never infer an absence from them.

## Phase 4 — direct writes

1. Convert pending `SyncItem` values into official API requests.
2. Create or update logs with date, rating and review in one operation where supported.
3. Add watchlist items separately.
4. Send bounded batches with retry/backoff.
5. Persist successful idempotency keys only after confirmed API success.
6. Require confirmation for ambiguous film candidates and conflicts.

## Phase 5 — background mode

1. Launch foreground “Sync N changes” first.
2. Add Android WorkManager detection and retry.
3. Enable background writes only when explicitly permitted by Letterboxd and enabled by the user.
4. Surface failures, expired sessions and conflicts in the activity log.

## Acceptance criteria

- A repeated run creates no duplicate diary entry, rating, review or watchlist addition.
- A 401/403 moves the action to `session-expired`, never to success.
- A 429 respects `Retry-After` and exponential backoff.
- A partial read cannot create a false “missing” action.
- A title-only ambiguous match is never written automatically.
- Disconnecting removes tokens and stops all scheduled work.
