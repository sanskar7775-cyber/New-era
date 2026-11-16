const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const SANSKAR_UID = "sanskar_uid_001";
const STANDARD_USER_UID = "user_uid_002";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

test("Unauthenticated user cannot read or write any user profile or encrypted records", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(SANSKAR_UID).get());
  await assertFails(
    unauthDb
      .collection("users")
      .doc(SANSKAR_UID)
      .collection("encrypted_records")
      .get()
  );
  await assertFails(unauthDb.collection("user_directory").get());
  await assertFails(unauthDb.collection("user_activities").get());
});

test("Any authenticated user can create and read their own UserProfile and directory entry, but cannot list all users", async () => {
  const userDb = testEnv.authenticatedContext(STANDARD_USER_UID, {
    email: "alex@gmail.com",
    email_verified: true,
  }).firestore();
  const now = new Date();
  await assertSucceeds(
    userDb.collection("users").doc(STANDARD_USER_UID).set({
      userId: STANDARD_USER_UID,
      ownerName: "alex",
      aiName: "New Era",
      websiteTitle: "Alex Hub",
      voiceReplyEnabled: true,
      autoSetupCompleted: true,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(userDb.collection("users").doc(STANDARD_USER_UID).get());

  await assertSucceeds(
    userDb.collection("user_directory").doc(STANDARD_USER_UID).set({
      userId: STANDARD_USER_UID,
      username: "alex",
      authMethod: "google",
      activityCount: 1,
      lastActiveAt: now,
    })
  );

  // Regular non-owner users must NOT be able to list other users or read global activities
  await assertFails(userDb.collection("user_directory").get());
  await assertFails(userDb.collection("user_activities").get());
});

test("Owner can list user_directory and user_activities across all users", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.collection("user_directory").doc(STANDARD_USER_UID).set({
      userId: STANDARD_USER_UID,
      username: "alex",
      authMethod: "google",
      activityCount: 3,
      lastActiveAt: now,
    });
    await adminDb.collection("user_activities").doc("act_1").set({
      userId: STANDARD_USER_UID,
      username: "alex",
      actionType: "CHAT",
      summary: "Asked a question",
      timestamp: now,
    });
  });

  const ownerDb = testEnv.authenticatedContext(SANSKAR_UID, {
    email: "sanskar7775@gmail.com",
    email_verified: true,
  }).firestore();
  await assertSucceeds(ownerDb.collection("user_directory").get());
  await assertSucceeds(ownerDb.collection("user_activities").get());
});

test("Cross-user access to private encrypted_records is denied", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb
      .collection("users")
      .doc(SANSKAR_UID)
      .collection("encrypted_records")
      .doc("rec_1")
      .set({
        userId: SANSKAR_UID,
        recordType: "note",
        ciphertext: "ZW5jcnlwdGVk",
        iv: "MTIzNDU2Nzg=",
        keyVersion: 1,
        pinned: true,
        createdAt: now,
        updatedAt: now,
      });
  });

  const otherDb = testEnv.authenticatedContext(STANDARD_USER_UID, {
    email: "alex@gmail.com",
    email_verified: true,
  }).firestore();
  await assertFails(
    otherDb
      .collection("users")
      .doc(SANSKAR_UID)
      .collection("encrypted_records")
      .get()
  );
});
