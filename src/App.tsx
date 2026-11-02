import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  QrCode,
  Settings,
  Bell,
  Volume2,
  Play,
  CheckCircle2,
  ArrowLeft,
  Smartphone,
  FolderGit2,
  Lock,
  History,
  FileCode,
  Download,
  AlertTriangle,
  Send,
  Info,
  Power,
  Clock,
  Image as ImageIcon,
  Trash2,
  Upload,
  ChevronLeft,
  ChevronRight,
  Calendar,
  BarChart3,
  Receipt
} from 'lucide-react';
import { triggerClientSideZipDownload } from './downloadHelper';

const COLOMBIA_TZ = 'America/Bogota';

const MONTH_NAMES_LOWER = [
  'enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
  'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'
];

const MONTH_NAMES_TITLE = [
  'Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
  'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'
];

const MONTH_NAMES_SHORT = [
  'ene', 'feb', 'mar', 'abr', 'may', 'jun',
  'jul', 'ago', 'sep', 'oct', 'nov', 'dic'
];

const WEEKDAY_HEADERS = ['L', 'M', 'M', 'J', 'V', 'S', 'D'];

// Spanish number converter replicating SpanishNumbers.kt
function toSpanishWords(amount: number): string {
  if (amount === 0) return 'cero';
  const UNITS = [
    '', 'un', 'dos', 'tres', 'cuatro', 'cinco', 'seis', 'siete', 'ocho', 'nueve',
    'diez', 'once', 'doce', 'trece', 'catorce', 'quince', 'dieciséis', 'diecisiete',
    'dieciocho', 'diecinueve', 'veinte', 'veintiún', 'veintidós', 'veintitrés',
    'veinticuatro', 'veinticinco', 'veintiséis', 'veintisiete', 'veintiocho', 'veintinueve'
  ];
  const TENS = ['', '', '', 'treinta', 'cuarenta', 'cincuenta', 'sesenta', 'setenta', 'ochenta', 'noventa'];
  const HUNDREDS = ['', 'ciento', 'doscientos', 'trescientos', 'cuatrocientos', 'quinientos', 'seiscientos', 'setecientos', 'ochocientos', 'novecientos'];

  function convertGroup(n: number): string {
    if (n === 0) return '';
    if (n === 100) return 'cien';
    const h = Math.floor(n / 100);
    const rem = n % 100;
    const parts: string[] = [];
    if (h > 0) parts.push(HUNDREDS[h]);
    if (rem > 0) {
      if (rem < 30) {
        parts.push(UNITS[rem]);
      } else {
        const t = Math.floor(rem / 10);
        const u = rem % 10;
        parts.push(u > 0 ? `${TENS[t]} y ${UNITS[u]}` : TENS[t]);
      }
    }
    return parts.join(' ').trim();
  }

  const millions = Math.floor(amount / 1_000_000);
  const thousands = Math.floor((amount % 1_000_000) / 1_000);
  const units = amount % 1_000;
  const result: string[] = [];

  if (millions > 0) {
    result.push(millions === 1 ? 'un millón' : `${convertGroup(millions)} millones`);
  }
  if (thousands > 0) {
    result.push(thousands === 1 ? 'mil' : `${convertGroup(thousands)} mil`);
  }
  if (units > 0) {
    result.push(convertGroup(units));
  }
  return result.join(' ').trim();
}

function getPaymentPhrase(amount: number, includeThankYou: boolean = true): string {
  const words = toSpanishWords(amount);
  let amountWithCurrency: string;
  if (amount === 1) {
    amountWithCurrency = 'un peso';
  } else if (amount % 1_000_000 === 0 && amount > 0) {
    amountWithCurrency = `${words} de pesos`;
  } else {
    amountWithCurrency = `${words} pesos`;
  }

  if (includeThankYou) {
    return `Pago recibido, ${amountWithCurrency}. Gracias por tu compra.`;
  } else {
    return `Pago recibido, ${amountWithCurrency}.`;
  }
}

// Format COP currency
function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('es-CO', {
    style: 'currency',
    currency: 'COP',
    maximumFractionDigits: 0
  }).format(amount);
}

interface DayKey {
  year: number;
  month: number; // 1..12
  day: number;   // 1..31
}

function getColombiaDateParts(timestampMillis: number): {
  year: number;
  month: number;
  day: number;
  hour24: string;
  minute: string;
  second: string;
} {
  const d = new Date(timestampMillis);
  const formatter = new Intl.DateTimeFormat('es-CO', {
    timeZone: COLOMBIA_TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });
  const parts = formatter.formatToParts(d);
  const map: Record<string, string> = {};
  for (const p of parts) {
    map[p.type] = p.value;
  }
  const rawHour = map.hour === '24' ? '00' : (map.hour || '00').padStart(2, '0');
  return {
    year: parseInt(map.year || '2026', 10),
    month: parseInt(map.month || '10', 10),
    day: parseInt(map.day || '30', 10),
    hour24: rawHour,
    minute: (map.minute || '00').padStart(2, '0'),
    second: (map.second || '00').padStart(2, '0')
  };
}

function formatExactColombiaTime(timestampMillis: number, hasExactSeconds: boolean = true): string {
  const parts = getColombiaDateParts(timestampMillis);
  return hasExactSeconds
    ? `${parts.hour24}:${parts.minute}:${parts.second}`
    : `${parts.hour24}:${parts.minute}`;
}

function formatColombiaDateDDMMYYYY(timestampMillis: number): string {
  const parts = getColombiaDateParts(timestampMillis);
  return `${String(parts.day).padStart(2, '0')}/${String(parts.month).padStart(2, '0')}/${parts.year}`;
}

function dayKeyEquals(a: DayKey, b: DayKey): boolean {
  return a.year === b.year && a.month === b.month && a.day === b.day;
}

// Extract sender name from notification text (replicating PaymentNotificationParser.kt)
function extractSenderNameFromNotification(text: string): string | null {
  if (!text || !text.trim()) return null;
  const clean = text.replace(/\s+/g, ' ').trim();

  // Pattern 1: "... de <Nombre Completo>"
  const afterDeRegex = /(?:recibiste|recibi[oó]|has\s+recibido|te\s+enviaron|te\s+pasaron|transferencia(?:\s+de)?|pago(?:\s+de)?)[^.!\n]*?\bde\s+([A-ZÁÉÍÓÚÑa-záéíóúñ]+(?:\s+[A-ZÁÉÍÓÚÑa-záéíóúñ]+){0,5}?)(?=\s+(?:el\s+\d|a\s+tu\b|en\s+tu\b|a\s+trav[eé]s\b|exitosamente\b|por\s+\$|con\s+éxito\b)|[.,!]|$)/i;
  const deMatch = afterDeRegex.exec(clean);
  if (deMatch && deMatch[1]) {
    const candidate = sanitizeSenderCandidate(deMatch[1]);
    if (candidate) return candidate;
  }

  // Pattern 2: "<Nombre Completo> te transfirió / te envió / te pasó"
  const beforeVerbRegex = /(?:¡?te\s+enviaron\s+plata!?\s*|¡?te\s+lleg[oó]\s+plata!?\s*|^|[.!?]\s*)([A-ZÁÉÍÓÚÑa-záéíóúñ]+(?:\s+[A-ZÁÉÍÓÚÑa-záéíóúñ]+){0,5}?)\s+te\s+(?:transfiri[oó]|envi[oó]|pas[oó]|pag[oó]|consign[oó])\b/i;
  const beforeMatch = beforeVerbRegex.exec(clean);
  if (beforeMatch && beforeMatch[1]) {
    const candidate = sanitizeSenderCandidate(beforeMatch[1]);
    if (candidate) return candidate;
  }

  return null;
}

function sanitizeSenderCandidate(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed.length < 2) return null;
  if (!/^[A-Za-zÁÉÍÓÚÑáéíóúñ\s]+$/.test(trimmed)) return null;
  const words = trimmed.split(/\s+/).filter(Boolean);
  if (words.length === 0 || words.length > 6) return null;
  const invalidWords = new Set([
    'tu', 'su', 'mi', 'cuenta', 'nequi', 'bancolombia', 'daviplata',
    'plata', 'dinero', 'pesos', 'transferencia', 'pago', 'saldo', 'disponible'
  ]);
  if (words.some(w => invalidWords.has(w.toLowerCase()))) return null;
  if (!words.some(w => w.length >= 2)) return null;
  return words.join(' ');
}

// Global AudioContext for browser chime playback
let globalAudioContext: AudioContext | null = null;
function getAudioContext(): AudioContext | null {
  try {
    if (!globalAudioContext) {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      if (AudioCtx) {
        globalAudioContext = new AudioCtx();
      }
    }
    if (globalAudioContext && globalAudioContext.state === 'suspended') {
      globalAudioContext.resume().catch(() => {});
    }
    return globalAudioContext;
  } catch {
    return null;
  }
}

function playChimeSound() {
  try {
    const ctx = getAudioContext();
    if (!ctx) return;

    if (ctx.state === 'suspended') {
      ctx.resume().then(() => playChimeNodes(ctx)).catch(() => {});
    } else {
      playChimeNodes(ctx);
    }
  } catch (err) {
    console.warn('Audio chime error:', err);
  }
}

function playChimeNodes(ctx: AudioContext) {
  const now = ctx.currentTime;
  const osc1 = ctx.createOscillator();
  const osc2 = ctx.createOscillator();
  const gain = ctx.createGain();

  osc1.type = 'sine';
  osc1.frequency.setValueAtTime(880, now);
  osc1.frequency.exponentialRampToValueAtTime(1320, now + 0.12);

  osc2.type = 'triangle';
  osc2.frequency.setValueAtTime(440, now);
  osc2.frequency.exponentialRampToValueAtTime(660, now + 0.12);

  gain.gain.setValueAtTime(0.5, now);
  gain.gain.exponentialRampToValueAtTime(0.001, now + 0.4);

  osc1.connect(gain);
  osc2.connect(gain);
  gain.connect(ctx.destination);

  osc1.start(now);
  osc2.start(now);
  osc1.stop(now + 0.4);
  osc2.stop(now + 0.4);
}

declare global {
  interface Window {
    _ambiorixUtterance?: SpeechSynthesisUtterance;
  }
}

function formatPhraseForFluidSpeech(rawPhrase: string): string {
  return rawPhrase
    .replace(/\.\s+Gracias/gi, ' gracias')
    .replace(/,/g, ' ')
    .replace(/\./g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function speakSpanishPhrase(phrase: string, onStart?: () => void, onEnd?: () => void) {
  if (!('speechSynthesis' in window)) return;
  try {
    getAudioContext();
    const synth = window.speechSynthesis;
    const fluidPhrase = formatPhraseForFluidSpeech(phrase);

    const startSpeech = () => {
      if (synth.paused) {
        synth.resume();
      }

      const utterance = new SpeechSynthesisUtterance(fluidPhrase);
      window._ambiorixUtterance = utterance;

      const voices = synth.getVoices();
      // Priorizar voces locales (offline) en español para evitar trabas por red
      const spanishVoice =
        voices.find(v => v.localService && v.lang.toLowerCase() === 'es-co') ||
        voices.find(v => v.localService && v.lang.toLowerCase().startsWith('es-')) ||
        voices.find(v => v.localService && v.lang.toLowerCase().startsWith('es')) ||
        voices.find(v => v.lang.toLowerCase() === 'es-co') ||
        voices.find(v => v.lang.toLowerCase().startsWith('es-')) ||
        voices.find(v => v.lang.toLowerCase().startsWith('es'));

      if (spanishVoice) {
        utterance.voice = spanishVoice;
        utterance.lang = spanishVoice.lang;
      } else {
        utterance.lang = 'es-CO';
      }

      utterance.rate = 1.0;
      utterance.pitch = 1.0;
      utterance.volume = 1.0;

      if (onStart) utterance.onstart = onStart;
      if (onEnd) utterance.onend = onEnd;
      utterance.onerror = () => {
        if (onEnd) onEnd();
      };

      synth.speak(utterance);
    };

    if (synth.speaking || synth.pending) {
      synth.cancel();
      setTimeout(startSpeech, 60);
    } else {
      startSpeech();
    }
  } catch (err) {
    console.warn('Speech synthesis error:', err);
  }
}

interface PaymentRecord {
  id: string;
  amount: number;
  appName: string;
  packageName: string;
  timestampMillis: number;
  senderName: string | null;
  hasExactSeconds: boolean;
  isSimulated?: boolean;
}

function buildInitialStoredPayments(): PaymentRecord[] {
  try {
    localStorage.removeItem('ambiorix_pay_history_v27');
    const raw = localStorage.getItem('ambiorix_pay_history_v27_real');
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return parsed;
      }
    }
  } catch {
    // Fallback to empty records
  }

  return [];
}

// Deduplicate confirmed payments (excluding simulated test payments and duplicate notifications)
function deduplicateConfirmedPayments(payments: PaymentRecord[]): PaymentRecord[] {
  const valid = payments
    .filter(p => !p.isSimulated && p.amount > 0)
    .sort((a, b) => b.timestampMillis - a.timestampMillis);

  const seenIds = new Set<string>();
  const unique: PaymentRecord[] = [];

  for (const p of valid) {
    if (p.id && seenIds.has(p.id)) continue;
    if (p.id) seenIds.add(p.id);

    const isNearDuplicate = unique.some(
      existing =>
        existing.packageName.toLowerCase() === p.packageName.toLowerCase() &&
        existing.amount === p.amount &&
        (existing.senderName || '').toLowerCase() === (p.senderName || '').toLowerCase() &&
        Math.abs(existing.timestampMillis - p.timestampMillis) <= 2000
    );

    if (!isNearDuplicate) {
      unique.push(p);
    }
  }
  return unique;
}

// Skeleton Loader Component for History Screen
function HistorySkeletonView() {
  const barHeights = [32, 56, 24, 78, 44, 88, 60, 36, 68, 48, 82, 28];
  return (
    <div className="flex-1 flex flex-col bg-[#0B0F17] p-4 space-y-3.5 overflow-y-auto" aria-busy="true" aria-label="Cargando historial de pagos">
      {/* Daily Accumulated Skeleton */}
      <div className="bg-[#131B2A] border border-slate-800 rounded-2xl p-4 space-y-2.5">
        <div className="h-3 w-36 rounded ambiorix-skeleton-shimmer" />
        <div className="h-6 w-52 rounded ambiorix-skeleton-shimmer" />
        <div className="grid grid-cols-2 gap-2 pt-1">
          <div className="h-10 rounded-xl ambiorix-skeleton-shimmer" />
          <div className="h-10 rounded-xl ambiorix-skeleton-shimmer" />
        </div>
      </div>

      {/* Bar Chart Skeleton */}
      <div className="bg-[#131B2A] border border-slate-800 rounded-2xl p-4 space-y-3">
        <div className="flex justify-between items-center">
          <div className="h-3.5 w-32 rounded ambiorix-skeleton-shimmer" />
          <div className="h-3.5 w-20 rounded ambiorix-skeleton-shimmer" />
        </div>
        <div className="h-24 flex items-end justify-between gap-1.5 pt-2">
          {barHeights.map((h, i) => (
            <div key={i} className="flex-1 flex flex-col items-center gap-1">
              <div className="w-full rounded-t-md ambiorix-skeleton-shimmer" style={{ height: `${h}px` }} />
              <div className="w-2.5 h-2 rounded ambiorix-skeleton-shimmer" />
            </div>
          ))}
        </div>
      </div>

      {/* Calendar Skeleton */}
      <div className="bg-[#131B2A] border border-slate-800 rounded-2xl p-4 space-y-2.5">
        <div className="flex justify-between items-center">
          <div className="h-6 w-6 rounded-lg ambiorix-skeleton-shimmer" />
          <div className="h-4 w-28 rounded ambiorix-skeleton-shimmer" />
          <div className="h-6 w-6 rounded-lg ambiorix-skeleton-shimmer" />
        </div>
        <div className="grid grid-cols-7 gap-1.5 pt-1">
          {Array.from({ length: 28 }).map((_, idx) => (
            <div key={idx} className="aspect-square rounded-lg ambiorix-skeleton-shimmer" />
          ))}
        </div>
      </div>

      {/* List Skeleton */}
      <div className="space-y-2">
        {[0, 1].map(item => (
          <div key={item} className="bg-[#131B2A] border border-slate-800 rounded-xl p-3 flex justify-between items-center">
            <div className="space-y-1.5">
              <div className="h-3.5 w-40 rounded ambiorix-skeleton-shimmer" />
              <div className="h-2.5 w-28 rounded ambiorix-skeleton-shimmer" />
            </div>
            <div className="h-3.5 w-14 rounded ambiorix-skeleton-shimmer" />
          </div>
        ))}
      </div>
    </div>
  );
}

export function isAmbiorixPurpleTier(dailyTotalAmount: number): boolean {
  return dailyTotalAmount > 600000 || (dailyTotalAmount > 600 && dailyTotalAmount < 1000);
}

export function isAmbiorixGoldTier(dailyTotalAmount: number): boolean {
  return !isAmbiorixPurpleTier(dailyTotalAmount) &&
    (dailyTotalAmount > 300000 || (dailyTotalAmount > 300 && dailyTotalAmount <= 600));
}

interface RGB {
  r: number;
  g: number;
  b: number;
}

function hexToRgb(hex: string): RGB {
  const clean = hex.replace('#', '');
  const num = parseInt(clean, 16);
  return {
    r: (num >> 16) & 255,
    g: (num >> 8) & 255,
    b: num & 255
  };
}

