# Letterboxd API access application

This document is the ready-to-review application package for official Letterboxd API access. Replace the bracketed contact and URL fields before sending. Do not add personal contact information to this public repository.

## Recommended request

Send to the API contact or form linked from [Letterboxd API access](https://letterboxd.com/api-beta/).

**Subject:** API access request — ReviewOnce, user-controlled cross-service diary sync

**Body:**

> Hello Letterboxd team,
>
> I am requesting API access for ReviewOnce, an open-source utility that helps a member transfer their own film diary data from SensCritique to their own Letterboxd account without entering the same rating, watched date, review or watchlist item twice.
>
> ReviewOnce is not a recommendation, analytics, data-mining or AI product. It does not analyze the Letterboxd community or reproduce Letterboxd features. Its sole purpose is user-controlled data portability into a member’s own account. Every proposed write is derived from that member’s source account, matched primarily by TMDB ID, shown for review when ambiguous, and protected against duplicate submissions.
>
> Current public repository: https://github.com/ElieNissen/ReviewOnce
>
> Product URL: [PUBLIC_PRODUCT_URL]
>
> Privacy policy: [PUBLIC_PRIVACY_URL]
>
> We would like to use OAuth Authorization Code flow and the minimum scopes needed to:
>
> - identify the authenticated member;
> - read that member’s films, ratings, diary entries, reviews and watchlist for duplicate detection;
> - create or update that member’s log entries, watched dates, ratings and reviews;
> - add films to that member’s watchlist.
>
> We do not need access to other members’ private data. Destructive watchlist removals will not be enabled by default.
>
> Security and product safeguards:
>
> - no Letterboxd passwords are collected or stored;
> - OAuth tokens are encrypted and revocable;
> - least-privilege scopes and explicit account connection;
> - idempotency keys and a device-visible activity log prevent duplicate writes;
> - ambiguous film matches require confirmation;
> - TMDB identifiers are used whenever available;
> - bounded concurrency, retry backoff and compliance with your rate limits;
> - users can disconnect and delete locally retained account data;
> - no sale of data, advertising profile, community scraping or runtime LLM processing;
> - the integration can be disabled immediately if requested.
>
> The intended experience is either an explicit “Sync N changes” action or an optional background sync chosen by the member. We are happy to restrict the initial release to explicit foreground sync if preferred.
>
> Could you confirm whether this use case is eligible, which read/write endpoints and scopes you would approve, and the rate limits we should design for? We can provide a demo video, test account, architecture diagram and any additional compliance information.
>
> Kind regards,
>
> [CONTACT_NAME]
> [CONTACT_EMAIL]

## Requested technical access

| Need | Preferred API capability | Safeguard |
|---|---|---|
| Authenticate | OAuth Authorization Code | No password collection |
| Detect duplicates | Read own films, logs, ratings, reviews and watchlist | Own account only |
| Create diary entry | Create log entry with TMDB/film ID and watched date | Idempotency key |
| Add rating/review | Create or update own log entry | Preview and activity log |
| Add watchlist item | Add to own watchlist | No removal by default |
| Background mode | Refreshable authorization, if permitted | Opt-in, backoff, revocation |

Likely scopes based on the current API documentation: `user` and `content:modify`. Confirm exact scope and endpoint availability with Letterboxd before implementation.

## Pre-submission checklist

- [ ] Deploy a stable public product URL.
- [ ] Publish the privacy policy from `docs/privacy-policy.md`.
- [ ] Add a support/contact address outside the public repository.
- [ ] Record a 60–90 second demo showing preview, exact TMDB match and duplicate prevention.
- [ ] Provide an OAuth redirect URL, for example `https://[DOMAIN]/api/auth/letterboxd/callback`.
- [ ] Decide whether the first application requests foreground sync only.
- [ ] Add account disconnect and local-data deletion controls before production OAuth.
- [ ] Replace every bracketed field in the email.
- [ ] Send only after the repository contains no secrets or personal account data.

## If access is declined

Keep the official CSV import as the supported write path. A local authenticated-session connector must remain experimental, foreground-only and disabled by default unless Letterboxd explicitly authorizes it. Do not ship copied API credentials, hidden client keys or a server-side password/session collector.
