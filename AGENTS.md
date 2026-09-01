# ReviewOnce project guidance

## Product goal

ReviewOnce helps people avoid entering the same film information twice across SensCritique and Letterboxd. Show only actionable gaps such as a missing film, rating, review, or watched date. Do not prioritize entries that are already complete on both services.

## Matching rules

- Prefer stable identifiers, especially TMDB IDs, over localized titles.
- Use title, original title, year, and other available metadata only as fallbacks.
- Automatically accept only high-confidence matches.
- Present ambiguous candidates for confirmation instead of inventing a match.
- Open the exact Letterboxd film through its TMDB route when an ID is known.

## Privacy and security

- Never commit personal usernames, account histories, email addresses, access tokens, API keys, passwords, signing keys, or user exports.
- Keep account identifiers and comparison results device-local unless a future feature explicitly requires informed user consent.
- Keep `TMDB_API_TOKEN` and future credentials in runtime secrets only. `.env.example` may contain names and documentation, never real values.
- Never display fabricated demo data as user data. Retrieval failures must produce explicit errors.
- Preserve `scripts/check-privacy.sh` and run it before every commit or build.

## Architecture

- Keep comparison and matching logic independent from the web interface so it can be reused by a future mobile application.
- Keep SensCritique, Letterboxd, and TMDB integrations behind separate adapters or API routes.
- Respect provider rate limits with caching, bounded concurrency, retries, and explicit degraded modes.
- Do not treat partial Letterboxd activity as proof that an older film is absent.

## Validation and Git workflow

- Run `npm run build` after code changes. Run relevant focused tests when available.
- Fix validation failures before committing.
- Use concise commit messages describing the user-visible or architectural outcome.
- Commit and push each validated, coherent change to `main`, unless the user requests a branch or pull request.
- Do not commit generated builds, dependencies, caches, local environments, or secrets.