function lerpRgb(a: RGB, b: RGB, t: number): RGB {
  const f = Math.max(0, Math.min(1, t));
  return {
    r: Math.round(a.r + (b.r - a.r) * f),
    g: Math.round(a.g + (b.g - a.g) * f),
    b: Math.round(a.b + (b.b - a.b) * f)
  };
}

function rgbCss(c: RGB, alpha: number = 1): string {
  return `rgba(${c.r}, ${c.g}, ${c.b}, ${Math.max(0, Math.min(1, alpha))})`;
}

const PALETTE_SILVER = {
  spec: hexToRgb('#FFFFFF'),
  bright: hexToRgb('#E2E8F0'),
  mid: hexToRgb('#94A3B8'),
  deep: hexToRgb('#334155'),
  halo: hexToRgb('#CBD5E1')
};

const PALETTE_GOLD = {
  spec: hexToRgb('#FFFBEB'),
  bright: hexToRgb('#FDE047'),
  mid: hexToRgb('#D4AF37'),
  deep: hexToRgb('#92400E'),
  halo: hexToRgb('#F59E0B')
};

const PALETTE_PURPLE = {
  spec: hexToRgb('#F5F3FF'),
  bright: hexToRgb('#C084FC'),
  mid: hexToRgb('#9333EA'),
  deep: hexToRgb('#581C87'),
  halo: hexToRgb('#A855F7')
};

const PALETTE_RED = {
  spec: hexToRgb('#FEF2F2'),
  bright: hexToRgb('#F87171'),
  mid: hexToRgb('#DC2626'),
  deep: hexToRgb('#991B1B'),
  halo: hexToRgb('#EF4444')
};

const PALETTE_EMERALD = {
  spec: hexToRgb('#ECFDF5'),
  bright: hexToRgb('#6EE7B7'),
  mid: hexToRgb('#10B981'),
  deep: hexToRgb('#047857'),
  halo: hexToRgb('#34D399')
};

function addRoundedRectSubpath(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  radius: number
) {
  if (w <= 0.2 || h <= 0.2) return;
  const r = Math.max(0, Math.min(radius, w / 2, h / 2));
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}

function addMonogramCutoutsToContext(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  r: number,
  m: number
) {
  const ease = m * m * (3 - 2 * m);
  if (ease <= 0.003) return;

  // 1. Two vertical separation channels dividing the sphere into the 3 "A" columns
  // Calibrated to exact original logo coordinates: centers at ±0.3455*R, half-width = 0.0715*R -> [0.274*R, 0.417*R]
  const gapHalfW = r * 0.0715 * ease;
  const gapHalfH = r * 1.05 * Math.min(1, ease * 1.08);
  const gapCorner = r * 0.015 * ease;

  const g1Cx = cx - r * 0.3455;
  addRoundedRectSubpath(
    ctx,
    g1Cx - gapHalfW,
    cy - gapHalfH,
    gapHalfW * 2,
    gapHalfH * 2,
    gapCorner
  );

  const g2Cx = cx + r * 0.3455;
  addRoundedRectSubpath(
    ctx,
    g2Cx - gapHalfW,
    cy - gapHalfH,
    gapHalfW * 2,
    gapHalfH * 2,
    gapCorner
  );

  // 2. Center "A" upper window & lower leg slot
  // Calibrated to exact original logo coordinates: half-width = 0.112*R, crossbar at y = ±0.083*R, top arch above -0.762*R
  const cHoleHalfW = r * 0.112 * ease;
  const crossbarTopY = cy - r * 0.083;
  const crossbarBottomY = cy + r * 0.083;
  const cTopH = r * 0.679 * ease;
  const cCorner = r * 0.022 * ease;

  if (cTopH > 0.5) {
    addRoundedRectSubpath(
      ctx,
      cx - cHoleHalfW,
      crossbarTopY - cTopH,
      cHoleHalfW * 2,
      cTopH,
      cCorner
    );
  }

  const cBotTop = cy + r * (1.02 - 0.937 * ease);
  const cBotBottom = cy + r * 1.06;
  if (cBotBottom > cBotTop) {
    addRoundedRectSubpath(
      ctx,
      cx - cHoleHalfW,
      cBotTop,
      cHoleHalfW * 2,
      cBotBottom - cBotTop,
      cCorner
    );
  }

  // 3. Left & Right "A" D-Shaped Upper Windows
  // Bounded by inner vertical stem at |x| = 0.583*R, crossbar top at y = -0.083*R, and inner circular arc of radius 0.822*R
  const xStem = r * 0.583;
  const yBar = r * 0.083;
  const rInner = r * (0.583 + 0.239 * ease);

  if (rInner > xStem + 0.5) {
    const dyStem = Math.sqrt(Math.max(0, rInner * rInner - xStem * xStem));
    if (dyStem > yBar + 0.5) {
      const dxBar = Math.sqrt(Math.max(0, rInner * rInner - yBar * yBar));
      const sinBar = Math.min(0.999, yBar / rInner);
      const cosStem = Math.min(0.999, xStem / rInner);

      // Left "A" upper D-window
      const aLeftStart = Math.PI + Math.asin(sinBar);
      const aLeftEnd = Math.PI + Math.acos(cosStem);
      ctx.moveTo(cx - xStem, cy - yBar);
      ctx.lineTo(cx - dxBar, cy - yBar);
      ctx.arc(cx, cy, rInner, aLeftStart, aLeftEnd, false);
      ctx.lineTo(cx - xStem, cy - yBar);
      ctx.closePath();

      // Right "A" upper D-window
      const aRightStart = -Math.asin(sinBar);
      const aRightEnd = -Math.acos(cosStem);
      ctx.moveTo(cx + xStem, cy - yBar);
      ctx.lineTo(cx + dxBar, cy - yBar);
      ctx.arc(cx, cy, rInner, aRightStart, aRightEnd, true);
      ctx.lineTo(cx + xStem, cy - yBar);
      ctx.closePath();
    }
  }

  // 4. Left & Right "A" Unified Lower Windows + Outer Curved Leg Termination
  // Follows inner circular arc of radius 0.822*R from crossbar bottom (y = +0.083*R) down to y = +0.49*R,
  // then cuts across the outer curved leg to y = +0.55*R so only the 4 slender inner stems reach the bottom
  const yLowerTop = cy + r * (1.02 - 0.937 * ease);
  const yCutInner = cy + r * (1.02 - 0.53 * ease);
  const yCutOuter = cy + r * (1.04 - 0.49 * ease);
  const yBottom = cy + r * 1.08;
  const rInnerLower = r * 0.822;

  if (yBottom > yLowerTop + 0.5) {
    const dyTop = Math.max(0, yLowerTop - cy);
    const dyCut = Math.max(dyTop, yCutInner - cy);

    if (dyTop < rInnerLower * 0.98) {
      const sinTop = Math.min(0.995, dyTop / rInnerLower);
      const sinCut = Math.min(0.995, dyCut / rInnerLower);

      // Left lower unified cutout
      const aLowLeftStart = Math.PI - Math.asin(sinTop);
      const aLowLeftEnd = Math.PI - Math.asin(sinCut);
      ctx.moveTo(cx - xStem, yLowerTop);
      ctx.arc(cx, cy, rInnerLower, aLowLeftStart, aLowLeftEnd, true);
      ctx.lineTo(cx - r * 1.08, yCutOuter);
      ctx.lineTo(cx - r * 1.08, yBottom);
      ctx.lineTo(cx - xStem, yBottom);
      ctx.lineTo(cx - xStem, yLowerTop);
      ctx.closePath();

      // Right lower unified cutout
      const aLowRightStart = Math.asin(sinTop);
      const aLowRightEnd = Math.asin(sinCut);
      ctx.moveTo(cx + xStem, yLowerTop);
      ctx.arc(cx, cy, rInnerLower, aLowRightStart, aLowRightEnd, false);
      ctx.lineTo(cx + r * 1.08, yCutOuter);
      ctx.lineTo(cx + r * 1.08, yBottom);
      ctx.lineTo(cx + xStem, yBottom);
      ctx.lineTo(cx + xStem, yLowerTop);
      ctx.closePath();
    } else {
      // Initial emergence near bottom edge of sphere
      ctx.moveTo(cx - xStem, yLowerTop);
      ctx.lineTo(cx - r * 1.08, yCutOuter);
      ctx.lineTo(cx - r * 1.08, yBottom);
      ctx.lineTo(cx - xStem, yBottom);
      ctx.closePath();

      ctx.moveTo(cx + xStem, yLowerTop);
      ctx.lineTo(cx + r * 1.08, yCutOuter);
      ctx.lineTo(cx + r * 1.08, yBottom);
      ctx.lineTo(cx + xStem, yBottom);
      ctx.closePath();
    }
  }
}

