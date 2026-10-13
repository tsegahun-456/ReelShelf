# Firebase setup for Movie Watchlist

This adds cloud sign-in and syncs only the watchlist and watched-history records. Local video files and their paths stay on each PC. Use the Firebase no-cost Spark plan; do not add a billing account for this setup.

## 1. Create a Firebase project

1. Open the [Firebase Console](https://console.firebase.google.com/) and create a project, for example `movie-watchlist-tsega`.
2. Google Analytics is optional; skip it for this app.

## 2. Turn on email/password sign-in

In the project, open **Build → Authentication → Get started → Sign-in method**, enable **Email/Password**, and save. Do not enable Email link/passwordless sign-in. Firebase's current setup guide uses this provider setting for password accounts: [Firebase email/password setup](https://firebase.google.com/docs/auth/web/password-auth).

## 3. Create the Firestore database

1. Open **Build → Firestore Database → Create database**.
2. Use the default database ID `(default)` and **Standard** edition.
3. Select **Production mode**.
4. Choose a regional location. `me-central1 (Doha)` is a nearby available region for Ethiopia. The location cannot be changed after database creation, so confirm it before creating the database. See [Firestore location guidance](https://firebase.google.com/docs/firestore/locations).

The Spark plan includes no-cost Firestore quotas. Usage beyond Spark quotas is stopped until the quota resets rather than billed; do not upgrade/link billing. Review current [Firebase pricing](https://firebase.google.com/pricing) if Firebase changes its plan terms.

## 4. Publish the owner-only Firestore rules

Open the database's **Rules** tab. Replace the starter rules with the contents of [`firebase/firestore.rules`](../firebase/firestore.rules), then click **Publish**. Those rules allow a signed-in user to read or change documents only inside that user's own `users/{uid}` path.

## 5. Register a web app and copy two values

1. Open **Project settings → General → Your apps** and choose **Add app → Web** (`</>`).
2. Use the nickname `Movie Watchlist`; Firebase Hosting is not needed.
3. Register the app. In its Firebase config, copy only:
   - `apiKey` → `FIREBASE_WEB_API_KEY`
   - `projectId` → `FIREBASE_PROJECT_ID`

Send those two values to the local setup, or place them in the two matching `.env` lines yourself. The Firebase web API key identifies the project; Firebase documents that it is public by design. Firestore authorization comes from the ID token and Security Rules. Do not create or download a service-account key.

Once those values are in `.env`, rebuild with `scripts/rebuild-movie-watchlist.sh`, then sign out and back in. Existing local accounts are linked when the old email/password is valid; their PostgreSQL watchlist and watched history are merged during the first sync. New signups create Firebase accounts. Local media files and the existing database volume remain on the PC.
