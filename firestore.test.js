const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProjectPayload(uid) {
  return {
    userId: uid,
    title: "Tech Keynote 2026",
    fileName: "keynote.mp4",
    mimeType: "video/mp4",
    fileSizeBytes: 104857600,
    durationMs: 900000,
    status: "queued",
    progressPercent: 0,
    processingMode: "cloud_ai",
    cueCount: 0,
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot list or create projects", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/projects`).get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/projects`).doc("proj_1").set(validProjectPayload(ALICE_UID))
  );
});

test("Authenticated owner can create, list, update, and delete own project", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection(`users/${ALICE_UID}/projects`).doc("proj_1");
  await assertSucceeds(docRef.set(validProjectPayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/projects`).get());
  await assertSucceeds(
    docRef.update({
      status: "ready",
      progressPercent: 100,
      cueCount: 42,
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(docRef.delete());
});

test("Cross-user access is denied for reading, writing, and deleting projects", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/projects`).doc("proj_alice").set(validProjectPayload(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/projects`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/projects`).doc("proj_alice").get());
  await assertFails(
    bobDb.collection(`users/${ALICE_UID}/projects`).doc("proj_alice").update({
      title: "Hijacked",
      updatedAt: new Date(),
    })
  );
  await assertFails(bobDb.collection(`users/${ALICE_UID}/projects`).doc("proj_alice").delete());
});

test("Shadow update with ghost field or invalid status enum is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection(`users/${ALICE_UID}/projects`).doc("proj_shadow");
  await assertSucceeds(docRef.set(validProjectPayload(ALICE_UID)));

  await assertFails(
    docRef.update({
      isVerified: true,
      updatedAt: new Date(),
    })
  );

  await assertFails(
    docRef.update({
      status: "hacked_status",
      updatedAt: new Date(),
    })
  );
});

test("CuePage requires existing parent project and enforces owner isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const orphanPageRef = aliceDb.collection(`users/${ALICE_UID}/projects/missing_proj/cuePages`).doc("page_0");
  await assertFails(
    orphanPageRef.set({
      userId: ALICE_UID,
      projectId: "missing_proj",
      pageIndex: 0,
      cues: [{ id: "1", index: 1, startMs: 0, endMs: 2500, englishText: "Hello", bengaliText: "হ্যালো" }],
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/projects`).doc("proj_with_cues").set(validProjectPayload(ALICE_UID))
  );
  const validPageRef = aliceDb.collection(`users/${ALICE_UID}/projects/proj_with_cues/cuePages`).doc("page_0");
  await assertSucceeds(
    validPageRef.set({
      userId: ALICE_UID,
      projectId: "proj_with_cues",
      pageIndex: 0,
      cues: [{ id: "1", index: 1, startMs: 0, endMs: 2500, englishText: "Hello", bengaliText: "হ্যালো" }],
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  await assertSucceeds(
    validPageRef.update({
      cues: [{ id: "1", index: 1, startMs: 0, endMs: 2500, englishText: "Hello", bengaliText: "নমস্কার" }],
      updatedAt: new Date(),
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/projects/proj_with_cues/cuePages`).get());
});