function drawSculptedAmbiorixCanvas(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  morphFraction: number,
  rotationAngleRad: number,
  shimmerPhase: number,
  goldTransition: number,
  purpleTransition: number,
  greenTransition: number,
  redTransition: number = 0
) {
  ctx.clearRect(0, 0, width, height);

  const cx = width / 2;
  const cy = height / 2;
  const sphereRadius = (Math.min(width, height) / 2) * 0.76;
  const m = Math.max(0, Math.min(1, morphFraction));

  // Interpolate base alloy (Silver <= 300 vs Gold > 300 vs Purple > 600), synchronize with Red on low-battery warning, and override toward Emerald Green on payment confirmation
  const sgSpec = lerpRgb(PALETTE_SILVER.spec, PALETTE_GOLD.spec, goldTransition);
  const sgBright = lerpRgb(PALETTE_SILVER.bright, PALETTE_GOLD.bright, goldTransition);
  const sgMid = lerpRgb(PALETTE_SILVER.mid, PALETTE_GOLD.mid, goldTransition);
  const sgDeep = lerpRgb(PALETTE_SILVER.deep, PALETTE_GOLD.deep, goldTransition);
  const sgHalo = lerpRgb(PALETTE_SILVER.halo, PALETTE_GOLD.halo, goldTransition);

  const tierSpec = lerpRgb(sgSpec, PALETTE_PURPLE.spec, purpleTransition);
  const tierBright = lerpRgb(sgBright, PALETTE_PURPLE.bright, purpleTransition);
  const tierMid = lerpRgb(sgMid, PALETTE_PURPLE.mid, purpleTransition);
  const tierDeep = lerpRgb(sgDeep, PALETTE_PURPLE.deep, purpleTransition);
  const tierHalo = lerpRgb(sgHalo, PALETTE_PURPLE.halo, purpleTransition);

  const baseSpec = lerpRgb(tierSpec, PALETTE_RED.spec, redTransition);
  const baseBright = lerpRgb(tierBright, PALETTE_RED.bright, redTransition);
  const baseMid = lerpRgb(tierMid, PALETTE_RED.mid, redTransition);
  const baseDeep = lerpRgb(tierDeep, PALETTE_RED.deep, redTransition);
  const baseHalo = lerpRgb(tierHalo, PALETTE_RED.halo, redTransition);

  const cSpec = lerpRgb(baseSpec, PALETTE_EMERALD.spec, greenTransition);
  const cBright = lerpRgb(baseBright, PALETTE_EMERALD.bright, greenTransition);
  const cMid = lerpRgb(baseMid, PALETTE_EMERALD.mid, greenTransition);
  const cDeep = lerpRgb(baseDeep, PALETTE_EMERALD.deep, greenTransition);
  const cHalo = lerpRgb(baseHalo, PALETTE_EMERALD.halo, greenTransition);
  const tierDarkCore = lerpRgb(hexToRgb('#0F172A'), hexToRgb('#1E0B36'), purpleTransition);
  const baseDarkCore = lerpRgb(tierDarkCore, hexToRgb('#3B0707'), redTransition);
  const cDarkCore = lerpRgb(baseDarkCore, hexToRgb('#064E3B'), greenTransition);

  const tiltX = Math.sin(rotationAngleRad);
  const tiltY = Math.cos(rotationAngleRad);

  // 1. Ambient Metallic / Emerald Halo
  const haloGrad = ctx.createRadialGradient(cx, cy, sphereRadius * 0.15, cx, cy, sphereRadius * 1.3);
  haloGrad.addColorStop(0, rgbCss(cHalo, 0.34 + 0.24 * greenTransition));
  haloGrad.addColorStop(0.62, rgbCss(cMid, 0.14 + 0.16 * greenTransition));
  haloGrad.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = haloGrad;
  ctx.beginPath();
  ctx.arc(cx, cy, sphereRadius * 1.3, 0, Math.PI * 2);
  ctx.fill();

  // 2. Rotating Precision Orbital Ring
  const orbitRadius = sphereRadius * 1.13;
  ctx.save();
  ctx.translate(cx, cy);
  ctx.rotate(rotationAngleRad);
  const orbitGrad = ctx.createLinearGradient(-orbitRadius, -orbitRadius, orbitRadius, orbitRadius);
  orbitGrad.addColorStop(0, rgbCss(cSpec, 0.9));
  orbitGrad.addColorStop(0.35, rgbCss(cBright, 0.85));
  orbitGrad.addColorStop(0.7, rgbCss(cDeep, 0.4));
  orbitGrad.addColorStop(1, rgbCss(cMid, 0.9));
  ctx.strokeStyle = orbitGrad;
  ctx.lineWidth = Math.max(1.8, sphereRadius * 0.032);
  ctx.beginPath();
  ctx.arc(0, 0, orbitRadius, 0, Math.PI * 2);
  ctx.stroke();

  // Orbital satellite nodes
  ctx.fillStyle = rgbCss(cSpec, 0.95);
  ctx.beginPath();
  ctx.arc(0, -orbitRadius, sphereRadius * 0.042, 0, Math.PI * 2);
  ctx.fill();

  ctx.fillStyle = rgbCss(cBright, 0.85);
  ctx.beginPath();
  ctx.arc(0, orbitRadius, sphereRadius * 0.034, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  // 3. Inner Sphere Core Shadow (gives depth to the carved channels while morphing)
  if (m < 0.98) {
    const coreAlpha = (1 - m * 0.72) * 0.65;
    const coreGrad = ctx.createRadialGradient(cx, cy, sphereRadius * 0.1, cx, cy, sphereRadius);
    coreGrad.addColorStop(0, rgbCss(cDeep, coreAlpha * 0.55));
    coreGrad.addColorStop(0.75, rgbCss(cDarkCore, coreAlpha * 0.92));
    coreGrad.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = coreGrad;
    ctx.beginPath();
    ctx.arc(cx, cy, sphereRadius * 0.99, 0, Math.PI * 2);
    ctx.fill();
  }

  // 4. 3D Extruded Metallic Side Depth Layer (single lightweight pass for smooth 60fps performance)
  const depthOffsetX = -tiltX * sphereRadius * 0.08 * (0.45 + 0.55 * m);
  const depthOffsetY = Math.abs(tiltY) * sphereRadius * 0.055 + sphereRadius * 0.032;
  const layerColor = lerpRgb(cDeep, cDarkCore, 0.65);

  ctx.save();
  ctx.translate(depthOffsetX, depthOffsetY);
  ctx.beginPath();
  ctx.arc(cx, cy, sphereRadius, 0, Math.PI * 2);
  ctx.clip();

  ctx.beginPath();
  ctx.rect(cx - sphereRadius * 1.2, cy - sphereRadius * 1.2, sphereRadius * 2.4, sphereRadius * 2.4);
  addMonogramCutoutsToContext(ctx, cx, cy, sphereRadius, m);
  ctx.fillStyle = rgbCss(layerColor, 0.92);
  ctx.fill('evenodd');
  ctx.restore();

  // 5. Main Sculpted 3D Sphere / Monogram Surface
  ctx.save();
  // Outer sphere boundary clip
  ctx.beginPath();
  ctx.arc(cx, cy, sphereRadius, 0, Math.PI * 2);
  ctx.clip();

  // Carve out the "AAA" negative space using evenodd clip
  ctx.beginPath();
  ctx.rect(cx - sphereRadius * 1.2, cy - sphereRadius * 1.2, sphereRadius * 2.4, sphereRadius * 2.4);
  addMonogramCutoutsToContext(ctx, cx, cy, sphereRadius, m);
  ctx.clip('evenodd');

  // 5a. 3D Spherical Radial Lighting (Base Alloy: Silver / Gold / Purple / Red)
  const lightX = cx - sphereRadius * 0.34 + tiltX * sphereRadius * 0.24;
  const lightY = cy - sphereRadius * 0.36 + tiltY * sphereRadius * 0.18;
  const surfaceGrad = ctx.createRadialGradient(
    lightX,
    lightY,
    sphereRadius * 0.04,
    lightX,
    lightY,
    sphereRadius * 1.55
  );
  surfaceGrad.addColorStop(0, rgbCss(baseSpec, 1));
  surfaceGrad.addColorStop(0.24, rgbCss(baseBright, 1));
  surfaceGrad.addColorStop(0.56, rgbCss(baseMid, 1));
  surfaceGrad.addColorStop(0.84, rgbCss(baseDeep, 1));
  surfaceGrad.addColorStop(1, rgbCss(baseDarkCore, 1));

  ctx.fillStyle = surfaceGrad;
  ctx.fillRect(cx - sphereRadius, cy - sphereRadius, sphereRadius * 2, sphereRadius * 2);

  // If payment is confirmed, Emerald Green is born directly from the center of this same logo
  // and expands outward across the 3D sculpted columns without superimposing any duplicate logo
  if (greenTransition > 0.005) {
    const greenBirthRadius = Math.max(1, sphereRadius * 1.45 * greenTransition);
    const greenBirthGrad = ctx.createRadialGradient(cx, cy, 0, cx, cy, greenBirthRadius);
    greenBirthGrad.addColorStop(0, rgbCss(PALETTE_EMERALD.spec, 1));
    greenBirthGrad.addColorStop(0.28, rgbCss(PALETTE_EMERALD.bright, 1));
    greenBirthGrad.addColorStop(0.65, rgbCss(PALETTE_EMERALD.mid, 1));
    greenBirthGrad.addColorStop(0.92, rgbCss(PALETTE_EMERALD.deep, 1));
    greenBirthGrad.addColorStop(1, 'rgba(4,120,87,0)');
    ctx.fillStyle = greenBirthGrad;
    ctx.fillRect(cx - sphereRadius, cy - sphereRadius, sphereRadius * 2, sphereRadius * 2);
  }

  // 5b. 3D Rotating Meridians & Latitude Rings integrated into the sculpted surface
  const meridianAlpha = Math.max(0, Math.min(1, (1 - m * 0.78) * 0.46));
  if (meridianAlpha > 0.01) {
    ctx.strokeStyle = rgbCss(cSpec, meridianAlpha);
    ctx.lineWidth = Math.max(1.1, sphereRadius * 0.016);
    for (let i = 0; i < 3; i++) {
      const phase = rotationAngleRad + (i * Math.PI) / 3;
      const rx = Math.max(sphereRadius * 0.06, Math.abs(Math.cos(phase)) * sphereRadius * 0.95);
      ctx.beginPath();
      ctx.ellipse(cx, cy, rx, sphereRadius * 0.95, 0, 0, Math.PI * 2);
      ctx.stroke();
    }

    const latRy = Math.max(sphereRadius * 0.08, Math.abs(Math.sin(rotationAngleRad)) * sphereRadius * 0.42);
    ctx.strokeStyle = rgbCss(cBright, meridianAlpha * 0.9);
    ctx.beginPath();
    ctx.ellipse(cx, cy, sphereRadius * 0.95, latRy, 0, 0, Math.PI * 2);
    ctx.stroke();
  }

  // 5c. Travelling Diagonal Specular Sheen across the sculpted 3D surface
  const sweepOffset = (shimmerPhase * 2.4 - 1.2) * sphereRadius * 1.85;
  const sheenGrad = ctx.createLinearGradient(
    cx + sweepOffset - sphereRadius * 0.55,
    cy - sphereRadius,
    cx + sweepOffset + sphereRadius * 0.55,
    cy + sphereRadius
  );
  sheenGrad.addColorStop(0, 'rgba(255,255,255,0)');
  sheenGrad.addColorStop(0.42, rgbCss(cSpec, 0.14 + 0.14 * m));
  sheenGrad.addColorStop(0.5, rgbCss(hexToRgb('#FFFFFF'), 0.34 + 0.18 * m));
  sheenGrad.addColorStop(0.58, rgbCss(cBright, 0.14 + 0.14 * m));
  sheenGrad.addColorStop(1, 'rgba(255,255,255,0)');

  ctx.fillStyle = sheenGrad;
  ctx.fillRect(cx - sphereRadius, cy - sphereRadius, sphereRadius * 2, sphereRadius * 2);

  // 5d. Top-left 3D Spherical Specular Gleam
  const gleamX = cx - sphereRadius * 0.32 + tiltX * sphereRadius * 0.12;
  const gleamY = cy - sphereRadius * 0.32 + tiltY * sphereRadius * 0.12;
  const gleamR = sphereRadius * 0.44;
  const gleamGrad = ctx.createRadialGradient(gleamX, gleamY, 0, gleamX, gleamY, gleamR);
  gleamGrad.addColorStop(0, rgbCss(hexToRgb('#FFFFFF'), 0.46 * (1 - 0.3 * m)));
  gleamGrad.addColorStop(0.55, rgbCss(cSpec, 0.16 * (1 - 0.25 * m)));
  gleamGrad.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = gleamGrad;
  ctx.beginPath();
  ctx.arc(gleamX, gleamY, gleamR, 0, Math.PI * 2);
  ctx.fill();

  ctx.restore();

  // 6. Metallic Beveled Rim Highlight along the outer sphere & carved "AAA" columns
  ctx.save();
  ctx.beginPath();
  ctx.arc(cx, cy, sphereRadius, 0, Math.PI * 2);
  ctx.clip();

  const bevelGrad = ctx.createLinearGradient(
    cx - sphereRadius,
    cy - sphereRadius,
    cx + sphereRadius,
    cy + sphereRadius
  );
  bevelGrad.addColorStop(0, rgbCss(cSpec, 0.95));
  bevelGrad.addColorStop(0.3, rgbCss(cBright, 0.8));
  bevelGrad.addColorStop(0.65, rgbCss(cDeep, 0.65));
  bevelGrad.addColorStop(1, rgbCss(cSpec, 0.9));

  ctx.strokeStyle = bevelGrad;
  ctx.lineWidth = Math.max(1.5, sphereRadius * (0.02 + 0.01 * m));

  // Stroke outer circle bevel
  ctx.beginPath();
  ctx.arc(cx, cy, sphereRadius - ctx.lineWidth * 0.5, 0, Math.PI * 2);
  ctx.stroke();

  // Stroke inner carved "AAA" column bevels
  if (m > 0.01) {
    ctx.beginPath();
    addMonogramCutoutsToContext(ctx, cx, cy, sphereRadius, m);
    ctx.stroke();
  }
  ctx.restore();
}

function AmbiorixInteractiveLogoPreview({
  isPaymentConfirmed,
  dailyTotalAmount,
  isLowBatteryWarning = false,
  isOperational,
  isVisible
}: {
  isPaymentConfirmed: boolean;
  dailyTotalAmount: number;
  isLowBatteryWarning?: boolean;
  isOperational: boolean;
  isVisible: boolean;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const isPurpleTier = isAmbiorixPurpleTier(dailyTotalAmount);
  const isGoldTier = isAmbiorixGoldTier(dailyTotalAmount);

  const animStateRef = useRef({
    cycleProgress: 0.42,
    goldTransition: isGoldTier || isPurpleTier ? 1 : 0,
    purpleTransition: isPurpleTier ? 1 : 0,
    redTransition: isLowBatteryWarning ? 1 : 0,
    greenTransition: isPaymentConfirmed ? 1 : 0
  });

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = typeof window !== 'undefined' ? Math.min(2, window.devicePixelRatio || 1) : 1;
    const size = 240;
    if (canvas.width !== size * dpr || canvas.height !== size * dpr) {
      canvas.width = size * dpr;
      canvas.height = size * dpr;
    }

    const prefersReducedMotion =
      typeof window !== 'undefined' &&
      window.matchMedia &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    let rafId = 0;
    let lastTime = performance.now();
    const cycleDurationMs = 7200;

    const smootherStep = (x: number) => {
      const t = Math.max(0, Math.min(1, x));
      return t * t * t * (t * (t * 6 - 15) + 10);
    };

    const computeContinuousMorph = (phase: number) => {
      const wave = 0.5 - 0.5 * Math.cos(phase * Math.PI * 2);
      const shaped = Math.min(1, Math.max(0, (wave - 0.08) / 0.78));
      return smootherStep(shaped);
    };

    const renderFrame = (now: number) => {
      const rawDelta = now - lastTime;
      const state = animStateRef.current;
      const targetGold = isGoldTier || isPurpleTier ? 1 : 0;
      const targetPurple = isPurpleTier ? 1 : 0;
      const targetRed = isLowBatteryWarning ? 1 : 0;
      const targetGreen = isPaymentConfirmed ? 1 : 0;

      const isTransitioning =
        Math.abs(state.goldTransition - targetGold) > 0.01 ||
        Math.abs(state.purpleTransition - targetPurple) > 0.01 ||
        Math.abs(state.redTransition - targetRed) > 0.01 ||
        Math.abs(state.greenTransition - targetGreen) > 0.01;

      // Throttle idle loop to ~45-50fps unless actively transitioning alloy/green state
      if (rawDelta < 20 && !isTransitioning) {
        if (isVisible && isOperational && !(isPaymentConfirmed && state.greenTransition >= 0.99)) {
          rafId = requestAnimationFrame(renderFrame);
        }
        return;
      }

      const delta = Math.min(64, Math.max(0, rawDelta));
      lastTime = now;

      if (isVisible && isOperational && !prefersReducedMotion) {
        state.cycleProgress = (state.cycleProgress + delta / cycleDurationMs) % 1;
      } else if (prefersReducedMotion) {
        state.cycleProgress = 0.5;
      }

      // Smoothly interpolate alloy state
      const alloySpeed = delta / 450;
      if (state.goldTransition < targetGold) {
        state.goldTransition = Math.min(targetGold, state.goldTransition + alloySpeed);
      } else if (state.goldTransition > targetGold) {
        state.goldTransition = Math.max(targetGold, state.goldTransition - alloySpeed);
      }

      if (state.purpleTransition < targetPurple) {
        state.purpleTransition = Math.min(targetPurple, state.purpleTransition + alloySpeed);
      } else if (state.purpleTransition > targetPurple) {
        state.purpleTransition = Math.max(targetPurple, state.purpleTransition - alloySpeed);
      }

      const redSpeed = delta / 260;
      if (state.redTransition < targetRed) {
        state.redTransition = Math.min(targetRed, state.redTransition + redSpeed);
      } else if (state.redTransition > targetRed) {
        state.redTransition = Math.max(targetRed, state.redTransition - redSpeed);
      }

      const greenSpeed = delta / 220;
      if (state.greenTransition < targetGreen) {
        state.greenTransition = Math.min(targetGreen, state.greenTransition + greenSpeed);
      } else if (state.greenTransition > targetGreen) {
        state.greenTransition = Math.max(targetGreen, state.greenTransition - greenSpeed);
      }

      const baseMorph = computeContinuousMorph(state.cycleProgress);
      const morphFraction = baseMorph + (1 - baseMorph) * state.greenTransition;
      const angleRad = state.cycleProgress * Math.PI * 2;
      const shimmerPhase = (state.cycleProgress * 2) % 1;

      ctx.save();
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      drawSculptedAmbiorixCanvas(
        ctx,
        size,
        size,
        morphFraction,
        angleRad,
        shimmerPhase,
        state.goldTransition,
        state.purpleTransition,
        state.greenTransition,
        state.redTransition
      );
      ctx.restore();

      const stillTransitioning =
        Math.abs(state.goldTransition - targetGold) > 0.01 ||
        Math.abs(state.purpleTransition - targetPurple) > 0.01 ||
        Math.abs(state.redTransition - targetRed) > 0.01 ||
        Math.abs(state.greenTransition - targetGreen) > 0.01;

      // Pause background canvas loop once the green confirmation overlay covers the screen
      const isCoveredByConfirmation = isPaymentConfirmed && state.greenTransition >= 0.99;

      if (isVisible && !isCoveredByConfirmation && (isOperational || stillTransitioning)) {
        rafId = requestAnimationFrame(renderFrame);
      }
    };

    rafId = requestAnimationFrame(renderFrame);
    return () => cancelAnimationFrame(rafId);
  }, [isVisible, isOperational, isGoldTier, isPurpleTier, isLowBatteryWarning, isPaymentConfirmed]);

  return (
    <div className="relative w-60 h-60 flex flex-col items-center justify-center select-none">
      <canvas
        ref={canvasRef}
        style={{ width: 240, height: 240 }}
        className={`transition-transform duration-300 ${
          isPaymentConfirmed ? 'scale-110' : 'scale-100'
        }`}
      />
    </div>
  );
}

