import { jsx as _jsx } from "react/jsx-runtime";
import "./styles.css";
import { Buffer } from "buffer";
// Wallet adapter packages reach for the Node `Buffer` global. Ship one.
window.Buffer = Buffer;
import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
ReactDOM.createRoot(document.getElementById("root")).render(_jsx(React.StrictMode, { children: _jsx(App, {}) }));
