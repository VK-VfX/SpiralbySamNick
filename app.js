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
      vibrate(VIBRATE_PERFECT_MS);
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
      updateChordForStreak(0);

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

  // Mobile browsers report stale layout dimensions immediately after an
  // orientation flip; re-measure once the frame settles.
  window.addEventListener("orientationchange", () => {
    window.setTimeout(() => {
      setupCanvas();
      if (!audioActive) renderIdle();
    }, 120);
  });

  updateFreqDisplay();
  setupCanvas();
  renderIdle();
})();
