// 자세 지킴이 — 카메라로 얼굴 랜드마크를 추적해 화면 거리와 고개 자세를 확인한다.
// 모든 영상 처리는 브라우저 안에서만 이루어진다.

const MP_VERSION = "0.10.14";
const MP_BASE = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${MP_VERSION}`;
const MODEL_URL =
  "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task";

const DETECT_INTERVAL_MS = 300;
const CALIBRATION_FRAMES = 15;
const BREAK_INTERVAL_MS = 20 * 60 * 1000;

// 얼굴 랜드마크 인덱스 (MediaPipe Face Mesh)
const LM = { leftCheek: 234, rightCheek: 454, leftEyeOuter: 33, rightEyeOuter: 263 };

const DEFAULTS = {
  baseDist: 55,
  minDist: 40,
  pitch: 15,
  roll: 12,
  hold: 5,
  cooldown: 30,
  sound: true,
  vibrate: true,
  notify: true,
  breakReminder: true,
};

const $ = (id) => document.getElementById(id);

// ---------- 저장소 (실패해도 동작하도록) ----------
function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? { ...fallback, ...JSON.parse(raw) } : { ...fallback };
  } catch {
    return { ...fallback };
  }
}
function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {}
}

const settings = load("posture.settings", DEFAULTS);
let baseline = (() => {
  try {
    return JSON.parse(localStorage.getItem("posture.baseline")) || null;
  } catch {
    return null;
  }
})();

const today = new Date().toISOString().slice(0, 10);
let stats = load("posture.stats", { day: today, measuredMs: 0, goodMs: 0, alerts: 0 });
if (stats.day !== today) stats = { day: today, measuredMs: 0, goodMs: 0, alerts: 0 };

// ---------- 설정 UI ----------
const settingInputs = {
  baseDist: $("setBaseDist"),
  minDist: $("setMinDist"),
  pitch: $("setPitch"),
  roll: $("setRoll"),
  hold: $("setHold"),
  cooldown: $("setCooldown"),
  sound: $("setSound"),
  vibrate: $("setVibrate"),
  notify: $("setNotify"),
  breakReminder: $("setBreak"),
};
for (const [key, input] of Object.entries(settingInputs)) {
  if (input.type === "checkbox") input.checked = settings[key];
  else input.value = settings[key];
  input.addEventListener("change", () => {
    if (input.type === "checkbox") {
      settings[key] = input.checked;
      if (key === "notify" && input.checked) requestNotificationPermission();
    } else {
      const n = Number(input.value);
      if (Number.isFinite(n) && n > 0) settings[key] = n;
      else input.value = settings[key];
    }
    save("posture.settings", settings);
  });
}

// ---------- 상태 ----------
let landmarker = null;
const video = $("video");
let stream = null;
let timer = null;
let lastTick = 0;
let calibrating = null; // { samples: [] }
let previewHidden = false;
const badSince = {}; // 문제 종류 -> 시작 시각
const lastAlert = {}; // 문제 종류 -> 마지막 알림 시각
let faceSeenMs = 0; // 눈 휴식 타이머용 (얼굴이 보인 누적 시간)

// ---------- 얼굴 측정 ----------
function measure(result) {
  const lms = result.faceLandmarks?.[0];
  if (!lms) return null;
  const w = video.videoWidth;
  const h = video.videoHeight;
  const px = (p) => ({ x: p.x * w, y: p.y * h });

  const lc = px(lms[LM.leftCheek]);
  const rc = px(lms[LM.rightCheek]);
  const faceWidth = Math.hypot(rc.x - lc.x, rc.y - lc.y);

  const le = px(lms[LM.leftEyeOuter]);
  const re = px(lms[LM.rightEyeOuter]);
  let roll = (Math.atan2(re.y - le.y, re.x - le.x) * 180) / Math.PI;

  // 머리 회전 행렬에서 pitch(위아래) 추출. 행렬은 column-major 4x4.
  let pitch = 0;
  const m = result.facialTransformationMatrixes?.[0]?.data;
  if (m) {
    const r21 = m[1 * 4 + 2];
    const r22 = m[2 * 4 + 2];
    pitch = (Math.atan2(r21, r22) * 180) / Math.PI;
    const r10 = m[0 * 4 + 1];
    const r00 = m[0];
    roll = (Math.atan2(r10, r00) * 180) / Math.PI;
  }
  return { faceWidth, roll, pitch };
}

function angleDiff(a, b) {
  let d = a - b;
  while (d > 180) d -= 360;
  while (d < -180) d += 360;
  return d;
}

function evaluate(m) {
  const distance = (settings.baseDist * baseline.faceWidth) / m.faceWidth;
  const pitch = angleDiff(m.pitch, baseline.pitch);
  const roll = angleDiff(m.roll, baseline.roll);

  const problems = [];
  if (distance < settings.minDist) {
    problems.push({ key: "close", msg: `화면과 너무 가까워요 (약 ${Math.round(distance)}cm). 조금 뒤로 물러나 주세요.` });
  }
  if (pitch > settings.pitch) {
    problems.push({ key: "down", msg: "고개를 너무 숙이고 있어요. 턱을 당기고 화면을 눈높이로 올려주세요." });
  } else if (pitch < -settings.pitch * 1.5) {
    problems.push({ key: "up", msg: "고개가 너무 뒤로 젖혀져 있어요. 화면 높이를 확인해 주세요." });
  }
  if (Math.abs(roll) > settings.roll) {
    problems.push({ key: "tilt", msg: "고개가 한쪽으로 기울어 있어요. 똑바로 앉아주세요." });
  }
  return { distance, pitch, roll, problems };
}

// ---------- 알림 ----------
let audioCtx = null;
function beep() {
  try {
    audioCtx ??= new AudioContext();
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.frequency.value = 660;
    gain.gain.setValueAtTime(0.15, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.4);
    osc.connect(gain).connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.4);
  } catch {}
}

let toastTimer = null;
function toast(msg, info = false) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.toggle("info", info);
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 5000);
}

let swReg = null;
async function requestNotificationPermission() {
  if (!("Notification" in window)) return;
  if (Notification.permission === "default") {
    try {
      await Notification.requestPermission();
    } catch {}
  }
}

async function systemNotify(msg) {
  if (!("Notification" in window) || Notification.permission !== "granted") return;
  const opts = { body: msg, icon: "icon.svg", tag: "posture", renotify: true };
  try {
    if (swReg) await swReg.showNotification("자세 지킴이", opts);
    else new Notification("자세 지킴이", opts);
  } catch {}
}

function alertUser(msg, info = false) {
  toast(msg, info);
  if (settings.sound) beep();
  if (settings.vibrate && navigator.vibrate) navigator.vibrate(info ? [100] : [200, 100, 200]);
  if (settings.notify && document.visibilityState === "hidden") systemNotify(msg);
}

// ---------- 화면 갱신 ----------
function setStatus(level, label, detail) {
  $("status").dataset.level = level;
  $("statusLabel").textContent = label;
  $("statusDetail").textContent = detail;
}

function renderStats() {
  $("sTime").textContent = `${Math.floor(stats.measuredMs / 60000)}분`;
  $("sGood").textContent = stats.measuredMs > 5000 ? `${Math.round((stats.goodMs / stats.measuredMs) * 100)}%` : "–";
  $("sAlerts").textContent = String(stats.alerts);
}

function renderMetrics(ev) {
  $("mDistance").textContent = ev ? `${Math.round(ev.distance)}cm` : "–";
  $("mPitch").textContent = ev ? `${Math.round(ev.pitch)}°` : "–";
  $("mRoll").textContent = ev ? `${Math.round(Math.abs(ev.roll))}°` : "–";
}

// ---------- 메인 루프 ----------
function tick() {
  if (!landmarker || video.readyState < 2) return;
  const now = performance.now();
  const dt = lastTick ? Math.min(now - lastTick, 5000) : 0;
  lastTick = now;

  let result;
  try {
    result = landmarker.detectForVideo(video, now);
  } catch (e) {
    console.error(e);
    return;
  }
  const m = measure(result);

  if (calibrating) {
    if (m) calibrating.samples.push(m);
    const left = CALIBRATION_FRAMES - calibrating.samples.length;
    setStatus("idle", "기준 자세 저장 중…", left > 0 ? "그대로 바른 자세를 유지해 주세요." : "");
    if (left <= 0) finishCalibration();
    return;
  }

  if (!m) {
    renderMetrics(null);
    setStatus("idle", "얼굴이 안 보여요", "자리를 비웠거나 카메라 각도를 확인해 주세요.");
    for (const k in badSince) delete badSince[k];
    return;
  }

  if (!baseline) {
    setStatus("idle", "기준 자세가 필요해요", "바른 자세로 앉아서 ‘기준 자세 저장’을 눌러주세요.");
    return;
  }

  const ev = evaluate(m);
  renderMetrics(ev);

  stats.measuredMs += dt;
  faceSeenMs += dt;
  const activeKeys = new Set(ev.problems.map((p) => p.key));
  for (const k of Object.keys(badSince)) if (!activeKeys.has(k)) delete badSince[k];

  if (ev.problems.length === 0) {
    stats.goodMs += dt;
    setStatus("good", "좋은 자세예요 👍", "지금처럼 유지해 주세요.");
  } else {
    const holdMs = settings.hold * 1000;
    let worst = "warn";
    for (const p of ev.problems) {
      badSince[p.key] ??= now;
      if (now - badSince[p.key] >= holdMs) {
        worst = "bad";
        if (!lastAlert[p.key] || now - lastAlert[p.key] >= settings.cooldown * 1000) {
          lastAlert[p.key] = now;
          stats.alerts += 1;
          alertUser(p.msg);
        }
      }
    }
    setStatus(worst, worst === "bad" ? "자세를 고쳐주세요" : "자세가 흐트러지고 있어요", ev.problems[0].msg);
  }

  if (settings.breakReminder && faceSeenMs >= BREAK_INTERVAL_MS) {
    faceSeenMs = 0;
    alertUser("20분이 지났어요. 20초 동안 6m 이상 먼 곳을 바라보며 눈을 쉬어주세요.", true);
  }

  renderStats();
  save("posture.stats", stats);
}

function finishCalibration() {
  const s = calibrating.samples;
  calibrating = null;
  const avg = (f) => s.reduce((a, x) => a + f(x), 0) / s.length;
  baseline = {
    faceWidth: avg((x) => x.faceWidth),
    pitch: avg((x) => x.pitch),
    roll: avg((x) => x.roll),
  };
  save("posture.baseline", baseline);
  for (const k in badSince) delete badSince[k];
  toast("기준 자세를 저장했어요. 이제부터 자세를 지켜볼게요.", true);
}

// ---------- 시작 ----------
async function loadLandmarker() {
  const { FaceLandmarker, FilesetResolver } = await import(`${MP_BASE}/vision_bundle.mjs`);
  const fileset = await FilesetResolver.forVisionTasks(`${MP_BASE}/wasm`);
  const opts = (delegate) => ({
    baseOptions: { modelAssetPath: MODEL_URL, delegate },
    runningMode: "VIDEO",
    numFaces: 1,
    outputFacialTransformationMatrixes: true,
  });
  try {
    return await FaceLandmarker.createFromOptions(fileset, opts("GPU"));
  } catch {
    return await FaceLandmarker.createFromOptions(fileset, opts("CPU"));
  }
}

async function start() {
  const btn = $("startBtn");
  btn.disabled = true;
  try {
    setStatus("idle", "카메라 켜는 중…", "카메라 권한을 허용해 주세요.");
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } },
      audio: false,
    });
    video.srcObject = stream;
    await video.play();
    $("placeholder").hidden = true;

    if (!landmarker) {
      setStatus("idle", "얼굴 인식 준비 중…", "처음 한 번은 모델을 내려받느라 몇 초 걸려요.");
      landmarker = await loadLandmarker();
    }

    if (settings.notify) requestNotificationPermission();
    lastTick = 0;
    timer = setInterval(tick, DETECT_INTERVAL_MS);
    btn.textContent = "중지";
    btn.classList.remove("primary");
    btn.onclick = stop;
    $("calibrateBtn").disabled = false;
    $("previewBtn").disabled = false;
    setStatus("idle", baseline ? "측정 중" : "기준 자세가 필요해요", baseline ? "" : "바른 자세로 앉아서 ‘기준 자세 저장’을 눌러주세요.");
  } catch (e) {
    console.error(e);
    stopStream();
    const denied = e && (e.name === "NotAllowedError" || e.name === "SecurityError");
    setStatus(
      "bad",
      denied ? "카메라 권한이 필요해요" : "시작하지 못했어요",
      denied ? "브라우저 주소창의 카메라 권한을 허용한 뒤 다시 시도해 주세요." : String(e?.message || e),
    );
  } finally {
    btn.disabled = false;
  }
}

function stopStream() {
  clearInterval(timer);
  timer = null;
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  video.srcObject = null;
  $("placeholder").hidden = false;
}

function stop() {
  stopStream();
  const btn = $("startBtn");
  btn.textContent = "시작";
  btn.classList.add("primary");
  btn.onclick = start;
  $("calibrateBtn").disabled = true;
  $("previewBtn").disabled = true;
  renderMetrics(null);
  setStatus("idle", "멈춤", "다시 시작하려면 ‘시작’을 눌러주세요.");
}

$("startBtn").onclick = start;
$("calibrateBtn").onclick = () => {
  calibrating = { samples: [] };
};
$("previewBtn").onclick = () => {
  previewHidden = !previewHidden;
  $("cameraBox").classList.toggle("hidden-preview", previewHidden);
  $("previewBtn").textContent = previewHidden ? "미리보기 보이기" : "미리보기 숨기기";
};

if ("serviceWorker" in navigator) {
  navigator.serviceWorker
    .register("sw.js")
    .then((reg) => (swReg = reg))
    .catch(() => {});
}

renderStats();
if (baseline) {
  setStatus("idle", "대기 중", "저장된 기준 자세가 있어요. ‘시작’을 누르면 바로 측정해요.");
}
