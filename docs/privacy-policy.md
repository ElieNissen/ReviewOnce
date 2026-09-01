# ReviewOnce privacy policy draft

Last updated: [DATE]

ReviewOnce helps a user transfer their own film information between services. This draft must be published at a stable URL before requesting production OAuth access.

## Data handled

- Public SensCritique and Letterboxd profile names entered by the user.
- Film identifiers, ratings, watched dates, reviews and watchlist state required to compare the user’s own accounts.
- If official OAuth is approved, Letterboxd authorization tokens required to perform the actions requested by the user.

## Storage

Profile names, comparison results and the synchronization ledger are stored on the user’s device. ReviewOnce does not place account histories, reviews or access tokens in its public source repository. OAuth tokens, if introduced, will be encrypted and retained only for as long as the account remains connected.

## Use

Data is used only to identify missing fields, prepare an official import or perform user-authorized synchronization. ReviewOnce does not sell personal data, build advertising profiles, train models with account data or analyze the Letterboxd community.

## Sharing

Data is sent only to the source and destination services needed to complete the user’s request. Hosting providers may process transient network requests under their own service terms.

## Control and deletion

Users can disconnect an account and erase device-local settings, cached comparisons and synchronization history. OAuth access can also be revoked from Letterboxd account settings.

## Security

ReviewOnce uses least-privilege access, avoids password collection, prevents duplicate writes with idempotency keys and does not commit secrets to source control.

## Contact

[PRIVACY_CONTACT_EMAIL]
