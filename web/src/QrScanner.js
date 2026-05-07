import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useEffect, useRef, useState } from "react";
import { BrowserMultiFormatReader } from "@zxing/browser";
/**
 * Camera-based QR scanner overlay. Opens the user's webcam and continuously
 * decodes via @zxing/browser. Calls onResult once on a successful decode,
 * which closes the overlay (caller should toggle the flag).
 */
export function QrScanner({ onResult, onClose }) {
    const videoRef = useRef(null);
    const [error, setError] = useState(null);
    useEffect(() => {
        const reader = new BrowserMultiFormatReader();
        let cancelled = false;
        let stopFn = null;
        (async () => {
            try {
                const devices = await BrowserMultiFormatReader.listVideoInputDevices();
                if (devices.length === 0) {
                    setError("No camera available on this device.");
                    return;
                }
                const controls = await reader.decodeFromVideoDevice(devices[0].deviceId, videoRef.current, (result, _err, ctrls) => {
                    if (cancelled)
                        return;
                    if (result) {
                        ctrls.stop();
                        onResult(result.getText());
                    }
                });
                stopFn = () => controls.stop();
            }
            catch (e) {
                setError(e instanceof Error ? e.message : "Camera access failed");
            }
        })();
        return () => {
            cancelled = true;
            stopFn?.();
        };
    }, [onResult]);
    return (_jsx("div", { className: "fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4", children: _jsxs("div", { className: "bg-midnight border border-coinrim/40 rounded-3xl p-4 max-w-lg w-full", children: [_jsxs("div", { className: "flex items-center justify-between mb-3", children: [_jsx("h3", { className: "text-coingold font-bold", children: "Scan return QR" }), _jsx("button", { onClick: onClose, className: "text-muted hover:text-coingold text-sm underline", children: "Close" })] }), error ? (_jsx("p", { className: "text-red-300 text-sm py-8 text-center", children: error })) : (_jsx("video", { ref: videoRef, className: "w-full rounded-2xl bg-black", playsInline: true, muted: true })), _jsx("p", { className: "text-muted text-xs mt-3", children: "Point the camera at the QR your phone is showing." })] }) }));
}
