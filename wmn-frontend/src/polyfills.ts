/***************************************************************************************************
 * POLYFILLS
 *
 * Minimal polyfills to provide Node-like globals some older libs expect in the browser.
 * This keeps the bundle small and fixes 'global is not defined'.
 **************************************************************************************************/

// zone.js is required by Angular.
import 'zone.js';

// Provide Node-style `global` to the browser (some libs expect it)
;(window as any).global = window;

// Provide a minimal `process` so libs referencing process.env won't crash
;(window as any).process = (window as any).process || { env: {} };

// Optionally, provide Buffer if some lib needs it (uncomment if you see Buffer errors)
// import { Buffer } from 'buffer';
// (window as any).Buffer = (window as any).Buffer || Buffer;
