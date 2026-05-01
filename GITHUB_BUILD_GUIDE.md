# How to get the BitAim APK using GitHub (no PC tools needed)

This guide is for someone who has never used GitHub before. It takes about 10 minutes the first time. After that, every change rebuilds the APK automatically.

## Step 1 — Create a free GitHub account
1. Go to https://github.com/signup
2. Sign up with your email. The free plan is fine.

## Step 2 — Create a new empty repository
1. After logging in, click the green **"New"** button (top-left, next to the search bar) — or go to https://github.com/new
2. **Repository name:** `bitaim` (or anything you like)
3. Set it to **Private** (recommended) or Public — either works
4. Do NOT tick "Add a README", "Add .gitignore", or "Add a license" — leave it empty
5. Click **"Create repository"**

## Step 3 — Upload all the source files
1. On your new empty repo page, click the link **"uploading an existing file"** (it appears in the middle of the screen)
2. Open the BitAim source zip on your phone or computer and **extract it** so you can see all the files (App.tsx, package.json, android folder, .github folder, etc.)
3. **Drag and drop ALL files and folders** from the extracted folder into the GitHub upload box
   - Important: drag the *contents* of the BitAim folder, not the folder itself
   - Make sure you can see `.github`, `android`, `App.tsx`, `package.json` etc. listed after dropping
4. Scroll down, leave the commit message as is, click **"Commit changes"**

⚠️ If you don't see a `.github` folder when you extract the zip, it's because your file manager hides folders starting with a dot. On Windows: View → Hidden items. On Mac: press `Cmd+Shift+.` in Finder.

## Step 4 — Wait for GitHub to build the APK
1. Click the **"Actions"** tab at the top of your repository
2. You should see a workflow named **"Build BitAim Debug APK"** running (yellow dot = in progress)
3. **The first build takes 10–15 minutes** because GitHub has to download Android SDK, Gradle, OpenCV, etc. Subsequent builds are faster.
4. When done, the dot turns green ✓ (or red ✗ if there's an error)

## Step 5 — Download the APK
1. Once the build is green, click on the workflow run
2. Scroll to the bottom — you'll see a section called **"Artifacts"**
3. Click **"BitAim-debug-APK"** to download a zip
4. Extract that zip — inside you'll find **`app-debug.apk`** — that's the file you install on your phone

## Step 6 — Install on your Android phone
1. Send the `app-debug.apk` to your phone (Bluetooth, email to yourself, Google Drive, anything)
2. On the phone, open it — Android will warn about installing from unknown sources; allow it for your file manager
3. Open BitAim, grant the permissions when prompted

---

## Common problems

| Problem | Fix |
|---|---|
| Build fails with "Could not resolve com.quickbirdstudios:opencv" | JitPack was temporarily down. Re-run the workflow (Actions → click run → "Re-run all jobs") |
| Build fails with "SDK location not found" | Shouldn't happen — but if it does, the workflow file (`.github/workflows/build-apk.yml`) was not uploaded. Check it's in your repo. |
| Build fails with `command not found: npm` | The `.github` folder didn't upload. Re-upload, ensuring hidden folders are visible. |
| "I don't see the Actions tab running anything" | The `.github/workflows/build-apk.yml` file is missing or in the wrong place. It must be at exactly `.github/workflows/build-apk.yml` — not `github/workflows/` or anywhere else. |
| "App installed but won't open" | Check that you installed `app-debug.apk` — not the source zip. On Android 8+, allow your file manager to install unknown apps in Settings → Apps → [your file manager] → Install unknown apps. |

## Re-building after changes
If you ever want to change anything in the source code, just edit the file on GitHub (click the pencil icon when viewing a file) and commit. GitHub will automatically rebuild a new APK and you download it the same way as Step 5.
