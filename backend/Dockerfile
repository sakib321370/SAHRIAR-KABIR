FROM node:22-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libass9 \
    fonts-noto-core \
    fonts-noto-ui-core \
    fontconfig \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY fonts ./fonts
RUN fc-cache -f -v
COPY subtitles.js server.js ./

ENV PORT=8080
EXPOSE 8080

CMD ["npm", "start"]
