(() => {
  "use strict";

  // ---- DOM ----
  const tuneBtn = document.getElementById("tuneBtn");
  const tuneBtnLabel = document.getElementById("tuneBtnLabel");
  const freqSlider = document.getElementById("freqSlider");
  const freqValue = document.getElementById("freqValue");
  const canvas = document.getElementById("visualizer");
  const ctx = canvas.getContext("2d");
  const statusValue = document.getElementById("statusValue");
  const streakValue = document.getElementById("streakValue");
  const levelBtn = document.getElementById("levelBtn");
  const levelBtnText = document.getElementById("levelBtnText");
  const menuOverlay = document.getElementById("menuOverlay");
  const levelListEl = document.getElementById("levelList");
  const clearDataBtn = document.getElementById("clearDataBtn");
  const menuCloseBtn = document.getElementById("menuCloseBtn");

  // ---- Levels / progression ----
  // Each level tightens the beat range and/or the tap accuracy window, and
  // the higher ones layer a syncopated rhythm on top via a period-multiplier
  // pattern that cycles once per beat.
  const ROMAN = ["I", "II", "III", "IV", "V"];
  const LEVELS = [
    { name: "CARRIER WAVE", beatPeriodMinMs: 350, beatPeriodMaxMs: 1000, accuracyWindowMs: 100, rhythmPattern: [1] },
    { name: "HARMONIC DRIFT", beatPeriodMinMs: 300, beatPeriodMaxMs: 900, accuracyWindowMs: 90, rhythmPattern: [1] },
    { name: "PHASE JITTER", beatPeriodMinMs: 260, beatPeriodMaxMs: 800, accuracyWindowMs: 80, rhythmPattern: [1, 0.65, 1.35] },
    { name: "QUANTUM NOISE", beatPeriodMinMs: 220, beatPeriodMaxMs: 700, accuracyWindowMs: 65, rhythmPattern: [0.8, 1.3, 0.6, 1.3] },
    { name: "SINGULARITY", beatPeriodMinMs: 180, beatPeriodMaxMs: 600, accuracyWindowMs: 50, rhythmPattern: [1, 0.5, 0.5, 1.5, 0.75, 0.75] },
  ];
  const UNLOCK_STREAK_THRESHOLD = 5;

  let currentLevelIndex = 0;

  // ---- Persistent save data (localStorage) ----
  const SAVE_KEY = "signalStabilizer.save.v1";

  function defaultSaveData() {
    return {
      unlocked: LEVELS.map((_, i) => i === 0),
      bestStreaks: LEVELS.map(() => 0),
    };
  }

  function loadSaveData() {
    try {
      const raw = localStorage.getItem(SAVE_KEY);
      if (!raw) return defaultSaveData();
      const parsed = JSON.parse(raw);
      if (
        !parsed ||
        !Array.isArray(parsed.unlocked) ||
        !Array.isArray(parsed.bestStreaks) ||
        parsed.unlocked.length !== LEVELS.length ||
        parsed.bestStreaks.length !== LEVELS.length
      ) {
        return defaultSaveData();
      }
      parsed.unlocked[0] = true;
      return parsed;
    } catch (err) {
      return defaultSaveData();
    }
  }

  function persistSaveData() {
    try {
      localStorage.setItem(SAVE_KEY, JSON.stringify(saveData));
    } catch (err) {
      /* storage unavailable (private mode / quota) — progress just won't persist */
    }
  }

  function clearAllSaveData() {
    try {
      localStorage.removeItem(SAVE_KEY);
    } catch (err) {
      /* ignore */
    }
    saveData = defaultSaveData();
    endSession();
    if (currentLevelIndex !== 0) {
      currentLevelIndex = 0;
      updateLevelBtnText();
    }
    renderLevelList();
  }

  let saveData = loadSaveData();

  // ---- Audio state (created lazily on first user gesture) ----
  let audioCtx = null;
  let oscillator = null;
  let masterGain = null;
  let fifthOsc = null;
  let octaveOsc = null;
  let harmonyGain = null;
  let audioActive = false;

  const TONE_GAIN = 0.18;
  const RAMP_SECONDS = 0.05;

  // A quiet fifth + octave layer, anchored to the hidden target frequency,
  // that fades in and resolves from a detuned shimmer into pure just-
  // intonation the longer the player's perfect-tap streak runs.
  const HARMONY_GAIN_MAX = 0.09;
  const CHORD_STREAK_CAP = 8;
  const HARMONY_MAX_DETUNE_CENTS = 40;
  const MAIN_MAX_DETUNE_CENTS = 12;
  const CHORD_RAMP_SECONDS = 0.15;

  // ---- Tuning state (pitch-matching drives beat difficulty below) ----
  const FREQ_MIN = Number(freqSlider.min);
  const FREQ_MAX = Number(freqSlider.max);
  const UNSTABLE_RATIO = 0.35; // deviation ratio at/beyond which the beat is fastest/most erratic

  let targetFreq = randomTarget();

  function randomTarget() {
    return Math.round(FREQ_MIN + Math.random() * (FREQ_MAX - FREQ_MIN));
  }

  function deviationRatio(current, target) {
    return Math.abs(current - target) / target;
  }

  // ---- Audio setup ----
  function ensureAudioContext() {
    if (!audioCtx) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      audioCtx = new Ctx();
    }
    if (audioCtx.state === "suspended") {
      audioCtx.resume();
    }
    return audioCtx;
  }

  // Creates (or recreates) a live sine oscillator routed through a gain node
  // used purely as a click-free volume envelope, plus a quiet fifth+octave
  // harmony layer anchored to the hidden target frequency for the chord swell.
  function startTone(frequencyHz) {
    const ctx = ensureAudioContext();

    masterGain = ctx.createGain();
    masterGain.gain.value = 0;
    masterGain.connect(ctx.destination);

    oscillator = ctx.createOscillator();
    oscillator.type = "sine";
    oscillator.frequency.value = frequencyHz;
    oscillator.detune.value = MAIN_MAX_DETUNE_CENTS;
    oscillator.connect(masterGain);
    oscillator.start();

    harmonyGain = ctx.createGain();
    harmonyGain.gain.value = 0;
    harmonyGain.connect(ctx.destination);

    fifthOsc = ctx.createOscillator();
    fifthOsc.type = "sine";
    fifthOsc.frequency.value = targetFreq * 1.5; // perfect fifth above the target
    fifthOsc.detune.value = HARMONY_MAX_DETUNE_CENTS;
    fifthOsc.connect(harmonyGain);
    fifthOsc.start();

    octaveOsc = ctx.createOscillator();
    octaveOsc.type = "sine";
    octaveOsc.frequency.value = targetFreq * 2; // octave above the target
    octaveOsc.detune.value = -HARMONY_MAX_DETUNE_CENTS;
    octaveOsc.connect(harmonyGain);
    octaveOsc.start();

    const now = ctx.currentTime;
    masterGain.gain.setTargetAtTime(TONE_GAIN, now, RAMP_SECONDS);

    audioActive = true;
  }

  function fadeAndStop(osc, gain, now) {
    if (!osc || !gain) return;
    gain.gain.setTargetAtTime(0, now, RAMP_SECONDS);
    window.setTimeout(() => {
      try {
        osc.stop();
      } catch (err) {
        /* already stopped */
      }
      osc.disconnect();
      gain.disconnect();
    }, RAMP_SECONDS * 1000 * 6);
  }

  // octaveOsc shares harmonyGain's fade with fifthOsc, so it just needs its
  // own delayed stop/disconnect once that fade completes.
  function stopOscOnly(osc) {
    if (!osc) return;
    window.setTimeout(() => {
      try {
        osc.stop();
      } catch (err) {
        /* already stopped */
      }
      osc.disconnect();
    }, RAMP_SECONDS * 1000 * 6);
  }

  function stopTone() {
    if (!audioCtx) return;
    const now = audioCtx.currentTime;

    fadeAndStop(oscillator, masterGain, now);
    fadeAndStop(fifthOsc, harmonyGain, now);
    stopOscOnly(octaveOsc);

    oscillator = null;
    masterGain = null;
    fifthOsc = null;
    octaveOsc = null;
    harmonyGain = null;
    audioActive = false;
  }

  function setToneFrequency(hz) {
    if (oscillator && audioCtx) {
      oscillator.frequency.setTargetAtTime(hz, audioCtx.currentTime, 0.02);
    }
  }

  // Smoothly resolves the harmony layer's detune (dissonant -> pure) and
  // gain (silent -> audible), plus the main tone's own shimmer, based on how
  // close the player is to a full chord-reward streak.
  function updateChordForStreak(streak) {
    if (!audioCtx || !harmonyGain || !fifthOsc || !octaveOsc || !oscillator) return;
    const closeness = Math.min(streak / CHORD_STREAK_CAP, 1);
    const now = audioCtx.currentTime;

    harmonyGain.gain.setTargetAtTime(closeness * HARMONY_GAIN_MAX, now, CHORD_RAMP_SECONDS);
    fifthOsc.detune.setTargetAtTime(HARMONY_MAX_DETUNE_CENTS * (1 - closeness), now, CHORD_RAMP_SECONDS);
    octaveOsc.detune.setTargetAtTime(-HARMONY_MAX_DETUNE_CENTS * (1 - closeness), now, CHORD_RAMP_SECONDS);
    oscillator.detune.setTargetAtTime(MAIN_MAX_DETUNE_CENTS * (1 - closeness), now, CHORD_RAMP_SECONDS);
  }

  // ---- Beat clock ----
  // Beat period ranges between the active level's min/max ms as the player's
  // slider frequency approaches the hidden target, then gets multiplied by
  // the level's rhythm pattern (1 for a steady beat, varied values for a
  // syncopated one) — pitch accuracy is what makes the beat hittable at all.
  let beatIndex = 0;

  function currentBeatPeriodMs() {
    const level = LEVELS[currentLevelIndex];
    const dev = deviationRatio(Number(freqSlider.value), targetFreq);
    const closeness = 1 - Math.min(dev / UNSTABLE_RATIO, 1);
    const base = level.beatPeriodMinMs + (level.beatPeriodMaxMs - level.beatPeriodMinMs) * closeness;
    const pattern = level.rhythmPattern;
    return base * pattern[beatIndex % pattern.length];
  }

  let cycleStart = null; // performance.now() timestamp of the last peak
  let cyclePeriod = null; // ms duration of the current beat cycle
  let lastPeakTimestamp = null; // most recent peak that has already occurred
  let rafId = null;
  let isSuspended = false; // true while backgrounded/menu-open but session preserved

  // ---- Tap accuracy ----
  const BURST_DURATION_MS = 350;

  const COLOR_ACCENT = "51,240,200";
  const COLOR_DANGER = "255,61,94";
  const COLOR_DIM = "124,138,141";

  let tapStreak = 0;
  let burst = null; // { startTime, color } — brief hit/miss feedback ring
  let statusRevertTimer = null;

  // ---- Haptics ----
  const supportsVibration = "vibrate" in navigator;
  const VIBRATE_PERFECT_MS = 20;
  const VIBRATE_DRIFT_PATTERN = [40, 60, 40]; // buzz, pause, buzz

  function vibrate(pattern) {
    if (!supportsVibration) return;
    try {
      navigator.vibrate(pattern);
    } catch (err) {
      /* vibration not permitted in this context; ignore */
    }
  }

  function handleTap(e) {
    if (!audioActive || cycleStart === null) return;
    e.preventDefault();

    const level = LEVELS[currentLevelIndex];
    const tapTime = e.timeStamp; // DOMHighResTimeStamp, same epoch as performance.now()
    const upcomingPeak = cycleStart + cyclePeriod;
    const candidates = [lastPeakTimestamp, upcomingPeak].filter((t) => t !== null);
    const nearestPeak = candidates.reduce((best, t) =>
      Math.abs(tapTime - t) < Math.abs(tapTime - best) ? t : best
    );
    const delta = tapTime - nearestPeak; // signed ms: negative = early, positive = late

    if (Math.abs(delta) <= level.accuracyWindowMs) {
      tapStreak += 1;
      burst = { startTime: tapTime, color: COLOR_ACCENT };
      vibrate(VIBRATE_PERFECT_MS);

      let unlockedNew = false;
      let saveChanged = false;
      if (tapStreak > saveData.bestStreaks[currentLevelIndex]) {
        saveData.bestStreaks[currentLevelIndex] = tapStreak;
        saveChanged = true;
      }
      if (tapStreak >= UNLOCK_STREAK_THRESHOLD) {
        const nextIndex = currentLevelIndex + 1;
        if (nextIndex < LEVELS.length && !saveData.unlocked[nextIndex]) {
          saveData.unlocked[nextIndex] = true;
          unlockedNew = true;
          saveChanged = true;
        }
      }
      if (saveChanged) persistSaveData();

      flashStatus(unlockedNew ? "SIGNAL UNLOCKED" : "PERFECT ALIGNMENT", "locked");
    } else {
      tapStreak = 0;
      burst = { startTime: tapTime, color: COLOR_DANGER };
      flashStatus("SIGNAL DRIFT", "danger");
      vibrate(VIBRATE_DRIFT_PATTERN);
    }
    streakValue.textContent = String(tapStreak);
    updateChordForStreak(tapStreak);
  }

  function flashStatus(text, mode) {
    setStatus(text, mode);
    clearTimeout(statusRevertTimer);
    statusRevertTimer = window.setTimeout(() => {
      if (audioActive && !isSuspended) setStatus("LISTENING");
    }, 650);
  }

  function setStatus(text, mode) {
    statusValue.textContent = text;
    statusValue.classList.remove("danger", "locked");
    if (mode === "danger") statusValue.classList.add("danger");
    if (mode === "locked") statusValue.classList.add("locked");
  }

  // ---- Canvas rendering ----
  // Metrics are cached on resize rather than read from canvas.clientWidth/
  // Height every animation frame, so the 60fps rAF loop never forces a
  // synchronous layout read on mobile.
  let vizW = 0;
  let vizH = 0;
  let vizCX = 0;
  let vizCY = 0;
  let vizMaxR = 0;

  function setupCanvas() {
    const dpr = window.devicePixelRatio || 1;
    vizW = canvas.clientWidth;
    vizH = canvas.clientHeight;
    canvas.width = vizW * dpr;
    canvas.height = vizH * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    vizCX = vizW / 2;
    vizCY = vizH / 2;
    vizMaxR = (Math.min(vizW, vizH) / 2) * 0.82;
  }

  function draw(now, phase) {
    const w = vizW;
    const h = vizH;
    const cx = vizCX;
    const cy = vizCY;
    const maxR = vizMaxR;

    ctx.clearRect(0, 0, w, h);

    // Static target ring
    ctx.beginPath();
    ctx.arc(cx, cy, maxR, 0, Math.PI * 2);
    ctx.strokeStyle = `rgba(${COLOR_DIM},0.35)`;
    ctx.lineWidth = 2;
    ctx.stroke();

    // Animated ring approaching the target, peaking exactly on the beat
    const eased = Math.pow(phase, 0.6);
    const r = Math.max(4, maxR * eased);
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.lineWidth = 3;
    ctx.strokeStyle = `rgba(${COLOR_ACCENT},${0.25 + 0.75 * eased})`;
    ctx.stroke();

    // Center core, swelling slightly as it approaches the peak
    const coreR = 16 + 10 * eased;
    ctx.beginPath();
    ctx.arc(cx, cy, coreR, 0, Math.PI * 2);
    ctx.fillStyle = `rgba(${COLOR_ACCENT},${0.2 + 0.5 * eased})`;
    ctx.fill();

    // Hit/miss feedback burst
    if (burst) {
      const age = now - burst.startTime;
      if (age >= 0 && age < BURST_DURATION_MS) {
        const t = age / BURST_DURATION_MS;
        ctx.beginPath();
        ctx.arc(cx, cy, maxR + t * 22, 0, Math.PI * 2);
        ctx.lineWidth = 4;
        ctx.strokeStyle = `rgba(${burst.color},${1 - t})`;
        ctx.stroke();
      } else if (age >= BURST_DURATION_MS) {
        burst = null;
      }
    }
  }

  function renderIdle() {
    draw(performance.now(), 0);
  }

  function renderFrame(now) {
    if (!audioActive || isSuspended) return;

    if (cycleStart === null) {
      cycleStart = now;
      cyclePeriod = currentBeatPeriodMs();
    }

    let elapsed = now - cycleStart;
    let iterations = 0;
    while (elapsed >= cyclePeriod && iterations < 10) {
      lastPeakTimestamp = cycleStart + cyclePeriod;
      cycleStart = lastPeakTimestamp;
      beatIndex += 1;
      cyclePeriod = currentBeatPeriodMs();
      elapsed = now - cycleStart;
      iterations += 1;
    }

    const phase = cyclePeriod > 0 ? Math.min(elapsed / cyclePeriod, 1) : 0;
    draw(now, phase);
    rafId = requestAnimationFrame(renderFrame);
  }

  // ---- Session lifecycle ----
  function startSession() {
    ensureAudioContext();
    targetFreq = randomTarget();
    startTone(Number(freqSlider.value));

    tuneBtn.setAttribute("aria-pressed", "true");
    tuneBtnLabel.textContent = "STOP";
    setStatus("LISTENING");

    tapStreak = 0;
    streakValue.textContent = "0";
    cycleStart = null;
    cyclePeriod = null;
    lastPeakTimestamp = null;
    beatIndex = 0;
    burst = null;
    isSuspended = false;
    updateChordForStreak(0);

    rafId = requestAnimationFrame(renderFrame);
  }

  function endSession() {
    stopTone();
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    clearTimeout(statusRevertTimer);
    isSuspended = false;

    tuneBtn.setAttribute("aria-pressed", "false");
    tuneBtnLabel.textContent = "TUNE";
    setStatus("STANDBY");
    renderIdle();
  }

  // Pauses audio + rendering without tearing the session down, so a
  // background/lock-screen interruption (or the level menu opening) can
  // resume exactly where it left off — streak, target pitch, and level all
  // preserved. Distinct from endSession(), which is a full teardown.
  function suspendSession() {
    if (!audioActive || isSuspended) return;
    isSuspended = true;
    if (audioCtx && audioCtx.state === "running") {
      audioCtx.suspend().catch(() => {});
    }
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    setStatus("SUSPENDED");
  }

  function resumeSession() {
    if (!audioActive || !isSuspended) return;
    isSuspended = false;
    if (audioCtx && audioCtx.state === "suspended") {
      audioCtx.resume().catch(() => {});
    }
    // Re-anchor the beat clock instead of "catching up" on every beat that
    // would have happened while backgrounded.
    cycleStart = null;
    setStatus("LISTENING");
    rafId = requestAnimationFrame(renderFrame);
  }

  // ---- Level select menu ----
  let menuHideTimer = null;

  function openMenu() {
    renderLevelList();
    clearTimeout(menuHideTimer);
    menuOverlay.hidden = false;
    void menuOverlay.offsetWidth; // force layout so the transition below runs
    menuOverlay.classList.add("open");
    suspendSession();
  }

  function closeMenu() {
    menuOverlay.classList.remove("open");
    clearTimeout(menuHideTimer);
    menuHideTimer = window.setTimeout(() => {
      menuOverlay.hidden = true;
    }, 240);
    resumeSession();
  }

  function updateLevelBtnText() {
    const level = LEVELS[currentLevelIndex];
    levelBtnText.textContent = `SIGNAL ${ROMAN[currentLevelIndex]} · ${level.name}`;
  }

  function renderLevelList() {
    levelListEl.innerHTML = "";
    LEVELS.forEach((level, index) => {
      const unlocked = saveData.unlocked[index];
      const best = saveData.bestStreaks[index];

      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "level-row" + (index === currentLevelIndex ? " selected" : "");
      btn.disabled = !unlocked;

      const nameEl = document.createElement("span");
      nameEl.className = "level-row-name";
      nameEl.textContent = `SIGNAL ${ROMAN[index]} · ${level.name}`;

      const metaEl = document.createElement("span");
      metaEl.className = "level-row-meta";
      metaEl.textContent = unlocked
        ? `BEST STREAK ${best}`
        : `LOCKED — REACH A STREAK OF ${UNLOCK_STREAK_THRESHOLD} TO UNLOCK`;

      btn.appendChild(nameEl);
      btn.appendChild(metaEl);
      btn.addEventListener("click", () => selectLevel(index));

      li.appendChild(btn);
      levelListEl.appendChild(li);
    });
  }

  function selectLevel(index) {
    if (!saveData.unlocked[index]) return;
    const changed = index !== currentLevelIndex;
    currentLevelIndex = index;
    updateLevelBtnText();
    if (changed) endSession();
    closeMenu();
  }

  let clearArmed = false;
  let clearArmTimer = null;

  function disarmClear() {
    clearArmed = false;
    clearDataBtn.textContent = "CLEAR SAVE DATA";
    clearDataBtn.classList.remove("armed");
  }

  clearDataBtn.addEventListener("click", () => {
    if (!clearArmed) {
      clearArmed = true;
      clearDataBtn.textContent = "TAP AGAIN TO CONFIRM";
      clearDataBtn.classList.add("armed");
      clearArmTimer = window.setTimeout(disarmClear, 3000);
    } else {
      clearTimeout(clearArmTimer);
      disarmClear();
      clearAllSaveData();
    }
  });

  levelBtn.addEventListener("click", openMenu);
  menuCloseBtn.addEventListener("click", closeMenu);
  menuOverlay.addEventListener("click", (e) => {
    if (e.target === menuOverlay) closeMenu();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !menuOverlay.hidden) closeMenu();
  });

  // ---- UI wiring ----
  function updateFreqDisplay() {
    freqValue.textContent = freqSlider.value;
  }

  freqSlider.addEventListener("input", () => {
    updateFreqDisplay();
    setToneFrequency(Number(freqSlider.value));
  });

  canvas.addEventListener("pointerdown", handleTap);

  tuneBtn.addEventListener("click", () => {
    if (!audioActive) {
      startSession();
    } else {
      endSession();
    }
  });

  // ---- Android / mobile lifecycle ----
  // On backgrounding (tab hidden, phone locked, incoming call, app
  // minimized) we suspend the AudioContext outright and cancel the render
  // loop rather than relying on the browser to throttle rAF — this halts
  // audio processing and rendering immediately for battery, and resumes
  // seamlessly the moment the app is foregrounded again.
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      suspendSession();
    } else {
      resumeSession();
    }
  });

  // Capacitor native app-state integration. This project ships zero
  // external dependencies and never bundles Capacitor itself — but when
  // this same index.html/app.js is packaged inside a Capacitor-wrapped
  // Android app, the native shell injects `window.Capacitor` at runtime.
  // We opportunistically hook into it, since a native WebView's pause/
  // resume events are a more reliable background signal than
  // visibilitychange alone (e.g. on an incoming phone call).
  (function wireCapacitorLifecycle() {
    const Capacitor = window.Capacitor;
    const AppPlugin = Capacitor && Capacitor.Plugins && Capacitor.Plugins.App;
    if (!AppPlugin || typeof AppPlugin.addListener !== "function") return;

    AppPlugin.addListener("appStateChange", (state) => {
      if (state && state.isActive) {
        resumeSession();
      } else {
        suspendSession();
      }
    });
    AppPlugin.addListener("pause", suspendSession);
    AppPlugin.addListener("resume", resumeSession);
  })();

  window.addEventListener("resize", () => {
    setupCanvas();
    if (!audioActive) renderIdle();
  });

  // Mobile browsers report stale layout dimensions immediately after an
  // orientation flip; re-measure once the frame settles.
  window.addEventListener("orientationchange", () => {
    window.setTimeout(() => {
      setupCanvas();
      if (!audioActive) renderIdle();
    }, 120);
  });

  updateFreqDisplay();
  updateLevelBtnText();
  setupCanvas();
  renderIdle();
})();
