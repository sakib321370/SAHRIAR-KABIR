# BanglaSub AI — FFmpeg & Chunked Upload Backend

This folder contains the Node.js + FFmpeg backend for processing large (10 minute – 2 hour) MP4, MOV, and MKV videos server-side with 5 MB chunked uploads, FFmpeg audio segmentation, server-side Gemini transcription/translation, and `libass` / `Noto Sans Bengali` MP4 subtitle burn-in.

## Deploy Using Only a Web Browser (No Local Coding Tools Required)

### Option A: Google Cloud Console + Cloud Shell (Easiest, 100% in Browser)

1. Push/export this project from AI Studio to **GitHub** (using the AI Studio Export / GitHub menu).
2. Open **[Google Cloud Console](https://console.cloud.google.com/)** in your web browser and select your project (`gen-lang-client-0389353078` or any billing-enabled Google Cloud project).
3. Click the **Activate Cloud Shell** (`>_`) icon in the top-right toolbar of Google Cloud Console.
4. In the browser Cloud Shell terminal, clone your GitHub repo and run one command:
   ```bash
   git clone <YOUR_GITHUB_REPO_URL>
   cd <YOUR_REPO_FOLDER>/backend
   gcloud run deploy banglasub-backend \
     --source . \
     --region asia-southeast1 \
     --allow-unauthenticated \
     --memory 4Gi \
     --cpu 2 \
     --no-cpu-throttling \
     --max-instances 1 \
     --timeout 3600 \
     --set-env-vars GEMINI_API_KEY=YOUR_GEMINI_API_KEY
   ```
   *(You can create a Gemini API key in your browser at **[Google AI Studio](https://aistudio.google.com/apikey)**.)*
5. Copy the **Service URL** printed at the end (e.g. `https://banglasub-backend-xxxx.a.run.app`).
6. Open **BanglaSub AI** on your Android phone → **Settings** → choose **Remote FFmpeg Server (Chunked Upload)** → paste the Service URL → tap **Test Connection**.

### Option B: Cloud Run Continuous GitHub Deployment (Zero Terminal Commands)

1. Open **[Google Cloud Run](https://console.cloud.google.com/run)** in your browser → click **Create Service**.
2. Choose **Continuously deploy from a repository (Source or GitHub)** → connect your repository.
3. Under **Build Configuration**, select **Dockerfile** and set the source location to `/backend/Dockerfile` (or Cloud Build `/backend/cloudbuild.yaml`).
4. Set **Authentication** to **Allow public access (unauthenticated)**.
5. Expand **Container(s), Volumes, Networking, Security**:
   - **Memory**: `4 GiB` | **CPU**: `2` | **Request timeout**: `3600` seconds.
   - **Variables & Secrets** → Add environment variable `GEMINI_API_KEY` = your Google AI Studio key.
6. Click **Create**, copy the HTTPS URL, and paste it into **BanglaSub AI → Settings**.