function PaymentConfirmationChoreographyPreview({
  payment,
  timeRemaining,
  confirmationDuration
}: {
  payment: PaymentRecord;
  dailyTotalAmount?: number;
  timeRemaining: number;
  confirmationDuration: number;
}) {
  // Stage 1: 'bounce' (0..420ms) -> The single central logo on KioskMainScreen turns emerald green from within itself (no duplicate logo superimposed)
  // Stage 2: 'expand' (420ms..end-380ms) -> Green confirmation screen expands radially from the center of that same logo
  // Stage 3: 'contract' (last 380ms) -> Green confirmation screen contracts back into the center of that same logo
  const [stage, setStage] = useState<'bounce' | 'expand' | 'contract'>('bounce');

  useEffect(() => {
    setStage('bounce');
    const expandTimer = setTimeout(() => {
      setStage('expand');
    }, 420);

    const totalMs = Math.max(2000, confirmationDuration * 1000);
    const contractTimer = setTimeout(() => {
      setStage('contract');
    }, Math.max(950, totalMs - 380));

    return () => {
      clearTimeout(expandTimer);
      clearTimeout(contractTimer);
    };
  }, [payment.id, confirmationDuration]);

  const isExpanded = stage === 'expand';
  const isBounce = stage === 'bounce';

  return (
    <div className="absolute inset-0 z-30 pointer-events-auto overflow-hidden flex items-center justify-center">
      {/* Phase 1 Shockwaves radiating from the single central green logo */}
      <div
        className="absolute w-48 h-48 rounded-full border-4 border-emerald-300 pointer-events-none transition-all duration-500 ease-out"
        style={{
          transform: isBounce ? 'scale(1.65)' : 'scale(2.2)',
          opacity: isBounce ? 0.85 : 0
        }}
      />
      <div
        className="absolute w-44 h-44 rounded-full border-2 border-white pointer-events-none transition-all duration-500 delay-75 ease-out"
        style={{
          transform: isBounce ? 'scale(1.38)' : 'scale(1.9)',
          opacity: isBounce ? 0.75 : 0
        }}
      />

      {/* Radial Emerald Green Screen born directly from the center of the same logo (without superimposing another logo on top) */}
      <div
        className="absolute inset-0 bg-gradient-to-b from-emerald-500 via-emerald-600 to-emerald-700 text-white flex flex-col justify-between p-6"
        style={{
          clipPath: isExpanded
            ? 'circle(145% at 50% 50%)'
            : 'circle(0% at 50% 50%)',
          transition: isExpanded
            ? 'clip-path 500ms cubic-bezier(0.22, 1.18, 0.36, 1)'
            : 'clip-path 340ms cubic-bezier(0.4, 0, 0.2, 1)'
        }}
      >
        {/* Top Header Confirmation Badge */}
        <div
          className="flex flex-col items-center pt-5 transition-all duration-300"
          style={{
            opacity: isExpanded ? 1 : 0,
            transform: isExpanded ? 'translateY(0) scale(1)' : 'translateY(12px) scale(0.92)'
          }}
        >
          <div className="w-20 h-20 mb-3 rounded-full bg-emerald-700/90 border-[3px] border-white flex items-center justify-center shadow-lg">
            <CheckCircle2 className="w-11 h-11 text-white stroke-[2.8]" />
          </div>
          <span className="text-[11px] uppercase tracking-widest font-bold opacity-90">
            TRANSACCIÓN CONFIRMADA
          </span>
          <h2 className="text-2xl font-black tracking-wide mt-0.5">PAGO RECIBIDO</h2>
        </div>

        {/* Exact Amount & Real Payer Name */}
        <div
          className="text-center my-auto w-full transition-all duration-300 delay-75"
          style={{
            opacity: isExpanded ? 1 : 0,
            transform: isExpanded ? 'scale(1)' : 'scale(0.86)'
          }}
        >
          <div className="text-5xl font-black tracking-tight drop-shadow-md tabular-nums">
            {formatCurrency(payment.amount)}
          </div>
          {payment.senderName && (
            <div className="mt-3 mx-1 px-4 py-2.5 rounded-2xl bg-white/20 border border-white/45 backdrop-blur-sm shadow-lg">
              <div className="text-[10px] font-bold uppercase tracking-widest text-white/90">
                ENVIADO POR
              </div>
              <div className="mt-0.5 text-base font-extrabold text-white leading-snug">
                {payment.senderName}
              </div>
            </div>
          )}
          <div className="inline-block mt-3 px-4 py-1.5 rounded-full bg-black/20 text-xs font-semibold backdrop-blur">
            Detectado por {payment.appName}
          </div>
        </div>

        {/* Footer Countdown */}
        <div
          className="flex flex-col items-center pb-3 w-full transition-opacity duration-200"
          style={{ opacity: isExpanded ? 1 : 0 }}
        >
          <span className="text-xs font-medium opacity-90 font-mono tabular-nums">
            Hora: {formatExactColombiaTime(payment.timestampMillis, payment.hasExactSeconds)}
          </span>

          <div className="w-full bg-white/25 h-2 rounded-full overflow-hidden mt-3">
            <div
              className="bg-white h-full transition-all duration-1000 ease-linear rounded-full"
              style={{ width: `${(timeRemaining / confirmationDuration) * 100}%` }}
            />
          </div>
          <span className="text-[11px] opacity-85 mt-1.5">
            Volviendo a espera activa ({timeRemaining}s)...
          </span>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  // App settings state
  const [businessName, setBusinessName] = useState('Mi Negocio');
  const [scanText, setScanText] = useState('ESCANEA PARA PAGAR');
  const [phoneText, setPhoneText] = useState('300 123 4567');
  const [additionalText, setAdditionalText] = useState('Verifica el nombre del negocio antes de transferir');
  const [qr1Image, setQr1Image] = useState<string | null>(() => {
    try {
      return localStorage.getItem('ambiorix_qr1');
    } catch {
      return null;
    }
  });
  const [qr2Image, setQr2Image] = useState<string | null>(() => {
    try {
      return localStorage.getItem('ambiorix_qr2');
    } catch {
      return null;
    }
  });
  const [qr2Enabled, setQr2Enabled] = useState(false);
  const [soundEnabled, setSoundEnabled] = useState(true);
  const [ttsEnabled, setTtsEnabled] = useState(true);
  const [includeThankYou, setIncludeThankYou] = useState(true);
  const [confirmationDuration, setConfirmationDuration] = useState(7);
  const [pinCode] = useState('1234');
  const [notificationAccessGranted, setNotificationAccessGranted] = useState(true);

  const qr1InputRef = useRef<HTMLInputElement>(null);
  const qr2InputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    try {
      if (qr1Image) localStorage.setItem('ambiorix_qr1', qr1Image);
      else localStorage.removeItem('ambiorix_qr1');
    } catch (e) {
      console.warn('Storage quota exceeded or error saving qr1', e);
    }
  }, [qr1Image]);

  useEffect(() => {
    try {
      if (qr2Image) localStorage.setItem('ambiorix_qr2', qr2Image);
      else localStorage.removeItem('ambiorix_qr2');
    } catch (e) {
      console.warn('Storage quota exceeded or error saving qr2', e);
    }
  }, [qr2Image]);

  const handleQrUpload = (e: React.ChangeEvent<HTMLInputElement>, target: 'qr1' | 'qr2') => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      const dataUrl = event.target?.result as string;
      if (dataUrl) {
        if (target === 'qr1') setQr1Image(dataUrl);
        else setQr2Image(dataUrl);
      }
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  // Ambiorix Pay Service & Commercial Schedule state
  const [isServiceEnabled, setIsServiceEnabled] = useState(true);
  const [isScheduleEnabled, setIsScheduleEnabled] = useState(false);
  const [openHour, setOpenHour] = useState(8);
  const [openMinute] = useState(0);
  const [closeHour, setCloseHour] = useState(20);
  const [closeMinute] = useState(0);
  const [activeDays, setActiveDays] = useState<number[]>([1, 2, 3, 4, 5, 6]);
  const [smsPaymentEnabled, setSmsPaymentEnabled] = useState(true);

  const formatTime12 = (h: number, m: number) => {
    const hour12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
    const amPm = h < 12 ? 'a. m.' : 'p. m.';
    return `${hour12}:${m.toString().padStart(2, '0')} ${amPm}`;
  };

  const isWithinSchedule = () => {
    if (!isScheduleEnabled) return true;
    const now = new Date();
    const day = now.getDay() === 0 ? 7 : now.getDay();
    if (!activeDays.includes(day)) return false;

    const currentMinutes = now.getHours() * 60 + now.getMinutes();
    const openMinutes = openHour * 60 + openMinute;
    const closeMinutes = closeHour * 60 + closeMinute;

    if (closeMinutes > openMinutes) {
      return currentMinutes >= openMinutes && currentMinutes < closeMinutes;
    } else {
      return currentMinutes >= openMinutes || currentMinutes < closeMinutes;
    }
  };

  const operationalStatus: 'OPERATIONAL' | 'DISABLED' | 'MISSING_PERMISSION' | 'OUTSIDE_HOURS' =
    !isServiceEnabled
      ? 'DISABLED'
      : !notificationAccessGranted
      ? 'MISSING_PERMISSION'
      : !isWithinSchedule()
      ? 'OUTSIDE_HOURS'
      : 'OPERATIONAL';

  const scheduleDesc = !isScheduleEnabled
    ? 'Modo manual 24/7 (Sin horario)'
    : isWithinSchedule()
    ? `Abierto hoy hasta las ${formatTime12(closeHour, closeMinute)}`
    : `Cerrado (Horario: ${formatTime12(openHour, openMinute)} - ${formatTime12(closeHour, closeMinute)})`;

  // Active view: 'kiosk', 'settings', 'history', 'code', 'docs'
  const [activeTab, setActiveTab] = useState<'kiosk' | 'settings' | 'history' | 'code' | 'docs'>('kiosk');

  // Track document visibility so 888 glow pauses when tab/screen is hidden
  const [isPageVisible, setIsPageVisible] = useState(true);
  useEffect(() => {
    const onVisChange = () => setIsPageVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisChange);
    return () => document.removeEventListener('visibilitychange', onVisChange);
  }, []);

  // PIN modal
  const [showPinModal, setShowPinModal] = useState(false);
  const [enteredPin, setEnteredPin] = useState('');
  const [pinError, setPinError] = useState(false);

  // Payment history & real storage state (no artificial delay)
  const [isHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [paymentHistory, setPaymentHistory] = useState<PaymentRecord[]>(() => buildInitialStoredPayments());
  const [currentPayment, setCurrentPayment] = useState<PaymentRecord | null>(null);
  const [filterFeedback, setFilterFeedback] = useState<{ type: 'ok' | 'warn' | 'err'; message: string } | null>(null);

  useEffect(() => {
    try {
      localStorage.setItem('ambiorix_pay_history_v27_real', JSON.stringify(paymentHistory));
      setHistoryError(null);
    } catch {
      setHistoryError('No se pudo sincronizar el almacenamiento local del navegador.');
    }
  }, [paymentHistory]);

  // Calendar state in Colombia timezone with automatic midnight rollover (00:00:00 America/Bogota)
  const [todayKey, setTodayKey] = useState<DayKey>(() => {
    const parts = getColombiaDateParts(Date.now());
    return { year: parts.year, month: parts.month, day: parts.day };
  });

  const [displayedYear, setDisplayedYear] = useState<number>(todayKey.year);
  const [displayedMonth, setDisplayedMonth] = useState<number>(todayKey.month);
  const [selectedDayKey, setSelectedDayKey] = useState<DayKey>(todayKey);
  const [showDeveloperSealModal, setShowDeveloperSealModal] = useState<boolean>(false);

  useEffect(() => {
    const interval = setInterval(() => {
      const parts = getColombiaDateParts(Date.now());
      const currentToday: DayKey = { year: parts.year, month: parts.month, day: parts.day };
      setTodayKey(prevToday => {
        if (
          prevToday.year !== currentToday.year ||
          prevToday.month !== currentToday.month ||
          prevToday.day !== currentToday.day
        ) {
          setSelectedDayKey(prevSelected => {
            const wasOnToday =
              prevSelected.year === prevToday.year &&
              prevSelected.month === prevToday.month &&
              prevSelected.day === prevToday.day;
            if (wasOnToday) {
              setDisplayedYear(currentToday.year);
              setDisplayedMonth(currentToday.month);
              return currentToday;
            }
            return prevSelected;
          });
          return currentToday;
        }
        return prevToday;
      });
    }, 30000);
    return () => clearInterval(interval);
  }, []);

  // Confirmed unique payments
  const confirmedUniquePayments = useMemo(
    () => deduplicateConfirmedPayments(paymentHistory),
    [paymentHistory]
  );

  // Today's accumulated total (for <= 300 Silver vs > 300 Gold rule)
  const todayTotalAmount = useMemo(() => {
    return confirmedUniquePayments
      .filter(p => {
        const parts = getColombiaDateParts(p.timestampMillis);
        return (
          parts.year === todayKey.year &&
          parts.month === todayKey.month &&
          parts.day === todayKey.day
        );
      })
      .reduce((acc, p) => acc + p.amount, 0);
  }, [confirmedUniquePayments, todayKey]);

  // Daily summary for selected day
  const dailySummary = useMemo(() => {
    const dayPayments = confirmedUniquePayments.filter(p => {
      const parts = getColombiaDateParts(p.timestampMillis);
      return (
        parts.year === selectedDayKey.year &&
        parts.month === selectedDayKey.month &&
        parts.day === selectedDayKey.day
      );
    });

    const totalAmount = dayPayments.reduce((acc, p) => acc + p.amount, 0);
    const paymentCount = dayPayments.length;
    const averageAmount = paymentCount > 0 ? Math.floor(totalAmount / paymentCount) : 0;
    const monthName = MONTH_NAMES_LOWER[(selectedDayKey.month - 1 + 12) % 12];

    return {
      dayKey: selectedDayKey,
      formattedDateTitle: `${selectedDayKey.day} de ${monthName} de ${selectedDayKey.year}`,
      formattedShortDate: `${selectedDayKey.day} de ${monthName}`,
      totalAmount,
      formattedTotalAmount: formatCurrency(totalAmount),
      paymentCount,
      averageAmount,
      formattedAverageAmount: formatCurrency(averageAmount),
      payments: dayPayments
    };
  }, [confirmedUniquePayments, selectedDayKey]);

  // Monthly summary & bar chart for displayed month
  const monthlySummary = useMemo(() => {
    const daysInMonth = new Date(displayedYear, displayedMonth, 0).getDate();
    // Day of week of 1st of month (Monday = 0 .. Sunday = 6)
    const firstDate = new Date(displayedYear, displayedMonth - 1, 1);
    const jsDay = firstDate.getDay(); // 0=Sun..6=Sat
    const leadingEmpty = jsDay === 0 ? 6 : jsDay - 1;

    const calendarCells: (number | null)[] = [];
    for (let i = 0; i < leadingEmpty; i++) calendarCells.push(null);
    for (let d = 1; d <= daysInMonth; d++) calendarCells.push(d);
    while (calendarCells.length % 7 !== 0) calendarCells.push(null);

    const dailyTotals = new Map<number, number>();
    const dailyCounts = new Map<number, number>();

    for (const p of confirmedUniquePayments) {
      const parts = getColombiaDateParts(p.timestampMillis);
      if (parts.year === displayedYear && parts.month === displayedMonth) {
        dailyTotals.set(parts.day, (dailyTotals.get(parts.day) || 0) + p.amount);
        dailyCounts.set(parts.day, (dailyCounts.get(parts.day) || 0) + 1);
      }
    }

    let maxDayTotal = 0;
    dailyTotals.forEach(val => {
      if (val > maxDayTotal) maxDayTotal = val;
    });

    const dailyBars = Array.from({ length: daysInMonth }, (_, idx) => {
      const day = idx + 1;
      const totalAmount = dailyTotals.get(day) || 0;
      const count = dailyCounts.get(day) || 0;
      const heightFraction =
        maxDayTotal > 0 && totalAmount > 0
          ? Math.max(0.08, Math.min(1, totalAmount / maxDayTotal))
          : 0;
      return {
        day,
        dayKey: { year: displayedYear, month: displayedMonth, day },
        totalAmount,
        formattedTotalAmount: formatCurrency(totalAmount),
        count,
        heightFraction
      };
    });

    const totalMonthAmount = dailyBars.reduce((acc, b) => acc + b.totalAmount, 0);
    const totalMonthCount = dailyBars.reduce((acc, b) => acc + b.count, 0);

    const barsWithIncome = dailyBars.filter(b => b.totalAmount > 0);
    const bestDay =
      barsWithIncome.length > 0
        ? barsWithIncome.reduce((best, cur) => (cur.totalAmount > best.totalAmount ? cur : best))
        : null;

    const shortMonth = MONTH_NAMES_SHORT[(displayedMonth - 1 + 12) % 12];
    const formattedBestDayLabel = bestDay
      ? `${bestDay.day} ${shortMonth} (${bestDay.formattedTotalAmount})`
      : 'Sin registros';

    return {
      year: displayedYear,
      month: displayedMonth,
      monthTitle: `${MONTH_NAMES_TITLE[(displayedMonth - 1 + 12) % 12]} ${displayedYear}`,
      daysInMonth,
      totalMonthAmount,
      formattedTotalMonthAmount: formatCurrency(totalMonthAmount),
      totalMonthCount,
      bestDay,
      formattedBestDayLabel,
      dailyBars,
      daysWithPayments: new Set(barsWithIncome.map(b => b.day)),
      calendarCells
    };
  }, [confirmedUniquePayments, displayedYear, displayedMonth]);

  // Simulator notification form
  const [simPreset, setSimPreset] = useState<'nequi' | 'nequi_gold' | 'nequi_purple' | 'bancolombia' | 'daviplata' | 'outgoing' | 'custom'>('nequi');
  const [simTitle, setSimTitle] = useState('Nequi');
  const [simBody, setSimBody] = useState('¡Te enviaron plata! Carlos Rodríguez te transfirió $35.000 a tu cuenta. Saldo $120.000');
  const [simPackage, setSimPackage] = useState('com.nequi.MobileApp');

  const [isSpeaking, setIsSpeaking] = useState(false);

  // Battery level & charging state (<= 20% unplugged shows persistent red warning; <= 5% unplugged speaks warning)
  const [batteryPercentage, setBatteryPercentage] = useState<number>(100);
  const [isBatteryCharging, setIsBatteryCharging] = useState<boolean>(false);
  const isLowBatteryUnplugged = batteryPercentage >= 1 && batteryPercentage <= 20 && !isBatteryCharging;
  const isCriticalBatteryUnplugged = batteryPercentage >= 1 && batteryPercentage <= 5 && !isBatteryCharging;

  // When battery <= 5% and not charging, speak "Batería baja, por favor conéctame a cargar"
  useEffect(() => {
    if (!isCriticalBatteryUnplugged) {
      return;
    }
    const announceLowBattery = () => {
      playChimeSound();
      setTimeout(() => {
        speakSpanishPhrase(
          'Batería baja, por favor conéctame a cargar',
          () => setIsSpeaking(true),
          () => setIsSpeaking(false)
        );
      }, 220);
    };
    announceLowBattery();
    const interval = setInterval(announceLowBattery, 45000);
    return () => clearInterval(interval);
  }, [isCriticalBatteryUnplugged]);

  // Pre-load browser voices
  useEffect(() => {
    if ('speechSynthesis' in window) {
      window.speechSynthesis.getVoices();
      window.speechSynthesis.onvoiceschanged = () => {
        window.speechSynthesis.getVoices();
      };
    }
  }, []);

  // Timer countdown for success screen
  const [timeRemaining, setTimeRemaining] = useState(7);
  const timerRef = useRef<NodeJS.Timeout | null>(null);

  // Trigger payment event
  const triggerPayment = (
    amount: number,
    appName: string,
    pkgName: string,
    senderName: string | null = null,
    saveHistory: boolean = true
  ) => {
    const nowMs = Date.now();
    const randomHex = Math.floor(10000000 + Math.random() * 90000000).toString(16).toUpperCase();

    const payment: PaymentRecord = {
      id: `AMB-${randomHex}`,
      amount,
      appName,
      packageName: pkgName,
      timestampMillis: nowMs,
      senderName,
      hasExactSeconds: true,
      isSimulated: !saveHistory
    };

    if (saveHistory) {
      setPaymentHistory(prev => {
        // Deduplicate near-instant identical notifications
        const isDup = prev.some(
          existing =>
            existing.packageName.toLowerCase() === pkgName.toLowerCase() &&
            existing.amount === amount &&
            (existing.senderName || '').toLowerCase() === (senderName || '').toLowerCase() &&
            Math.abs(existing.timestampMillis - nowMs) <= 2000
        );
        if (isDup) return prev;
        return [payment, ...prev.slice(0, 999)];
      });

      const parts = getColombiaDateParts(nowMs);
      setDisplayedYear(parts.year);
      setDisplayedMonth(parts.month);
      setSelectedDayKey({ year: parts.year, month: parts.month, day: parts.day });
    }

    if (soundEnabled) {
      playChimeSound();
    }
    if (ttsEnabled) {
      setTimeout(() => {
        speakSpanishPhrase(
          getPaymentPhrase(amount, includeThankYou),
          () => setIsSpeaking(true),
          () => setIsSpeaking(false)
        );
      }, 250);
    }

    if (timerRef.current) clearInterval(timerRef.current);
    setCurrentPayment(payment);
    setTimeRemaining(confirmationDuration);
  };

  // Handle countdown interval
  useEffect(() => {
    if (currentPayment) {
      timerRef.current = setInterval(() => {
        setTimeRemaining(prev => {
          if (prev <= 1) {
            if (timerRef.current) clearInterval(timerRef.current);
            setCurrentPayment(null);
            return 0;
          }
          return prev - 1;
        });
      }, 1000);
      return () => {
        if (timerRef.current) clearInterval(timerRef.current);
      };
    }
  }, [currentPayment, confirmationDuration]);

  useEffect(() => {
    if (!isServiceEnabled && currentPayment) {
      if (timerRef.current) clearInterval(timerRef.current);
      setCurrentPayment(null);
      if ('speechSynthesis' in window) {
        window.speechSynthesis.cancel();
      }
      setIsSpeaking(false);
    }
  }, [isServiceEnabled, currentPayment]);

  // Handle preset changes
  useEffect(() => {
    if (simPreset === 'nequi') {
      setSimTitle('Nequi');
      setSimBody('¡Te enviaron plata! Carlos Rodríguez te transfirió $35.000 a tu cuenta. Saldo $140.000');
      setSimPackage('com.nequi.MobileApp');
    } else if (simPreset === 'nequi_gold') {
      setSimTitle('Nequi');
      setSimBody('Recibiste $350.000 de Juan Carlos Gómez exitosamente.');
      setSimPackage('com.nequi.MobileApp');
    } else if (simPreset === 'nequi_purple') {
      setSimTitle('Nequi');
      setSimBody('Recibiste $650.000 de Andrés Felipe Martínez exitosamente.');
      setSimPackage('com.nequi.MobileApp');
    } else if (simPreset === 'bancolombia') {
      setSimTitle('Bancolombia');
      setSimBody('Bancolombia le informa recepción de transferencia de María Pérez por $50,000.00. Nuevo saldo: $250,000');
      setSimPackage('com.bancolombia.personas.gemini');
    } else if (simPreset === 'daviplata') {
      setSimTitle('Daviplata');
      setSimBody('Has recibido $ 25.000 de Laura Gómez.');
      setSimPackage('com.davivienda.daviplataapp');
    } else if (simPreset === 'outgoing') {
      setSimTitle('Nequi');
      setSimBody('Enviaste $35.000 a Juan exitosamente.');
      setSimPackage('com.nequi.MobileApp');
    }
  }, [simPreset]);

  // Execute parser & filter logic in simulator
  const handleSimulateNotification = () => {
    if (!isServiceEnabled) {
      setFilterFeedback({
        type: 'err',
        message: 'Filtro Ambiorix Pay: El servicio está APAGADO en Configuración. No se anuncian pagos.'
      });
      return;
    }

    if (!isWithinSchedule()) {
      setFilterFeedback({
        type: 'warn',
        message: `Filtro Ambiorix Pay: Fuera de horario comercial (${scheduleDesc}). No se anuncian pagos.`
      });
      return;
    }

    const combined = `${simTitle} ${simBody}`.toLowerCase();

    const exclusions = ['enviaste', 'has enviado', 'pagaste', 'retiraste', 'codigo', 'clave dinamica', 'promocion'];
    if (exclusions.some(w => combined.includes(w))) {
      setFilterFeedback({
        type: 'err',
        message: 'Filtro Ambiorix Pay: Notificación rechazada (palabra de exclusión detectada). No es un pago entrante.'
      });
      return;
    }

    const incoming = ['recibiste', 'has recibido', 'te enviaron', 'te llego', 'te pagaron', 'te transfirieron', 'abono', 'recibio', 'recepcion de transferencia'];
    if (!incoming.some(w => combined.includes(w))) {
      setFilterFeedback({
        type: 'warn',
        message: 'Filtro Ambiorix Pay: Notificación ignorada (no contiene palabras de pago entrante).'
      });
      return;
    }

    let effective = combined;
    const balanceKeywords = ['saldo', 'disponible', 'comision'];
    let earliest = -1;
    for (const kw of balanceKeywords) {
      const idx = effective.indexOf(kw);
      if (idx !== -1 && (earliest === -1 || idx < earliest)) {
        earliest = idx;
      }
    }
    if (earliest !== -1) {
      effective = effective.substring(0, earliest);
    }

    const match = /(?:\$|cop)\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+)/i.exec(effective)
      || /([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+)\s*(?:pesos|cop)/i.exec(effective);

    if (!match) {
      setFilterFeedback({
        type: 'warn',
        message: 'Filtro Ambiorix Pay: No se pudo detectar un monto válido en la notificación.'
      });
      return;
    }

    let rawNum = match[1];
    const decMatch = /[.,](\d{1,2})$/.exec(rawNum);
    if (decMatch) {
      rawNum = rawNum.substring(0, decMatch.index);
    }
    const cleanDigits = rawNum.replace(/[.,\s]/g, '');
    const amount = parseInt(cleanDigits, 10);

    if (isNaN(amount) || amount <= 0) {
      setFilterFeedback({
        type: 'warn',
        message: 'Monto inválido extraído.'
      });
      return;
    }

    const appDisplayName = simPackage.includes('nequi')
      ? 'Nequi'
      : simPackage.includes('bancolombia')
      ? 'Bancolombia'
      : simPackage.includes('daviplata')
      ? 'Daviplata'
      : 'Billetera';

    const extractedSender = extractSenderNameFromNotification(simBody);

    setFilterFeedback({
      type: 'ok',
      message: `Pago confirmado: ${formatCurrency(amount)}${extractedSender ? ` de ${extractedSender}` : ''} (${appDisplayName}).`
    });

    triggerPayment(amount, appDisplayName, simPackage, extractedSender, true);
  };

  // PIN verification
  const handlePinDigit = (digit: string) => {
    if (enteredPin.length < 8) {
      const next = enteredPin + digit;
      setEnteredPin(next);
      setPinError(false);
      if (next.length === pinCode.length) {
        if (next === pinCode) {
          setShowPinModal(false);
          setEnteredPin('');
          setActiveTab('settings');
        } else {
          setPinError(true);
          setEnteredPin('');
        }
      }
    }
  };

  // Navigate calendar months
  const goToPreviousMonth = () => {
    const prevM = displayedMonth <= 1 ? 12 : displayedMonth - 1;
    const prevY = displayedMonth <= 1 ? displayedYear - 1 : displayedYear;
    const maxDays = new Date(prevY, prevM, 0).getDate();
    setDisplayedYear(prevY);
    setDisplayedMonth(prevM);
    setSelectedDayKey({
      year: prevY,
      month: prevM,
      day: Math.min(selectedDayKey.day, maxDays)
    });
  };

  const goToNextMonth = () => {
    const nextM = displayedMonth >= 12 ? 1 : displayedMonth + 1;
    const nextY = displayedMonth >= 12 ? displayedYear + 1 : displayedYear;
    const maxDays = new Date(nextY, nextM, 0).getDate();
    setDisplayedYear(nextY);
    setDisplayedMonth(nextM);
    setSelectedDayKey({
      year: nextY,
      month: nextM,
      day: Math.min(selectedDayKey.day, maxDays)
    });
  };

  const projectFiles = [
    {
      name: 'HistoryScreen.kt',
      path: '/app/src/main/java/com/ambiorix/pay/ui/screens/HistoryScreen.kt',
      desc: 'Calendario mensual interactivo, acumulado diario, gráfico de barras de movimiento diario y detalle con segundos.'
    },
    {
      name: 'PaymentHistoryCalculator.kt',
      path: '/app/src/main/java/com/ambiorix/pay/data/PaymentHistoryCalculator.kt',
      desc: 'Cálculo determinista por día y mes en zona horaria de Colombia (America/Bogota) con deduplicación.'
    },
    {
      name: 'SkeletonLoader.kt',
      path: '/app/src/main/java/com/ambiorix/pay/ui/components/SkeletonLoader.kt',
      desc: 'Esqueletos de carga AMOLED oscuros que respetan reducción de movimiento y ciclo de vida.'
    },
    {
      name: 'SettingsScreen.kt',
      path: '/app/src/main/java/com/ambiorix/pay/ui/screens/SettingsScreen.kt',
      desc: 'Configuración completa con pie de página Versión 2.8, Creado por El.propiooo y elemento luminoso 888.'
    },
    {
      name: 'Payment.kt & PaymentRepository.kt',
      path: '/app/src/main/java/com/ambiorix/pay/data/Payment.kt',
      desc: 'Modelo y persistencia local compatible con 2.6 GT, conservando hora exacta con segundos e ID interno.'
    },
    {
      name: 'PaymentNotificationParser.kt',
      path: '/app/src/main/java/com/ambiorix/pay/notification/PaymentNotificationParser.kt',
      desc: 'Extrae montos en COP y nombre completo del remitente desde notificaciones de Nequi y bancos.'
    },
    {
      name: 'PaymentHistoryCalculatorTest.kt',
      path: '/app/src/test/java/com/ambiorix/pay/PaymentHistoryCalculatorTest.kt',
      desc: 'Suite de pruebas JUnit para acumulado diario, deduplicación, gráfico mensual y zona horaria Colombia.'
    },
    {
      name: 'build-apk.yml',
      path: '/.github/workflows/build-apk.yml',
      desc: 'Flujo de GitHub Actions con Temurin JDK 17 y Gradle 8.9 listo para compilar APK de Ambiorix Pay 2.8 GT.'
    }
  ];

  return (
    <div className="min-h-screen bg-[#070A0F] text-slate-100 flex flex-col font-sans selection:bg-amber-500 selection:text-black">
      {/* Top Application Bar */}
      <header className="border-b border-slate-800 bg-[#0B0F17]/95 backdrop-blur sticky top-0 z-40 px-4 py-3">
        <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 rounded-full bg-[#131B2A] border border-amber-500/50 p-1 flex items-center justify-center shadow-lg shadow-amber-500/20">
              <img
                src="/assets/logo.png"
                alt="Ambiorix Pay Logo"
                className="w-full h-full object-contain"
              />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h1 className="font-extrabold text-base tracking-wide text-white">AMBIORIX PAY 2.8 GT</h1>
                <span className="text-[11px] text-slate-400 font-medium">
                  · 100% Local (Sin Internet)
                </span>
              </div>
              <p className="text-xs text-slate-400">Pantalla Inteligente para Comercios en Colombia</p>
            </div>
          </div>

          {/* Header Right: Audio Test & Navigation Controls */}
          <div className="flex flex-wrap items-center gap-2">
            {isSpeaking && (
              <span className="text-xs font-bold text-emerald-400 px-2">
                Locución activa...
              </span>
            )}

            <button
              onClick={() => triggerClientSideZipDownload()}
              className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white font-extrabold text-xs rounded-xl flex items-center space-x-1.5 shadow-md shadow-emerald-600/20 transition cursor-pointer whitespace-nowrap shrink-0"
              title="Descargar el proyecto Android completo (Versión 2.8 GT) en un archivo .ZIP"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Descargar ZIP (v2.8)</span>
            </button>

            <button
              onClick={() => {
                playChimeSound();
                setTimeout(() => {
                  speakSpanishPhrase(
                    getPaymentPhrase(35000, includeThankYou),
                    () => setIsSpeaking(true),
                    () => setIsSpeaking(false)
                  );
                }, 250);
              }}
              className="px-3 py-1.5 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs rounded-xl flex items-center space-x-1.5 shadow-md shadow-amber-500/20 transition cursor-pointer whitespace-nowrap shrink-0"
            >
              <Volume2 className="w-3.5 h-3.5" />
              <span>Probar Sonido</span>
            </button>

            {/* Navigation Controls */}
            <div className="flex items-center space-x-1 bg-slate-900/90 p-1 rounded-xl border border-slate-800">
              <button
                onClick={() => setActiveTab('kiosk')}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all flex items-center space-x-1.5 whitespace-nowrap shrink-0 ${
                  activeTab === 'kiosk'
                    ? 'bg-amber-500 text-black shadow-md shadow-amber-500/30'
                    : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
              >
                <Smartphone className="w-3.5 h-3.5" />
                <span>Kiosco</span>
              </button>

              <button
                onClick={() => setActiveTab('history')}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all flex items-center space-x-1.5 whitespace-nowrap shrink-0 ${
                  activeTab === 'history'
                    ? 'bg-amber-500 text-black shadow-md shadow-amber-500/30'
                    : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
              >
                <History className="w-3.5 h-3.5" />
                <span>Historial ({confirmedUniquePayments.length})</span>
              </button>

              <button
                onClick={() => {
                  if (activeTab === 'settings') {
                    setActiveTab('kiosk');
                  } else {
                    setShowPinModal(true);
                  }
                }}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all flex items-center space-x-1.5 whitespace-nowrap shrink-0 ${
                  activeTab === 'settings'
                    ? 'bg-amber-500 text-black shadow-md shadow-amber-500/30'
                    : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
              >
                <Settings className="w-3.5 h-3.5" />
                <span>Ajustes (PIN {pinCode})</span>
              </button>

              <button
                onClick={() => setActiveTab('code')}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all flex items-center space-x-1.5 whitespace-nowrap shrink-0 ${
                  activeTab === 'code'
                    ? 'bg-amber-500 text-black shadow-md shadow-amber-500/30'
                    : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
              >
                <FolderGit2 className="w-3.5 h-3.5" />
                <span>Archivos 2.8</span>
              </button>

              <button
                onClick={() => setActiveTab('docs')}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all flex items-center space-x-1.5 whitespace-nowrap shrink-0 ${
                  activeTab === 'docs'
                    ? 'bg-amber-500 text-black shadow-md shadow-amber-500/30'
                    : 'text-slate-300 hover:text-white hover:bg-slate-800'
                }`}
              >
                <Info className="w-3.5 h-3.5" />
                <span>Compilación</span>
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Main Container */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: Mobile Kiosk Device Frame */}
        <div className="lg:col-span-7 flex flex-col items-center justify-center">
          <div className="relative w-full max-w-[440px] h-[820px] bg-[#000000] rounded-[48px] p-3.5 shadow-2xl border-4 border-slate-700/60 ring-1 ring-white/10 flex flex-col overflow-hidden">
            {/* Phone Notch / Speaker */}
            <div className="absolute top-0 left-1/2 -translate-x-1/2 h-5 w-36 bg-slate-900 rounded-b-2xl z-30 flex items-center justify-center">
              <div className="w-12 h-1 bg-slate-800 rounded-full" />
            </div>

            {/* SCREEN CONTENT */}
            <div className="relative flex-1 rounded-[36px] overflow-hidden flex flex-col bg-[#0B0F17]">
              {/* IF SUCCESS PAYMENT OVERLAY IS ACTIVE */}
              {currentPayment ? (
                <PaymentConfirmationChoreographyPreview
                  payment={currentPayment}
                  dailyTotalAmount={todayTotalAmount}
                  timeRemaining={timeRemaining}
                  confirmationDuration={confirmationDuration}
                />
              ) : null}

              {/* KIOSK MAIN SCREEN */}
              {activeTab === 'kiosk' && (
                <div className="flex-1 flex flex-col justify-between p-5 pt-7 text-white select-none overflow-y-auto">
                  <div>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center">
                        <img
                          src="/assets/firma_ambiorix.svg"
                          alt="Firma Ambiorix"
                          className="h-9 object-contain"
                        />
                      </div>
                      <div className="flex items-center space-x-2">
                        <button
                          onClick={() => setActiveTab('history')}
                          className="w-9 h-9 rounded-full bg-slate-800/80 hover:bg-slate-700 flex items-center justify-center text-amber-400 hover:text-amber-300 transition cursor-pointer"
                          title="Historial y Calendario de Ganancias"
                        >
                          <History className="w-4 h-4" />
                        </button>
                        <button
                          onClick={() => setShowPinModal(true)}
                          className="w-9 h-9 rounded-full bg-slate-800/80 hover:bg-slate-700 flex items-center justify-center text-slate-300 hover:text-white transition cursor-pointer"
                          title="Configuración protegida por PIN"
                        >
                          <Settings className="w-4 h-4" />
                        </button>
                      </div>
                    </div>

                    {/* AVISO ROJO PERSISTENTE DE BATERÍA BAJA (<= 20% SIN CARGAR) */}
                    {isLowBatteryUnplugged && (
                      <div className="mt-3 p-3 rounded-2xl bg-red-700 border-2 border-red-300 shadow-lg shadow-red-900/50 flex items-center space-x-3">
                        <div className="w-9 h-9 rounded-full bg-white/20 flex items-center justify-center shrink-0">
                          <AlertTriangle className="w-5 h-5 text-white" />
                        </div>
                        <div className="text-left leading-tight">
                          <p className="text-xs font-extrabold text-white">
                            Batería baja, por favor conecte el celular
                          </p>
                          <p className="text-[10px] font-semibold text-red-100 mt-0.5">
                            {isCriticalBatteryUnplugged
                              ? `Nivel crítico: ${batteryPercentage}% · Alerta de voz activa ("Batería baja, por favor conéctame a cargar")`
                              : `Nivel actual: ${batteryPercentage}% · Se quitará al conectar el cargador`}
                          </p>
                        </div>
                      </div>
                    )}

                    {operationalStatus === 'DISABLED' && (
                      <div
                        onClick={() => setShowPinModal(true)}
                        className="mt-3 p-2.5 rounded-xl bg-amber-500/20 border border-amber-500/40 flex items-center space-x-2.5 cursor-pointer hover:bg-amber-500/30 transition"
                      >
                        <Power className="w-5 h-5 text-amber-400 shrink-0" />
                        <div className="text-[11px] leading-tight">
                          <p className="font-bold text-amber-200">Servicio Ambiorix Pay APAGADO</p>
                          <p className="text-slate-300">En pausa manual. No se anuncian pagos. Toca para encender.</p>
                        </div>
                      </div>
                    )}

                    {operationalStatus === 'MISSING_PERMISSION' && (
                      <div
                        onClick={() => setNotificationAccessGranted(true)}
                        className="mt-3 p-2.5 rounded-xl bg-rose-500/20 border border-rose-500/40 flex items-center space-x-2.5 cursor-pointer hover:bg-rose-500/30 transition"
                      >
                        <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0" />
                        <div className="text-[11px] leading-tight">
                          <p className="font-bold text-rose-200">Acceso a notificaciones desactivado</p>
                          <p className="text-slate-300">Toca para activar en ajustes del sistema</p>
                        </div>
                      </div>
                    )}

                    {operationalStatus === 'OUTSIDE_HOURS' && (
                      <div
                        onClick={() => setShowPinModal(true)}
                        className="mt-3 p-2.5 rounded-xl bg-blue-500/20 border border-blue-500/40 flex items-center space-x-2.5 cursor-pointer hover:bg-blue-500/30 transition"
                      >
                        <Clock className="w-5 h-5 text-blue-400 shrink-0" />
                        <div className="text-[11px] leading-tight">
                          <p className="font-bold text-blue-200">Fuera de Horario Comercial</p>
                          <p className="text-slate-300">{scheduleDesc}. No se anuncian pagos.</p>
                        </div>
                      </div>
                    )}

                    {operationalStatus === 'OPERATIONAL' && isScheduleEnabled && (
                      <div className="mt-2.5 text-[11px] text-emerald-400 font-medium">
                        {scheduleDesc}
                      </div>
                    )}

                    <div className="text-center mt-4">
                      <h2 className="text-2xl font-black text-white uppercase tracking-wide">
                        {businessName}
                      </h2>
                      <p className="text-amber-400 font-bold text-sm tracking-wider mt-0.5">
                        {scanText}
                      </p>
                    </div>
                  </div>

                  {/* Interactive Metallic Sphere -> Sculpted 3D Ambiorix Logo Animation */}
                  <div className="my-auto py-3 flex items-center justify-center">
                    <AmbiorixInteractiveLogoPreview
                      isPaymentConfirmed={currentPayment !== null}
                      dailyTotalAmount={todayTotalAmount}
                      isLowBatteryWarning={isLowBatteryUnplugged}
                      isOperational={operationalStatus === 'OPERATIONAL'}
                      isVisible={isPageVisible && activeTab === 'kiosk'}
                    />
                  </div>

                  {/* Bottom Information */}
                  <div className="flex flex-col items-center space-y-3 pb-2">
                    {phoneText && (
                      <div className="px-3.5 py-1.5 rounded-xl bg-slate-800/90 border border-amber-500/30 text-xs font-bold text-amber-200 tabular-nums">
                        Transferir al cel: {phoneText}
                      </div>
                    )}

                    {additionalText && (
                      <p className="text-[11px] text-slate-400 text-center px-4 leading-relaxed">
                        {additionalText}
                      </p>
                    )}

                    {operationalStatus === 'OPERATIONAL' && (
                      <div className="px-5 py-2 rounded-full bg-[#131B2A] border border-amber-500/40 flex items-center space-x-2.5 shadow-lg shadow-amber-500/10">
                        <span className="text-xs font-bold tracking-wide text-amber-400">
                          Esperando pago...
                        </span>
                      </div>
                    )}

                    {operationalStatus === 'DISABLED' && (
                      <button
                        onClick={() => setShowPinModal(true)}
                        className="px-5 py-2 rounded-full bg-slate-900 border border-slate-700 flex items-center space-x-2 cursor-pointer hover:border-amber-500 transition"
                      >
                        <Power className="w-3.5 h-3.5 text-slate-400" />
                        <span className="text-xs font-semibold text-slate-400">
                          Servicio en Pausa
                        </span>
                      </button>
                    )}

                    {operationalStatus === 'OUTSIDE_HOURS' && (
                      <button
                        onClick={() => setShowPinModal(true)}
                        className="px-5 py-2 rounded-full bg-slate-900 border border-blue-500/40 flex items-center space-x-2 cursor-pointer hover:bg-slate-800 transition"
                      >
                        <Clock className="w-3.5 h-3.5 text-blue-400" />
                        <span className="text-xs font-semibold text-blue-300">
                          Fuera de Horario
                        </span>
                      </button>
                    )}

                    {operationalStatus === 'MISSING_PERMISSION' && (
                      <button
                        onClick={() => setNotificationAccessGranted(true)}
                        className="px-5 py-2 rounded-full bg-rose-500/20 border border-rose-500/50 flex items-center space-x-2 cursor-pointer hover:bg-rose-500/30 transition"
                      >
                        <AlertTriangle className="w-3.5 h-3.5 text-rose-400" />
                        <span className="text-xs font-semibold text-rose-300">
                          Falta Permiso
                        </span>
                      </button>
                    )}
                  </div>
                </div>
              )}

              {/* ===================================================================== */}
              {/* 1 & 2. REDESIGNED HISTORY VIEW: CALENDAR, DAILY TOTAL & BAR CHART     */}
              {/* ===================================================================== */}
              {activeTab === 'history' && (
                isHistoryLoading ? (
                  <HistorySkeletonView />
                ) : (
                  <div className="flex-1 flex flex-col bg-[#0B0F17] text-white p-4 pt-6 space-y-3.5 overflow-y-auto">
                    {/* Top Bar */}
                    <div className="flex items-center justify-between border-b border-slate-800 pb-2.5">
                      <div className="flex items-center space-x-2">
                        <button
                          onClick={() => setActiveTab('kiosk')}
                          className="p-1.5 hover:bg-slate-800 rounded-lg text-slate-300 cursor-pointer"
                          title="Volver a Kiosco"
                        >
                          <ArrowLeft className="w-4 h-4" />
                        </button>
                        <div>
                          <h3 className="font-bold text-sm text-white leading-tight">Historial y Ganancias</h3>
                          <p className="text-[10px] text-slate-400">Calendario mensual y movimiento diario</p>
                        </div>
                      </div>

                      <div className="flex items-center space-x-2">
                        {(!dayKeyEquals(selectedDayKey, todayKey) ||
                          displayedYear !== todayKey.year ||
                          displayedMonth !== todayKey.month) && (
                          <button
                            onClick={() => {
                              setDisplayedYear(todayKey.year);
                              setDisplayedMonth(todayKey.month);
                              setSelectedDayKey(todayKey);
                            }}
                            className="px-2 py-1 rounded-lg bg-amber-500/15 hover:bg-amber-500/25 text-amber-400 font-bold text-[11px] transition cursor-pointer"
                          >
                            Hoy
                          </button>
                        )}
                      </div>
                    </div>

                    {historyError && (
                      <div className="p-2.5 rounded-xl bg-rose-950/60 border border-rose-500/50 text-xs text-rose-200">
                        {historyError}
                      </div>
                    )}

                    {/* ACUMULADO DIARIO (DESTACADO EN LA PARTE SUPERIOR) */}
                    <div className={`p-3.5 rounded-2xl bg-[#131B2A] border ${
                      dailySummary.totalAmount > 0 ? 'border-amber-500/60' : 'border-slate-800'
                    }`}>
                      <div className="text-[11px] font-bold text-amber-400">
                        {dayKeyEquals(selectedDayKey, todayKey)
                          ? `Fecha seleccionada: Hoy (${dailySummary.formattedDateTitle})`
                          : `Fecha seleccionada: ${dailySummary.formattedDateTitle}`}
                      </div>

                      <div className={`text-lg sm:text-xl font-black tracking-tight mt-1 tabular-nums ${
                        dailySummary.totalAmount > 0 ? 'text-emerald-400' : 'text-white'
                      }`}>
                        TOTAL RECIBIDO HOY: {dailySummary.formattedTotalAmount}
                      </div>

                      {dailySummary.paymentCount === 0 ? (
                        <div className="mt-2.5 p-2.5 rounded-xl bg-[#0B0F17]/80 border border-slate-800/80 text-[11px] text-slate-400 leading-relaxed">
                          Sin pagos confirmados en esta fecha. Selecciona otro día con indicador en el calendario o simula una notificación.
                        </div>
                      ) : (
                        <div className="grid grid-cols-2 gap-2 mt-2.5">
                          <div className="bg-[#1E293B] border border-slate-700/70 rounded-xl px-3 py-2">
                            <span className="text-[10px] text-slate-400 block">Pagos confirmados</span>
                            <span className="text-sm font-extrabold text-white tabular-nums">
                              {dailySummary.paymentCount}
                            </span>
                          </div>
                          <div className="bg-[#1E293B] border border-slate-700/70 rounded-xl px-3 py-2">
                            <span className="text-[10px] text-slate-400 block">Promedio por pago</span>
                            <span className="text-sm font-extrabold text-amber-300 tabular-nums">
                              {dailySummary.formattedAverageAmount}
                            </span>
                          </div>
                        </div>
                      )}
                    </div>

                    {/* CALENDARIO MENSUAL INTERACTIVO */}
                    <div className="p-3.5 rounded-2xl bg-[#131B2A] border border-slate-800 space-y-2.5">
                      <div className="flex items-center justify-between">
                        <button
                          onClick={goToPreviousMonth}
                          className="w-8 h-8 rounded-lg bg-[#1E293B] hover:bg-slate-700 flex items-center justify-center text-white transition cursor-pointer"
                          title="Mes anterior"
                        >
                          <ChevronLeft className="w-4 h-4" />
                        </button>

                        <div className="flex items-center space-x-1.5">
                          <Calendar className="w-4 h-4 text-amber-400" />
                          <span className="text-xs font-extrabold text-white tracking-wide">
                            {monthlySummary.monthTitle}
                          </span>
                        </div>

                        <button
                          onClick={goToNextMonth}
                          className="w-8 h-8 rounded-lg bg-[#1E293B] hover:bg-slate-700 flex items-center justify-center text-white transition cursor-pointer"
                          title="Mes siguiente"
                        >
                          <ChevronRight className="w-4 h-4" />
                        </button>
                      </div>

                      {/* Weekday Headers */}
                      <div className="grid grid-cols-7 gap-1 text-center pt-1">
                        {WEEKDAY_HEADERS.map((d, i) => (
                          <div key={i} className="text-[10px] font-bold text-slate-500">
                            {d}
                          </div>
                        ))}
                      </div>

                      {/* Calendar Grid */}
                      <div className="grid grid-cols-7 gap-1">
                        {monthlySummary.calendarCells.map((dayNum, idx) => {
                          if (dayNum === null) {
                            return <div key={`empty-${idx}`} className="aspect-square" />;
                          }
                          const cellKey: DayKey = {
                            year: monthlySummary.year,
                            month: monthlySummary.month,
                            day: dayNum
                          };
                          const isSelected = dayKeyEquals(cellKey, selectedDayKey);
                          const isToday = dayKeyEquals(cellKey, todayKey);
                          const hasPayments = monthlySummary.daysWithPayments.has(dayNum);

                          return (
                            <button
                              key={`day-${dayNum}`}
                              type="button"
                              onClick={() => setSelectedDayKey(cellKey)}
                              className={`aspect-square rounded-xl flex flex-col items-center justify-center transition cursor-pointer border text-xs tabular-nums ${
                                isSelected
                                  ? 'bg-amber-500 text-black font-black border-amber-400 shadow-sm shadow-amber-500/30'
                                  : hasPayments
                                  ? 'bg-[#1E293B] text-white font-bold border-emerald-500/40 hover:border-amber-400'
                                  : isToday
                                  ? 'bg-[#0B0F17] text-amber-300 font-bold border-amber-500/70 hover:bg-slate-900'
                                  : 'bg-[#0B0F17]/60 text-slate-500 border-slate-800/70 hover:text-slate-300 hover:bg-slate-900/60'
                              }`}
                            >
                              <span>{dayNum}</span>
                              <span
                                className={`w-1.5 h-1.5 rounded-full mt-0.5 ${
                                  isSelected && hasPayments
                                    ? 'bg-black'
                                    : hasPayments
                                    ? 'bg-emerald-400'
                                    : 'bg-transparent'
                                }`}
                              />
                            </button>
                          );
                        })}
                      </div>

                      {/* Legend */}
                      <div className="flex items-center space-x-4 pt-1 text-[10px] text-slate-400">
                        <div className="flex items-center space-x-1.5">
                          <span className="w-2 h-2 rounded-full bg-emerald-400 inline-block" />
                          <span>Día con pagos</span>
                        </div>
                        <div className="flex items-center space-x-1.5">
                          <span className="w-2 h-2 rounded-full bg-amber-500 inline-block" />
                          <span>Día seleccionado</span>
                        </div>
                      </div>
                    </div>

                    {/* GRÁFICO DE BARRAS: MOVIMIENTO DIARIO + RESUMEN DEL MES */}
                    <div className="p-3.5 rounded-2xl bg-[#131B2A] border border-slate-800 space-y-3">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center space-x-1.5">
                          <BarChart3 className="w-4 h-4 text-amber-400" />
                          <div>
                            <h4 className="text-xs font-bold text-white leading-tight">Movimiento Diario</h4>
                            <span className="text-[10px] text-slate-400">{monthlySummary.monthTitle}</span>
                          </div>
                        </div>
                        <div className="text-right">
                          <span className="text-[10px] font-bold text-amber-400 block">
                            Día {selectedDayKey.day}
                          </span>
                          <span className={`text-xs font-extrabold tabular-nums ${
                            dailySummary.totalAmount > 0 ? 'text-emerald-400' : 'text-slate-400'
                          }`}>
                            {dailySummary.formattedTotalAmount}
                          </span>
                        </div>
                      </div>

                      {/* Horizontal Scrollable Bars */}
                      <div className="bg-[#0B0F17]/80 border border-slate-800/80 rounded-xl p-2.5 overflow-x-auto">
                        <div className="flex items-end space-x-1.5 min-w-max h-24 pt-2">
                          {monthlySummary.dailyBars.map(bar => {
                            const isSelected = dayKeyEquals(bar.dayKey, selectedDayKey);
                            const hasIncome = bar.totalAmount > 0;
                            const barHeightPx = hasIncome ? Math.max(8, Math.round(bar.heightFraction * 68)) : 3;

                            return (
                              <button
                                key={bar.day}
                                type="button"
                                onClick={() => setSelectedDayKey(bar.dayKey)}
                                className="flex flex-col items-center justify-end w-5 group cursor-pointer focus:outline-none"
                                title={`Día ${bar.day}: ${bar.formattedTotalAmount} (${bar.count} pagos)`}
                              >
                                <div className="h-[70px] w-full flex items-end justify-center">
                                  <div
                                    className={`w-3 rounded-t transition-all ${
                                      isSelected
                                        ? 'bg-amber-500 w-3.5 shadow-sm shadow-amber-500/40'
                                        : hasIncome
                                        ? 'bg-slate-500 group-hover:bg-slate-400'
                                        : 'bg-slate-800/90'
                                    }`}
                                    style={{ height: `${barHeightPx}px` }}
                                  />
                                </div>
                                <span
                                  className={`text-[9px] mt-1 tabular-nums ${
                                    isSelected
                                      ? 'text-amber-400 font-extrabold'
                                      : hasIncome
                                      ? 'text-slate-200 font-semibold'
                                      : 'text-slate-600'
                                  }`}
                                >
                                  {bar.day}
                                </span>
                              </button>
                            );
                          })}
                        </div>
                      </div>

                      {/* Resumen del mes (3 indicadores) */}
                      <div className="grid grid-cols-3 gap-1.5">
                        <div className="bg-[#1E293B] border border-slate-700/70 rounded-xl p-2">
                          <span className="text-[9px] text-slate-400 block truncate">Total del mes</span>
                          <span className="text-[11px] font-extrabold text-emerald-400 tabular-nums block truncate mt-0.5">
                            {monthlySummary.formattedTotalMonthAmount}
                          </span>
                        </div>
                        <div className="bg-[#1E293B] border border-slate-700/70 rounded-xl p-2">
                          <span className="text-[9px] text-slate-400 block truncate">Mayor ingreso</span>
                          <span className="text-[11px] font-extrabold text-amber-300 tabular-nums block truncate mt-0.5">
                            {monthlySummary.formattedBestDayLabel}
                          </span>
                        </div>
                        <div className="bg-[#1E293B] border border-slate-700/70 rounded-xl p-2">
                          <span className="text-[9px] text-slate-400 block truncate">Pagos del mes</span>
                          <span className="text-[11px] font-extrabold text-white tabular-nums block truncate mt-0.5">
                            {monthlySummary.totalMonthCount}
                          </span>
                        </div>
                      </div>
                    </div>

                    {/* DETALLE DE LOS PAGOS DEL DÍA SELECCIONADO */}
                    <div className="space-y-2 pt-1">
                      <div className="flex items-center justify-between">
                        <h4 className="text-xs font-extrabold text-white">
                          Pagos del {dailySummary.formattedShortDate}
                        </h4>
                        <span className="text-[11px] font-semibold text-slate-400 tabular-nums">
                          {dailySummary.paymentCount === 1
                            ? '1 registro'
                            : `${dailySummary.paymentCount} registros`}
                        </span>
                      </div>

                      {dailySummary.payments.length === 0 ? (
                        <div className="bg-[#131B2A] border border-slate-800/80 rounded-2xl p-6 text-center space-y-1.5">
                          <Receipt className="w-7 h-7 text-slate-600 mx-auto" />
                          <p className="text-xs font-bold text-slate-200">Sin movimientos registrados</p>
                          <p className="text-[11px] text-slate-400">
                            No existen pagos confirmados el {dailySummary.formattedDateTitle}.
                          </p>
                        </div>
                      ) : (
                        <div className="space-y-2">
                          {dailySummary.payments.map(p => {
                            const exactTime = formatExactColombiaTime(p.timestampMillis, p.hasExactSeconds);
                            const exactDate = formatColombiaDateDDMMYYYY(p.timestampMillis);
                            const senderLabel = p.senderName && p.senderName.trim().length > 0
                              ? p.senderName
                              : `${p.appName} (sin nombre en notificación)`;

                            return (
                              <div
                                key={p.id}
                                className="bg-[#131B2A] p-3 rounded-2xl border border-slate-800 space-y-1.5"
                              >
                                <div className="flex items-center justify-between gap-2">
                                  <div className="text-xs font-bold text-white truncate">
                                    <span className="font-mono text-amber-300 tabular-nums">{exactTime}</span>
                                    <span className="text-slate-500"> — </span>
                                    <span className="text-emerald-400 font-black tabular-nums">
                                      {formatCurrency(p.amount)}
                                    </span>
                                    <span className="text-slate-500"> — </span>
                                    <span className="text-slate-100">{senderLabel}</span>
                                  </div>
                                </div>

                                <div className="flex items-center justify-between text-[10px] text-slate-400 pt-0.5">
                                  <span>
                                    Fecha: {exactDate} · {p.appName}
                                  </span>
                                  <span className="font-mono text-slate-500">
                                    ID: #{p.id}
                                  </span>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  </div>
                )
              )}

              {/* ===================================================================== */}
              {/* SETTINGS VIEW WITH FOOTER & 888 ELEMENT                               */}
              {/* ===================================================================== */}
              {activeTab === 'settings' && (
                <div className="flex-1 flex flex-col bg-[#0B0F17] text-white overflow-y-auto p-4 pt-6 space-y-4">
                  <div className="flex items-center justify-between border-b border-slate-800 pb-2">
                    <div className="flex items-center space-x-2">
                      <button
                        onClick={() => setActiveTab('kiosk')}
                        className="p-1.5 hover:bg-slate-800 rounded-lg text-slate-300 cursor-pointer"
                      >
                        <ArrowLeft className="w-4 h-4" />
                      </button>
                      <h3 className="font-bold text-sm text-amber-400">Configuración Kiosco</h3>
                    </div>
                    <button
                      onClick={() => setActiveTab('history')}
                      className="px-2.5 py-1 rounded-lg bg-[#131B2A] border border-slate-700 text-[11px] font-semibold text-amber-400 hover:bg-slate-800 cursor-pointer"
                    >
                      Ver Historial
                    </button>
                  </div>

                  {/* 1. CONTROL PRINCIPAL: SERVICIO AMBIORIX PAY ENCENDIDO / APAGADO */}
                  <div className={`p-3.5 rounded-2xl border transition-all ${
                    isServiceEnabled
                      ? 'bg-[#131B2A] border-amber-500/60'
                      : 'bg-slate-900 border-slate-800 opacity-90'
                  }`}>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-2.5">
                        <div className={`w-8 h-8 rounded-xl flex items-center justify-center ${
                          isServiceEnabled ? 'bg-amber-500 text-black' : 'bg-slate-800 text-slate-400'
                        }`}>
                          <Power className="w-4 h-4 stroke-[2.5]" />
                        </div>
                        <div>
                          <span className="text-xs font-bold text-white block">Servicio Ambiorix Pay</span>
                          <span className={`text-[10px] font-extrabold uppercase tracking-wide ${
                            isServiceEnabled ? 'text-amber-400' : 'text-rose-400'
                          }`}>
                            {isServiceEnabled ? 'ESTADO: ENCENDIDO' : 'ESTADO: APAGADO'}
                          </span>
                        </div>
                      </div>
                      <input
                        type="checkbox"
                        checked={isServiceEnabled}
                        onChange={e => setIsServiceEnabled(e.target.checked)}
                        className="accent-amber-500 w-5 h-5 cursor-pointer"
                      />
                    </div>

                    <div className="mt-2.5 pt-2 border-t border-slate-800 text-[10px] text-slate-300 leading-tight">
                      {isServiceEnabled
                        ? 'Servicio activo para detectar notificaciones bancarias y SMS de pago.'
                        : 'Servicio en pausa. Se detienen los avisos sonoros y no se procesan pagos. El historial se conserva.'}
                    </div>
                  </div>

                  {/* DETECCIÓN DE SMS DE NEQUI (890806 / BRE-B) */}
                  <div className="bg-[#131B2A] p-3.5 rounded-2xl border border-amber-500/40 space-y-2.5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-2.5">
                        <div className={`w-8 h-8 rounded-xl flex items-center justify-center ${
                          smsPaymentEnabled ? 'bg-amber-500/20 text-amber-400 border border-amber-500/40' : 'bg-slate-800 text-slate-400'
                        }`}>
                          <Smartphone className="w-4 h-4" />
                        </div>
                        <div>
                          <span className="text-xs font-bold text-white block">Detección de SMS de Nequi</span>
                          <span className={`text-[10px] font-semibold ${
                            smsPaymentEnabled ? 'text-amber-400' : 'text-slate-400'
                          }`}>
                            {smsPaymentEnabled
                              ? 'ACTIVO: Detecta SMS 890806 / 85954 y Bre-B'
                              : 'Solo notificaciones push de la app'}
                          </span>
                        </div>
                      </div>
                      <input
                        type="checkbox"
                        checked={smsPaymentEnabled}
                        onChange={e => setSmsPaymentEnabled(e.target.checked)}
                        className="accent-amber-500 w-4 h-4 cursor-pointer"
                      />
                    </div>
                    {smsPaymentEnabled && (
                      <div className="p-2 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-[10px] text-emerald-300 flex items-center space-x-2">
                        <span className="w-2 h-2 rounded-full bg-emerald-400 shrink-0" />
                        <span>Doble canal SMS activo (Receptor SMS directo + Notificación de Mensajes con deduplicación)</span>
                      </div>
                    )}
                  </div>

                  {/* 2. PROGRAMACIÓN DE HORARIOS COMERCIALES */}
                  <div className="bg-[#131B2A] p-3 rounded-2xl border border-slate-800 space-y-2.5">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-2">
                        <Clock className="w-4 h-4 text-amber-400" />
                        <div>
                          <span className="text-xs font-semibold text-white block">Horario de Funcionamiento</span>
                          <span className="text-[10px] text-slate-400">
                            {isScheduleEnabled ? 'Programado por horas y días' : 'Modo manual 24/7 (Sin horario)'}
                          </span>
                        </div>
                      </div>
                      <input
                        type="checkbox"
                        checked={isScheduleEnabled}
                        onChange={e => setIsScheduleEnabled(e.target.checked)}
                        className="accent-amber-500 w-4 h-4 cursor-pointer"
                      />
                    </div>

                    <div className="p-2 rounded-xl bg-slate-950 border border-slate-800 text-[11px] flex items-center justify-between">
                      <span className="text-slate-400">Estado comercial:</span>
                      <span className={`font-semibold ${isWithinSchedule() ? 'text-emerald-400' : 'text-blue-400'}`}>
                        {scheduleDesc}
                      </span>
                    </div>

                    {isScheduleEnabled && (
                      <div className="space-y-3 pt-2 border-t border-slate-800 text-xs">
                        <div>
                          <span className="text-[11px] text-slate-300 font-medium block mb-1.5">Días activos:</span>
                          <div className="grid grid-cols-7 gap-1">
                            {[
                              { id: 1, label: 'L' },
                              { id: 2, label: 'M' },
                              { id: 3, label: 'M' },
                              { id: 4, label: 'J' },
                              { id: 5, label: 'V' },
                              { id: 6, label: 'S' },
                              { id: 7, label: 'D' }
                            ].map(d => {
                              const active = activeDays.includes(d.id);
                              return (
                                <button
                                  key={d.id}
                                  type="button"
                                  onClick={() => {
                                    setActiveDays(prev =>
                                      prev.includes(d.id) ? prev.filter(x => x !== d.id) : [...prev, d.id]
                                    );
                                  }}
                                  className={`py-1.5 rounded-lg text-xs font-bold transition cursor-pointer ${
                                    active
                                      ? 'bg-amber-500 text-black'
                                      : 'bg-slate-800 text-slate-300 hover:text-white'
                                  }`}
                                >
                                  {d.label}
                                </button>
                              );
                            })}
                          </div>
                        </div>

                        <div className="grid grid-cols-2 gap-2">
                          <div className="bg-slate-950 p-2 rounded-xl border border-slate-800">
                            <span className="text-[10px] text-slate-400 block">Apertura:</span>
                            <select
                              value={openHour}
                              onChange={e => setOpenHour(Number(e.target.value))}
                              className="mt-1 w-full bg-slate-900 text-white text-xs rounded px-1.5 py-1 border border-slate-700 outline-none"
                            >
                              {Array.from({ length: 24 }).map((_, i) => (
                                <option key={i} value={i}>
                                  {formatTime12(i, 0)}
                                </option>
                              ))}
                            </select>
                          </div>

                          <div className="bg-slate-950 p-2 rounded-xl border border-slate-800">
                            <span className="text-[10px] text-slate-400 block">Cierre:</span>
                            <select
                              value={closeHour}
                              onChange={e => setCloseHour(Number(e.target.value))}
                              className="mt-1 w-full bg-slate-900 text-white text-xs rounded px-1.5 py-1 border border-slate-700 outline-none"
                            >
                              {Array.from({ length: 24 }).map((_, i) => (
                                <option key={i} value={i}>
                                  {formatTime12(i, 0)}
                                </option>
                              ))}
                            </select>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Test Payment Button */}
                  <button
                    onClick={() => {
                      triggerPayment(35000, 'Prueba de Sistema', 'com.ambiorix.pay.test', null, false);
                      setActiveTab('kiosk');
                    }}
                    className="w-full py-2.5 px-4 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl font-bold text-xs flex items-center justify-center space-x-2 shadow-lg shadow-emerald-600/20 transition cursor-pointer"
                  >
                    <Play className="w-4 h-4 fill-white" />
                    <span>Probar pago ($35.000)</span>
                  </button>

                  {/* Merchant Details */}
                  <div className="bg-[#131B2A] p-3 rounded-2xl border border-slate-800 space-y-2.5">
                    <label className="text-[11px] font-semibold text-slate-300 block">Nombre del Negocio</label>
                    <input
                      type="text"
                      value={businessName}
                      onChange={e => setBusinessName(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:border-amber-500 outline-none"
                    />

                    <label className="text-[11px] font-semibold text-slate-300 block">Texto de Acción</label>
                    <input
                      type="text"
                      value={scanText}
                      onChange={e => setScanText(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:border-amber-500 outline-none"
                    />

                    <label className="text-[11px] font-semibold text-slate-300 block">Teléfono Opcional</label>
                    <input
                      type="text"
                      value={phoneText}
                      onChange={e => setPhoneText(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:border-amber-500 outline-none"
                    />

                    <label className="text-[11px] font-semibold text-slate-300 block">Texto Adicional</label>
                    <input
                      type="text"
                      value={additionalText}
                      onChange={e => setAdditionalText(e.target.value)}
                      className="w-full bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:border-amber-500 outline-none"
                    />
                  </div>

                  {/* QR Controls */}
                  <div className="bg-[#131B2A] p-3.5 rounded-2xl border border-slate-800 space-y-3">
                    <div className="flex items-center space-x-2">
                      <QrCode className="w-4 h-4 text-amber-400" />
                      <h4 className="text-xs font-bold text-white uppercase tracking-wider">Códigos QR del Negocio</h4>
                    </div>

                    <input
                      type="file"
                      ref={qr1InputRef}
                      onChange={e => handleQrUpload(e, 'qr1')}
                      accept="image/png,image/jpeg,image/jpg"
                      className="hidden"
                    />
                    <input
                      type="file"
                      ref={qr2InputRef}
                      onChange={e => handleQrUpload(e, 'qr2')}
                      accept="image/png,image/jpeg,image/jpg"
                      className="hidden"
                    />

                    <div className="p-3 bg-slate-950 rounded-xl border border-slate-800 space-y-2">
                      <div className="flex items-center justify-between">
                        <div>
                          <span className="text-xs font-bold text-white block">Código QR 1 (Principal)</span>
                          <span className="text-[10px] text-slate-400">QR visible para escanear y pagar</span>
                        </div>
                        {qr1Image && (
                          <span className="text-[10px] font-bold text-emerald-400">Guardado</span>
                        )}
                      </div>

                      {qr1Image ? (
                        <div className="flex items-center space-x-3 pt-1">
                          <div className="w-14 h-14 bg-white rounded-lg p-1 border border-amber-500 shrink-0 flex items-center justify-center">
                            <img src={qr1Image} alt="Vista previa QR 1" className="w-full h-full object-contain" />
                          </div>
                          <div className="flex-1 flex flex-col space-y-1.5">
                            <button
                              onClick={() => qr1InputRef.current?.click()}
                              className="px-3 py-1.5 bg-amber-500 hover:bg-amber-400 text-black font-bold text-xs rounded-lg flex items-center justify-center space-x-1.5 transition cursor-pointer"
                            >
                              <Upload className="w-3.5 h-3.5" />
                              <span>Reemplazar QR</span>
                            </button>
                            <button
                              onClick={() => setQr1Image(null)}
                              className="px-3 py-1 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/30 font-semibold text-[11px] rounded-lg flex items-center justify-center space-x-1 transition cursor-pointer"
                            >
                              <Trash2 className="w-3 h-3" />
                              <span>Eliminar QR</span>
                            </button>
                          </div>
                        </div>
                      ) : (
                        <button
                          onClick={() => qr1InputRef.current?.click()}
                          className="w-full py-2 px-3 bg-amber-500 hover:bg-amber-400 text-black font-bold text-xs rounded-xl flex items-center justify-center space-x-2 transition cursor-pointer"
                        >
                          <ImageIcon className="w-4 h-4" />
                          <span>Importar QR desde galería</span>
                        </button>
                      )}
                    </div>

                    <div className="p-3 bg-slate-950 rounded-xl border border-slate-800 space-y-2">
                      <div className="flex items-center justify-between">
                        <div>
                          <span className="text-xs font-bold text-white block">Habilitar segundo QR (QR 2)</span>
                          <span className="text-[10px] text-slate-400">Ofrece dos opciones de pago simultáneas</span>
                        </div>
                        <input
                          type="checkbox"
                          checked={qr2Enabled}
                          onChange={e => setQr2Enabled(e.target.checked)}
                          className="accent-amber-500 w-4 h-4 cursor-pointer"
                        />
                      </div>

                      {qr2Enabled && (
                        <div className="pt-2 border-t border-slate-800">
                          {qr2Image ? (
                            <div className="flex items-center space-x-3">
                              <div className="w-14 h-14 bg-white rounded-lg p-1 border border-amber-500 shrink-0 flex items-center justify-center">
                                <img src={qr2Image} alt="Vista previa QR 2" className="w-full h-full object-contain" />
                              </div>
                              <div className="flex-1 flex flex-col space-y-1.5">
                                <button
                                  onClick={() => qr2InputRef.current?.click()}
                                  className="px-3 py-1.5 bg-amber-500 hover:bg-amber-400 text-black font-bold text-xs rounded-lg flex items-center justify-center space-x-1.5 transition cursor-pointer"
                                >
                                  <Upload className="w-3.5 h-3.5" />
                                  <span>Reemplazar QR 2</span>
                                </button>
                                <button
                                  onClick={() => setQr2Image(null)}
                                  className="px-3 py-1 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/30 font-semibold text-[11px] rounded-lg flex items-center justify-center space-x-1 transition cursor-pointer"
                                >
                                  <Trash2 className="w-3 h-3" />
                                  <span>Eliminar QR 2</span>
                                </button>
                              </div>
                            </div>
                          ) : (
                            <button
                              onClick={() => qr2InputRef.current?.click()}
                              className="w-full py-2 px-3 bg-amber-500 hover:bg-amber-400 text-black font-bold text-xs rounded-xl flex items-center justify-center space-x-2 transition cursor-pointer"
                            >
                              <ImageIcon className="w-4 h-4" />
                              <span>Importar QR 2 desde galería</span>
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Audio Controls */}
                  <div className="bg-[#131B2A] p-3 rounded-2xl border border-slate-800 space-y-3">
                    <div className="flex items-center justify-between">
                      <div>
                        <span className="text-xs font-semibold text-white block">Tono de Confirmación</span>
                        <span className="text-[10px] text-slate-400">Timbre armónico instantáneo</span>
                      </div>
                      <input
                        type="checkbox"
                        checked={soundEnabled}
                        onChange={e => setSoundEnabled(e.target.checked)}
                        className="accent-amber-500 w-4 h-4 cursor-pointer"
                      />
                    </div>

                    <div className="flex items-center justify-between pt-1 border-t border-slate-800/80">
                      <div>
                        <span className="text-xs font-semibold text-white block">Voz de Pago</span>
                        <span className="text-[10px] text-slate-400">Dice: "Pago recibido, [monto] pesos"</span>
                      </div>
                      <input
                        type="checkbox"
                        checked={ttsEnabled}
                        onChange={e => setTtsEnabled(e.target.checked)}
                        className="accent-amber-500 w-4 h-4 cursor-pointer"
                      />
                    </div>

                    <div className="flex items-center justify-between pt-1 border-t border-slate-800/80">
                      <div>
                        <span className={`text-xs font-semibold block ${ttsEnabled ? 'text-white' : 'text-slate-500'}`}>
                          Agregar "Gracias por tu compra"
                        </span>
                        <span className="text-[10px] text-slate-400">
                          {ttsEnabled ? 'Añade la frase al final' : 'Requiere voz de pago activa'}
                        </span>
                      </div>
                      <input
                        type="checkbox"
                        disabled={!ttsEnabled}
                        checked={includeThankYou}
                        onChange={e => setIncludeThankYou(e.target.checked)}
                        className="accent-amber-500 w-4 h-4 cursor-pointer disabled:opacity-40"
                      />
                    </div>

                    <div className="p-2 rounded-lg bg-slate-950 border border-slate-800 text-[11px] text-amber-400/90 italic">
                      "{getPaymentPhrase(35000, includeThankYou)}"
                    </div>

                    <button
                      onClick={() => {
                        playChimeSound();
                        setTimeout(() => {
                          speakSpanishPhrase(
                            getPaymentPhrase(35000, includeThankYou),
                            () => setIsSpeaking(true),
                            () => setIsSpeaking(false)
                          );
                        }, 250);
                      }}
                      className="w-full py-2 px-3 bg-slate-800 hover:bg-slate-700 text-amber-400 rounded-xl font-bold text-xs flex items-center justify-center space-x-2 transition border border-amber-500/30 cursor-pointer"
                    >
                      <Volume2 className="w-4 h-4" />
                      <span>Probar voz ($35.000)</span>
                    </button>
                  </div>

                  {/* Duration Slider */}
                  <div className="bg-[#131B2A] p-3 rounded-2xl border border-slate-800 space-y-1">
                    <div className="flex justify-between text-xs">
                      <span className="text-slate-200">Duración Pantalla Verde</span>
                      <span className="font-bold text-amber-400 tabular-nums">{confirmationDuration} s</span>
                    </div>
                    <input
                      type="range"
                      min={3}
                      max={30}
                      value={confirmationDuration}
                      onChange={e => setConfirmationDuration(Number(e.target.value))}
                      className="w-full accent-amber-500 cursor-pointer"
                    />
                  </div>

                  {/* ================================================================= */}
                  {/* 4. PIE DE PÁGINA EN CONFIGURACIÓN + FIRMA DE AUTOR + 888          */}
                  {/* ================================================================= */}
                  <footer
                    onClick={() => setShowDeveloperSealModal(true)}
                    className="pt-4 pb-3 text-center select-none border-t border-slate-800/60 hover:bg-slate-900/40 rounded-2xl transition cursor-pointer group"
                    title="Toca para ver el Sello de Autor e Ingeniería"
                  >
                    <p className="text-xs font-semibold text-slate-400">Ambiorix Pay</p>
                    <p className="text-[11px] text-slate-500 mt-0.5">Versión 2.8</p>
                    <p className="text-[11px] text-slate-500 mt-0.5">Creado por El.propiooo</p>
                    <div className="mt-2 flex justify-center">
                      <img
                        src="/assets/firma_ambiorix.svg"
                        alt="Firma Oficial Ambiorix"
                        className="h-11 object-contain opacity-90 group-hover:opacity-100 transition"
                      />
                    </div>
                    <div
                      aria-hidden="true"
                      className={`mt-1.5 text-sm font-extrabold tracking-[0.28em] text-amber-300 ${
                        isPageVisible ? 'ambiorix-888-glow' : 'opacity-65'
                      }`}
                    >
                      888
                    </div>
                  </footer>
                </div>
              )}

              {/* ANDROID FILES VIEW */}
              {activeTab === 'code' && (
                <div className="flex-1 flex flex-col bg-[#0B0F17] text-white p-4 pt-6 space-y-3 overflow-y-auto">
                  <div className="flex items-center space-x-2 border-b border-slate-800 pb-2">
                    <button
                      onClick={() => setActiveTab('kiosk')}
                      className="p-1.5 hover:bg-slate-800 rounded-lg text-slate-300 cursor-pointer"
                    >
                      <ArrowLeft className="w-4 h-4" />
                    </button>
                    <h3 className="font-bold text-sm text-amber-400">Estructura Android 2.8 GT</h3>
                  </div>
                  <div className="space-y-2">
                    {projectFiles.map((f, idx) => (
                      <div key={idx} className="bg-[#131B2A] border border-slate-800 rounded-xl p-3">
                        <div className="font-mono text-xs font-bold text-amber-400">{f.name}</div>
                        <div className="font-mono text-[10px] text-slate-500 break-all mt-0.5">{f.path}</div>
                        <p className="text-[11px] text-slate-300 mt-1">{f.desc}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* DOCS / GITHUB BUILD VIEW */}
              {activeTab === 'docs' && (
                <div className="flex-1 flex flex-col bg-[#0B0F17] text-white p-4 pt-6 space-y-3 overflow-y-auto">
                  <div className="flex items-center space-x-2 border-b border-slate-800 pb-2">
                    <button
                      onClick={() => setActiveTab('kiosk')}
                      className="p-1.5 hover:bg-slate-800 rounded-lg text-slate-300 cursor-pointer"
                    >
                      <ArrowLeft className="w-4 h-4" />
                    </button>
                    <h3 className="font-bold text-sm text-amber-400">Guía de Compilación APK 2.8 GT</h3>
                  </div>
                  <div className="bg-[#131B2A] border border-slate-800 rounded-2xl p-3.5 space-y-2 text-xs text-slate-300 leading-relaxed">
                    <p className="font-bold text-white">1. Compilación automática en GitHub Actions</p>
                    <p>
                      Descarga el archivo ZIP con el botón superior y súbelo a tu repositorio de GitHub. El flujo <code className="text-amber-300">.github/workflows/build-apk.yml</code> ejecuta las pruebas unitarias (<code className="text-emerald-300">testDebugUnitTest</code>) y genera <code className="text-emerald-300">AmbiorixPay-Debug.apk</code>.
                    </p>
                    <p className="font-bold text-white pt-2">2. Compilación local en Android Studio</p>
                    <p>
                      Abre la carpeta raíz con JDK 17 (Temurin) y Gradle 8.9 y ejecuta <code className="text-amber-300">./gradlew assembleDebug</code>.
                    </p>
                  </div>
                </div>
              )}
            </div>

            {/* Android Navigation Bar Simulated Indicator */}
            <div className="h-4 flex items-center justify-center pt-1">
              <div className="w-24 h-1 bg-slate-700/60 rounded-full" />
            </div>
          </div>
        </div>

        {/* Right Column: Interactive Notification Simulator & Inspector */}
        <div className="lg:col-span-5 flex flex-col space-y-6">
          {/* INTERACTIVE NOTIFICATION INJECTOR */}
          <div className="bg-[#0B0F17] border border-slate-800 rounded-3xl p-5 shadow-xl">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3 mb-4">
              <div className="flex items-center space-x-2.5">
                <div className="w-8 h-8 rounded-xl bg-amber-500/20 text-amber-400 flex items-center justify-center border border-amber-500/30">
                  <Bell className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-white">Simulador de Notificaciones Nequi / Bancos</h3>
                  <p className="text-[11px] text-slate-400">Extrae valor, remitente y hora exacta con segundos</p>
                </div>
              </div>
            </div>

            {/* Presets */}
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 mb-4">
              <button
                onClick={() => setSimPreset('nequi')}
                className={`px-2 py-1.5 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                  simPreset === 'nequi'
                    ? 'bg-slate-800 text-slate-100 border-slate-400'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
              >
                Nequi ($35k · Plata)
              </button>
              <button
                onClick={() => setSimPreset('nequi_gold')}
                className={`px-2 py-1.5 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                  simPreset === 'nequi_gold'
                    ? 'bg-amber-950/60 text-amber-200 border-amber-500'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
                title="Supera los $300.000 para cambiar el logo a Dorado"
              >
                Nequi ($350k · Oro)
              </button>
              <button
                onClick={() => setSimPreset('nequi_purple')}
                className={`px-2 py-1.5 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                  simPreset === 'nequi_purple'
                    ? 'bg-purple-950/60 text-purple-200 border-purple-500'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
                title="Supera los $600.000 para cambiar la esfera y logo a Morado"
              >
                Nequi ($650k · Morado)
              </button>
              <button
                onClick={() => setSimPreset('bancolombia')}
                className={`px-2 py-1.5 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                  simPreset === 'bancolombia'
                    ? 'bg-yellow-950/60 text-yellow-200 border-yellow-500'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
              >
                Bancolombia
              </button>
              <button
                onClick={() => setSimPreset('daviplata')}
                className={`px-2 py-1.5 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                  simPreset === 'daviplata'
                    ? 'bg-red-950/60 text-red-200 border-red-500'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
              >
                Daviplata
              </button>
              <button
                onClick={() => setSimPreset('outgoing')}
                className={`px-2 py-1.5 rounded-xl text-xs font-semibold border transition cursor-pointer ${
                  simPreset === 'outgoing'
                    ? 'bg-rose-950/60 text-rose-200 border-rose-500'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
              >
                Salida
              </button>
            </div>

            {/* Inputs */}
            <div className="space-y-3">
              <div>
                <label className="text-[11px] font-semibold text-slate-300">Package Name de la App</label>
                <input
                  type="text"
                  value={simPackage}
                  onChange={e => setSimPackage(e.target.value)}
                  className="w-full mt-1 bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 text-xs text-slate-200 font-mono outline-none focus:border-amber-500"
                />
              </div>

              <div>
                <label className="text-[11px] font-semibold text-slate-300">Título Notificación</label>
                <input
                  type="text"
                  value={simTitle}
                  onChange={e => setSimTitle(e.target.value)}
                  className="w-full mt-1 bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 text-xs text-slate-200 outline-none focus:border-amber-500"
                />
              </div>

              <div>
                <label className="text-[11px] font-semibold text-slate-300">Cuerpo del Mensaje (Texto)</label>
                <textarea
                  value={simBody}
                  onChange={e => setSimBody(e.target.value)}
                  rows={2}
                  className="w-full mt-1 bg-slate-900 border border-slate-700 rounded-xl px-3 py-2 text-xs text-slate-200 outline-none focus:border-amber-500"
                />
              </div>

              {filterFeedback && (
                <div
                  className={`p-2.5 rounded-xl text-xs border ${
                    filterFeedback.type === 'ok'
                      ? 'bg-emerald-950/50 border-emerald-500/40 text-emerald-200'
                      : filterFeedback.type === 'warn'
                      ? 'bg-amber-950/50 border-amber-500/40 text-amber-200'
                      : 'bg-rose-950/50 border-rose-500/40 text-rose-200'
                  }`}
                >
                  {filterFeedback.message}
                </div>
              )}

              <button
                onClick={handleSimulateNotification}
                className="w-full py-2.5 bg-amber-500 hover:bg-amber-400 text-black font-extrabold rounded-xl text-xs flex items-center justify-center space-x-2 transition shadow-lg shadow-amber-500/20 cursor-pointer"
              >
                <Send className="w-3.5 h-3.5" />
                <span>Simular Recepción de Notificación Push</span>
              </button>
            </div>
          </div>

          {/* SIMULADOR DE BATERÍA BAJA (20% AVISO ROJO / 5% VOZ) */}
          <div className="bg-[#0B0F17] border border-slate-800 rounded-3xl p-5 shadow-xl space-y-3.5">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div className="flex items-center space-x-2.5">
                <div className={`w-8 h-8 rounded-xl flex items-center justify-center border ${
                  isLowBatteryUnplugged
                    ? 'bg-red-500/20 text-red-400 border-red-500/40'
                    : 'bg-emerald-500/20 text-emerald-400 border-emerald-500/40'
                }`}>
                  <AlertTriangle className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-white">Detector de Batería (20% Aviso / 5% Voz)</h3>
                  <p className="text-[11px] text-slate-400">
                    Batería: <strong className="text-white">{batteryPercentage}%</strong> · Estado:{' '}
                    <strong className={isBatteryCharging ? 'text-emerald-400' : 'text-amber-400'}>
                      {isBatteryCharging ? 'Cargando (Conectado)' : 'Descargando (Sin cable)'}
                    </strong>
                  </p>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2">
              <button
                type="button"
                onClick={() => {
                  setBatteryPercentage(100);
                  setIsBatteryCharging(false);
                }}
                className={`px-2.5 py-2 rounded-xl text-xs font-bold border transition cursor-pointer ${
                  batteryPercentage > 20 && !isBatteryCharging
                    ? 'bg-slate-800 text-white border-slate-400'
                    : 'bg-slate-900 text-slate-400 border-slate-800 hover:text-white'
                }`}
              >
                100% Normal
              </button>

              <button
                type="button"
                onClick={() => {
                  setBatteryPercentage(20);
                  setIsBatteryCharging(false);
                  setActiveTab('kiosk');
                }}
                className={`px-2.5 py-2 rounded-xl text-xs font-bold border transition cursor-pointer ${
                  batteryPercentage === 20 && !isBatteryCharging
                    ? 'bg-red-900/70 text-red-100 border-red-400'
                    : 'bg-slate-900 text-red-300 border-red-900/60 hover:bg-red-950/50'
                }`}
              >
                20% (Aviso Rojo)
              </button>

              <button
                type="button"
                onClick={() => {
                  setBatteryPercentage(5);
                  setIsBatteryCharging(false);
                  setActiveTab('kiosk');
                }}
                className={`px-2.5 py-2 rounded-xl text-xs font-bold border transition cursor-pointer ${
                  batteryPercentage <= 5 && !isBatteryCharging
                    ? 'bg-red-700 text-white border-red-300'
                    : 'bg-slate-900 text-rose-300 border-rose-900/60 hover:bg-rose-950/50'
                }`}
              >
                5% (Aviso + Voz)
              </button>
            </div>

            <button
              type="button"
              onClick={() => {
                const nextCharging = !isBatteryCharging;
                setIsBatteryCharging(nextCharging);
                if (nextCharging && 'speechSynthesis' in window) {
                  window.speechSynthesis.cancel();
                  setIsSpeaking(false);
                }
              }}
              className={`w-full py-2.5 rounded-xl font-extrabold text-xs flex items-center justify-center space-x-2 transition border cursor-pointer ${
                isBatteryCharging
                  ? 'bg-emerald-600 hover:bg-emerald-500 text-white border-emerald-400'
                  : 'bg-slate-900 hover:bg-slate-800 text-emerald-400 border-emerald-500/40'
              }`}
            >
              <Power className="w-3.5 h-3.5" />
              <span>
                {isBatteryCharging
                  ? 'Cargador Conectado (Aviso oculto · Toca para desconectar)'
                  : 'Conectar el Celular al Cargador (Quitar Aviso Rojo)'}
              </span>
            </button>
          </div>

          {/* PROJECT ARTIFACTS AND CODE LIST */}
          <div className="bg-[#0B0F17] border border-slate-800 rounded-3xl p-5 shadow-xl space-y-3">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <h3 className="text-sm font-bold text-white flex items-center space-x-2">
                <FileCode className="w-4 h-4 text-amber-400" />
                <span>Proyecto Android Ambiorix Pay 2.8 GT</span>
              </h3>
              <span className="text-[11px] text-slate-400">Listo para compilar APK</span>
            </div>

            <button
              onClick={() => triggerClientSideZipDownload()}
              className="w-full py-2.5 px-4 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl font-bold text-xs flex items-center justify-center space-x-2 transition shadow-md shadow-emerald-600/20 cursor-pointer"
            >
              <Download className="w-4 h-4" />
              <span>Descargar Proyecto Completo v2.8 (.ZIP)</span>
            </button>

            <div className="space-y-2 max-h-[300px] overflow-y-auto pr-1">
              {projectFiles.map((file, i) => (
                <div
                  key={i}
                  className="bg-slate-900/60 hover:bg-slate-900 border border-slate-800/80 p-2.5 rounded-xl transition"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs font-bold text-amber-400">{file.name}</span>
                    <span className="text-[10px] text-slate-500 font-mono truncate">{file.path}</span>
                  </div>
                  <p className="text-[11px] text-slate-400 mt-0.5">{file.desc}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </main>

      {/* PIN ENTRY MODAL */}
      {showPinModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 animate-in fade-in">
          <div className="bg-[#0B0F17] border border-slate-800 rounded-3xl p-6 max-w-xs w-full shadow-2xl flex flex-col items-center">
            <div className="w-12 h-12 rounded-full bg-amber-500/20 text-amber-400 flex items-center justify-center mb-3">
              <Lock className="w-6 h-6" />
            </div>
            <h4 className="text-base font-bold text-white">PIN de Seguridad</h4>
            <p className="text-xs text-slate-400 text-center mt-1 mb-4">
              Ingresa el PIN del comercio (por defecto: {pinCode})
            </p>

            <div className="flex space-x-3 mb-4">
              {[0, 1, 2, 3].map(idx => (
                <div
                  key={idx}
                  className={`w-3.5 h-3.5 rounded-full border transition-all ${
                    pinError
                      ? 'bg-rose-500 border-rose-500'
                      : idx < enteredPin.length
                      ? 'bg-amber-500 border-amber-500'
                      : 'bg-slate-800 border-slate-700'
                  }`}
                />
              ))}
            </div>

            {pinError && (
              <span className="text-xs text-rose-400 font-semibold mb-3">PIN incorrecto</span>
            )}

            <div className="grid grid-cols-3 gap-2 w-full">
              {['1', '2', '3', '4', '5', '6', '7', '8', '9', 'C', '0', '⌫'].map(k => (
                <button
                  key={k}
                  onClick={() => {
                    if (k === 'C') {
                      setEnteredPin('');
                      setPinError(false);
                    } else if (k === '⌫') {
                      setEnteredPin(prev => prev.slice(0, -1));
                      setPinError(false);
                    } else {
                      handlePinDigit(k);
                    }
                  }}
                  className="h-11 bg-slate-900 hover:bg-slate-800 text-white font-bold rounded-xl text-base transition flex items-center justify-center cursor-pointer"
                >
                  {k}
                </button>
              ))}
            </div>

            <button
              onClick={() => {
                setShowPinModal(false);
                setEnteredPin('');
                setPinError(false);
              }}
              className="mt-4 text-xs text-slate-400 hover:text-white cursor-pointer"
            >
              Cancelar
            </button>
          </div>
        </div>
      )}

      {/* DEVELOPER SIGNATURE & HALL OF FAME MODAL (EASTER EGG) */}
      {showDeveloperSealModal && (
        <div
          onClick={() => setShowDeveloperSealModal(false)}
          className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 animate-in fade-in"
        >
          <div
            onClick={e => e.stopPropagation()}
            className="bg-[#0B0F17] border border-amber-500/40 rounded-3xl p-6 max-w-sm w-full shadow-2xl shadow-amber-500/10 flex flex-col items-center text-center"
          >
            <div className="w-16 h-16 rounded-full bg-[#131B2A] border-2 border-amber-500 p-2 flex items-center justify-center shadow-lg shadow-amber-500/20 mb-3">
              <img
                src="/assets/logo.png"
                alt="Emblema AAA"
                className="w-full h-full object-contain"
              />
            </div>

            <span className="text-[11px] font-extrabold tracking-[0.2em] text-amber-400 uppercase">
              Sello de Autor • Edición 2.8 GT
            </span>
            <h4 className="text-base font-black text-white mt-1">
              Ambiorix Pay — Ingeniería Oficial
            </h4>
            <p className="text-xs text-slate-400 mt-1.5 leading-relaxed">
              Arquitectura 100% local, blindada y diseñada a medida para comercios de alto tráfico en Colombia.
            </p>

            <div className="w-full mt-4 p-4 rounded-2xl bg-[#131B2A]/90 border border-amber-500/30 flex flex-col items-center">
              <img
                src="/assets/firma_ambiorix.svg"
                alt="Firma Caligráfica Ambiorix"
                className="h-16 object-contain"
              />
              <span className="text-[11px] text-slate-400 font-medium mt-1">
                Firmado por <strong className="text-amber-300">El.propiooo</strong>
              </span>
              <span className="mt-1.5 text-xs font-extrabold tracking-[0.3em] text-amber-400 ambiorix-888-glow">
                888
              </span>
            </div>

            <button
              onClick={() => setShowDeveloperSealModal(false)}
              className="mt-5 w-full py-2.5 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs rounded-xl transition cursor-pointer"
            >
              Cerrar Sello de Autor
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
