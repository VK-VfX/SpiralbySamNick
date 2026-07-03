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

  // ---- Audio state (created lazily on first user gesture) ----
  let audioCtx = null;
  let oscillator = null;
  let masterGain = null;
  let audioActive = false;

  const TONE_GAIN = 0.18;
  const RAMP_SECONDS = 0.05;

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

  // ---- Beat clock ----
  // Beat period ranges from a fast, erratic 350ms up to a steady 1000ms
  // (1 Hz / 60 BPM) as the player's slider frequency approaches the hidden
  // target — pitch accuracy is what makes the beat hittable at all.
  const BEAT_PERIOD_MIN_MS = 350;
  const BEAT_PERIOD_MAX_MS = 1000;

  function currentBeatPeriodMs() {
    const dev = deviationRatio(Number(freqSlider.value), targetFreq);
    const closeness = 1 - Math.min(dev / UNSTABLE_RATIO, 1);
    return BEAT_PERIOD_MIN_MS + (BEAT_PERIOD_MAX_MS - BEAT_PERIOD_MIN_MS) * closeness;
  }

  let cycleStart = null; // performance.now() timestamp of the last peak
  let cyclePeriod = null; // ms duration of the current beat cycle
  let lastPeakTimestamp = null; // most recent peak that has already occurred
  let rafId = null;

  // ---- Tap accuracy ----
  const ACCURACY_WINDOW_MS = 100;
  const BURST_DURATION_MS = 350;

  const COLOR_ACCENT = "51,240,200";
  const COLOR_DANGER = "255,61,94";
  const COLOR_DIM = "124,138,141";

  let tapStreak = 0;
  let burst = null; // { startTime, color } — brief hit/miss feedback ring
  let statusRevertTimer = null;

  function handleTap(e) {
    if (!audioActive || cycleStart === null) return;
    e.preventDefault();

    const tapTime = e.timeStamp; // DOMHighResTimeStamp, same epoch as performance.now()
    const upcomingPeak = cycleStart + cyclePeriod;
    const candidates = [lastPeakTimestamp, upcomingPeak].filter((t) => t !== null);
    const nearestPeak = candidates.reduce((best, t) =>
      Math.abs(tapTime - t) < Math.abs(tapTime - best) ? t : best
    );
    const delta = tapTime - nearestPeak; // signed ms: negative = early, positive = late

    if (Math.abs(delta) <= ACCURACY_WINDOW_MS) {
      tapStreak += 1;
      burst = { startTime: tapTime, color: COLOR_ACCENT };
      flashStatus("PERFECT ALIGNMENT", "locked");
    } else {
      tapStreak = 0;
      burst = { startTime: tapTime, color: COLOR_DANGER };
      flashStatus("SIGNAL DRIFT", "danger");
    }
    streakValue.textContent = String(tapStreak);
  }

  function flashStatus(text, mode) {
    setStatus(text, mode);
    clearTimeout(statusRevertTimer);
    statusRevertTimer = window.setTimeout(() => {
      if (audioActive) setStatus("LISTENING");
    }, 650);
  }

  function setStatus(text, mode) {
    statusValue.textContent = text;
    statusValue.classList.remove("danger", "locked");
    if (mode === "danger") statusValue.classList.add("danger");
    if (mode === "locked") statusValue.classList.add("locked");
  }

  // ---- Canvas rendering ----
  function setupCanvas() {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = canvas.clientWidth * dpr;
    canvas.height = canvas.clientHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function draw(now, phase) {
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    const cx = w / 2;
    const cy = h / 2;
    const maxR = (Math.min(w, h) / 2) * 0.82;

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
    if (!audioActive) return;

    if (cycleStart === null) {
      cycleStart = now;
      cyclePeriod = currentBeatPeriodMs();
    }

    let elapsed = now - cycleStart;
    let iterations = 0;
    while (elapsed >= cyclePeriod && iterations < 10) {
      lastPeakTimestamp = cycleStart + cyclePeriod;
      cycleStart = lastPeakTimestamp;
      cyclePeriod = currentBeatPeriodMs();
      elapsed = now - cycleStart;
      iterations += 1;
    }

    const phase = cyclePeriod > 0 ? Math.min(elapsed / cyclePeriod, 1) : 0;
    draw(now, phase);
    rafId = requestAnimationFrame(renderFrame);
  }

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
      burst = null;

      rafId = requestAnimationFrame(renderFrame);
    } else {
      stopTone();
      cancelAnimationFrame(rafId);
      rafId = null;
      clearTimeout(statusRevertTimer);

      tuneBtn.setAttribute("aria-pressed", "false");
      tuneBtnLabel.textContent = "TUNE";
      setStatus("STANDBY");
      renderIdle();
    }
  });

  // iOS Safari can suspend the context if the tab loses focus; resume on
  // return so the tone doesn't silently die mid-session.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && audioCtx && audioCtx.state === "suspended" && audioActive) {
      audioCtx.resume();
    }
  });

  window.addEventListener("resize", () => {
    setupCanvas();
    if (!audioActive) renderIdle();
  });

  updateFreqDisplay();
  setupCanvas();
  renderIdle();
})();
