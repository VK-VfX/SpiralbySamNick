(() => {
  "use strict";

  // ---- DOM ----
  const tuneBtn = document.getElementById("tuneBtn");
  const tuneBtnLabel = document.getElementById("tuneBtnLabel");
  const freqSlider = document.getElementById("freqSlider");
  const freqValue = document.getElementById("freqValue");
  const indicator = document.getElementById("indicator");
  const statusValue = document.getElementById("statusValue");
  const streakValue = document.getElementById("streakValue");

  // ---- Audio state (created lazily on first user gesture) ----
  let audioCtx = null;
  let oscillator = null;
  let masterGain = null;
  let audioActive = false;

  const TONE_GAIN = 0.18;
  const RAMP_SECONDS = 0.05;

  // ---- Game state ----
  const FREQ_MIN = Number(freqSlider.min);
  const FREQ_MAX = Number(freqSlider.max);
  const LOCK_TOLERANCE_START = 0.05; // 5% deviation counts as "locked"
  const LOCK_TOLERANCE_FLOOR = 0.015; // hardest difficulty: 1.5%
  const LOCK_HOLD_MS = 900; // must stay within tolerance this long
  const UNSTABLE_RATIO = 0.35; // deviation ratio at/above which the beacon flickers fastest

  const FLASH_INTERVAL_LOCKED = 1000; // 1 Hz / 60 BPM when perfectly tuned
  const FLASH_INTERVAL_UNSTABLE = 130; // fast flicker when far off
  const FLASH_LIT_MS = 90;

  let targetFreq = randomTarget();
  let lockTolerance = LOCK_TOLERANCE_START;
  let streak = 0;
  let lockedSince = null;
  let flashTimer = null;

  function randomTarget() {
    return Math.round(FREQ_MIN + Math.random() * (FREQ_MAX - FREQ_MIN));
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
  // used purely as a click-free volume envelope.
  function startTone(frequencyHz) {
    const ctx = ensureAudioContext();

    masterGain = ctx.createGain();
    masterGain.gain.value = 0;
    masterGain.connect(ctx.destination);

    oscillator = ctx.createOscillator();
    oscillator.type = "sine";
    oscillator.frequency.value = frequencyHz;
    oscillator.connect(masterGain);
    oscillator.start();

    const now = ctx.currentTime;
    masterGain.gain.setTargetAtTime(TONE_GAIN, now, RAMP_SECONDS);

    audioActive = true;
  }

  function stopTone() {
    if (!oscillator || !masterGain || !audioCtx) return;
    const now = audioCtx.currentTime;
    const osc = oscillator;
    const gain = masterGain;

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

    oscillator = null;
    masterGain = null;
    audioActive = false;
  }

  function setToneFrequency(hz) {
    if (oscillator && audioCtx) {
      oscillator.frequency.setTargetAtTime(hz, audioCtx.currentTime, 0.02);
    }
  }

  // ---- Beacon / flash loop ----
  function deviationRatio(current, target) {
    return Math.abs(current - target) / target;
  }

  function currentFlashInterval() {
    const dev = deviationRatio(Number(freqSlider.value), targetFreq);
    const closeness = 1 - Math.min(dev / UNSTABLE_RATIO, 1);
    return FLASH_INTERVAL_UNSTABLE + (FLASH_INTERVAL_LOCKED - FLASH_INTERVAL_UNSTABLE) * closeness;
  }

  function flashColor() {
    const dev = deviationRatio(Number(freqSlider.value), targetFreq);
    if (dev <= lockTolerance) return getCss("--accent");
    if (dev <= UNSTABLE_RATIO * 0.6) return getCss("--amber");
    return getCss("--danger");
  }

  function getCss(varName) {
    return getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
  }

  function scheduleFlash() {
    clearTimeout(flashTimer);
    if (!audioActive) return;

    indicator.style.setProperty("--flash-color", flashColor());
    indicator.classList.add("lit");

    window.setTimeout(() => indicator.classList.remove("lit"), FLASH_LIT_MS);

    flashTimer = window.setTimeout(scheduleFlash, currentFlashInterval());
  }

  function stopFlashing() {
    clearTimeout(flashTimer);
    flashTimer = null;
    indicator.classList.remove("lit", "active");
  }

  // ---- Lock detection ----
  function evaluateLock() {
    if (!audioActive) return;
    const dev = deviationRatio(Number(freqSlider.value), targetFreq);

    if (dev <= lockTolerance) {
      if (lockedSince === null) lockedSince = performance.now();
      const held = performance.now() - lockedSince;
      setStatus("LOCKING", "amber");
      if (held >= LOCK_HOLD_MS) {
        onSignalStabilized();
      }
    } else {
      lockedSince = null;
      setStatus(dev >= UNSTABLE_RATIO ? "UNSTABLE" : "TUNING", dev >= UNSTABLE_RATIO ? "danger" : null);
    }
  }

  function onSignalStabilized() {
    streak += 1;
    streakValue.textContent = String(streak);
    setStatus("STABILIZED", "locked");
    lockTolerance = Math.max(LOCK_TOLERANCE_FLOOR, lockTolerance * 0.92);
    lockedSince = null;

    window.setTimeout(() => {
      targetFreq = randomTarget();
      if (audioActive) setStatus("TUNING");
    }, 1200);
  }

  function setStatus(text, mode) {
    statusValue.textContent = text;
    statusValue.classList.remove("danger", "locked");
    if (mode === "danger") statusValue.classList.add("danger");
    if (mode === "locked") statusValue.classList.add("locked");
  }

  // ---- Main loop (drives lock evaluation independently of slider events) ----
  let evalTimer = null;
  function startEvalLoop() {
    stopEvalLoop();
    evalTimer = window.setInterval(evaluateLock, 100);
  }
  function stopEvalLoop() {
    clearInterval(evalTimer);
    evalTimer = null;
  }

  // ---- UI wiring ----
  function updateFreqDisplay() {
    freqValue.textContent = freqSlider.value;
  }

  freqSlider.addEventListener("input", () => {
    updateFreqDisplay();
    setToneFrequency(Number(freqSlider.value));
  });

  tuneBtn.addEventListener("click", () => {
    if (!audioActive) {
      ensureAudioContext();
      startTone(Number(freqSlider.value));
      indicator.classList.add("active");
      tuneBtn.setAttribute("aria-pressed", "true");
      tuneBtnLabel.textContent = "STOP";
      setStatus("TUNING");
      lockedSince = null;
      scheduleFlash();
      startEvalLoop();
    } else {
      stopTone();
      stopFlashing();
      stopEvalLoop();
      tuneBtn.setAttribute("aria-pressed", "false");
      tuneBtnLabel.textContent = "TUNE";
      setStatus("STANDBY");
    }
  });

  // iOS Safari can suspend the context if the tab loses focus; resume on
  // return so the tone doesn't silently die mid-session.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && audioCtx && audioCtx.state === "suspended" && audioActive) {
      audioCtx.resume();
    }
  });

  updateFreqDisplay();
})();
